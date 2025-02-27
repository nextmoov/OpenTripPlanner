package org.opentripplanner.ext.siri;

import static org.opentripplanner.ext.siri.SiriTransportModeMapper.mapTransitMainMode;
import static org.opentripplanner.ext.siri.TimetableHelper.createModifiedStopTimes;
import static org.opentripplanner.ext.siri.TimetableHelper.createModifiedStops;
import static org.opentripplanner.ext.siri.TimetableHelper.createUpdatedTripTimes;
import static org.opentripplanner.model.PickDrop.NONE;
import static org.opentripplanner.model.PickDrop.SCHEDULED;

import com.google.common.base.Preconditions;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.opentripplanner.common.model.T2;
import org.opentripplanner.model.StopTime;
import org.opentripplanner.model.Timetable;
import org.opentripplanner.model.TimetableSnapshot;
import org.opentripplanner.model.TimetableSnapshotProvider;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.mappers.TransitLayerUpdater;
import org.opentripplanner.transit.model.basic.TransitMode;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.network.StopPattern;
import org.opentripplanner.transit.model.network.TripPattern;
import org.opentripplanner.transit.model.organization.Agency;
import org.opentripplanner.transit.model.organization.Operator;
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.model.timetable.RealTimeState;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.model.timetable.TripOnServiceDate;
import org.opentripplanner.transit.model.timetable.TripTimes;
import org.opentripplanner.transit.service.DefaultTransitService;
import org.opentripplanner.transit.service.TransitModel;
import org.opentripplanner.transit.service.TransitService;
import org.opentripplanner.util.time.ServiceDateUtils;
import org.rutebanken.netex.model.BusSubmodeEnumeration;
import org.rutebanken.netex.model.RailSubmodeEnumeration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.org.siri.siri20.ArrivalBoardingActivityEnumeration;
import uk.org.siri.siri20.DatedVehicleJourneyRef;
import uk.org.siri.siri20.DepartureBoardingActivityEnumeration;
import uk.org.siri.siri20.EstimatedCall;
import uk.org.siri.siri20.EstimatedTimetableDeliveryStructure;
import uk.org.siri.siri20.EstimatedVehicleJourney;
import uk.org.siri.siri20.EstimatedVersionFrameStructure;
import uk.org.siri.siri20.FramedVehicleJourneyRefStructure;
import uk.org.siri.siri20.NaturalLanguageStringStructure;
import uk.org.siri.siri20.RecordedCall;
import uk.org.siri.siri20.VehicleActivityCancellationStructure;
import uk.org.siri.siri20.VehicleActivityStructure;
import uk.org.siri.siri20.VehicleModesEnumeration;
import uk.org.siri.siri20.VehicleMonitoringDeliveryStructure;

/**
 * This class should be used to create snapshots of lookup tables of realtime data. This is
 * necessary to provide planning threads a consistent constant view of a graph with realtime data at
 * a specific point in time.
 */
public class SiriTimetableSnapshotSource implements TimetableSnapshotProvider {

  private static final Logger LOG = LoggerFactory.getLogger(SiriTimetableSnapshotSource.class);

  private static boolean keepLogging = true;
  /**
   * The working copy of the timetable snapshot. Should not be visible to routing threads. Should
   * only be modified by a thread that holds a lock on {@link #bufferLock}. All public methods that
   * might modify this buffer will correctly acquire the lock.
   */
  private final TimetableSnapshot buffer = new TimetableSnapshot();
  /**
   * Lock to indicate that buffer is in use
   */
  private final ReentrantLock bufferLock = new ReentrantLock(true);
  /**
   * Use a id generator to generate TripPattern ids for new TripPatterns created by RealTime
   * updates.
   */
  private final SiriTripPatternIdGenerator tripPatternIdGenerator = new SiriTripPatternIdGenerator();
  /**
   * A synchronized cache of trip patterns that are added to the graph due to GTFS-realtime
   * messages.
   */
  private final SiriTripPatternCache tripPatternCache = new SiriTripPatternCache(
    tripPatternIdGenerator
  );
  private final ZoneId timeZone;

  private final TransitService transitService;
  private final SiriFuzzyTripMatcher siriFuzzyTripMatcher;
  private final TransitLayerUpdater transitLayerUpdater;

  public int logFrequency = 2000;
  /**
   * If a timetable snapshot is requested less than this number of milliseconds after the previous
   * snapshot, just return the same one. Throttles the potentially resource-consuming task of
   * duplicating a TripPattern -> Timetable map and indexing the new Timetables.
   */
  public int maxSnapshotFrequency = 1000; // msec
  /**
   * The last committed snapshot that was handed off to a routing thread. This snapshot may be given
   * to more than one routing thread if the maximum snapshot frequency is exceeded.
   */
  private volatile TimetableSnapshot snapshot = null;
  /** Should expired realtime data be purged from the graph. */
  public boolean purgeExpiredData = true;
  protected LocalDate lastPurgeDate = null;
  protected long lastSnapshotTime = -1;

  public SiriTimetableSnapshotSource(final TransitModel transitModel) {
    timeZone = transitModel.getTimeZone();
    transitService = new DefaultTransitService(transitModel);
    transitLayerUpdater = transitModel.getTransitLayerUpdater();
    siriFuzzyTripMatcher = new SiriFuzzyTripMatcher(transitService);
  }

  /**
   * @return an up-to-date snapshot mapping TripPatterns to Timetables. This snapshot and the
   * timetable objects it references are guaranteed to never change, so the requesting thread is
   * provided a consistent view of all TripTimes. The routing thread need only release its reference
   * to the snapshot to release resources.
   */
  public TimetableSnapshot getTimetableSnapshot() {
    TimetableSnapshot snapshotToReturn;

    // Try to get a lock on the buffer
    if (bufferLock.tryLock()) {
      // Make a new snapshot if necessary
      try {
        snapshotToReturn = getTimetableSnapshot(false);
      } finally {
        bufferLock.unlock();
      }
    } else {
      // No lock could be obtained because there is either a snapshot commit busy or updates
      // are applied at this moment, just return the current snapshot
      snapshotToReturn = snapshot;
    }

    return snapshotToReturn;
  }

  /**
   * Method to apply a trip update list to the most recent version of the timetable snapshot.
   *
   * @param transitModel       transitModel to update (needed for adding/changing stop patterns)
   * @param fullDataset true iff the list with updates represent all updates that are active right
   *                    now, i.e. all previous updates should be disregarded
   * @param updates     SIRI VehicleMonitoringDeliveries that should be applied atomically
   */
  public void applyVehicleMonitoring(
    final TransitModel transitModel,
    final String feedId,
    final boolean fullDataset,
    final List<VehicleMonitoringDeliveryStructure> updates
  ) {
    if (updates == null) {
      LOG.warn("updates is null");
      return;
    }

    // Acquire lock on buffer
    bufferLock.lock();

    try {
      if (fullDataset) {
        // Remove all updates from the buffer
        buffer.clear(feedId);
      }

      for (VehicleMonitoringDeliveryStructure vmDelivery : updates) {
        LocalDate serviceDate = LocalDate.now(transitService.getTimeZone());

        List<VehicleActivityStructure> activities = vmDelivery.getVehicleActivities();
        if (activities != null) {
          //Handle activities
          LOG.info("Handling {} VM-activities.", activities.size());
          int handledCounter = 0;
          int skippedCounter = 0;
          for (VehicleActivityStructure activity : activities) {
            boolean handled = handleModifiedTrip(transitModel, activity, serviceDate, feedId);
            if (handled) {
              handledCounter++;
            } else {
              skippedCounter++;
            }
          }
          LOG.info("Applied {} VM-activities, skipped {}.", handledCounter, skippedCounter);
        }
        List<VehicleActivityCancellationStructure> cancellations = vmDelivery.getVehicleActivityCancellations();
        if (cancellations != null && !cancellations.isEmpty()) {
          //Handle cancellations
          LOG.info("TODO: Handle {} cancellations.", cancellations.size());
        }

        List<NaturalLanguageStringStructure> notes = vmDelivery.getVehicleActivityNotes();
        if (notes != null && !notes.isEmpty()) {
          //Handle notes
          LOG.info("TODO: Handle {} notes.", notes.size());
        }
      }

      // Make a snapshot after each message in anticipation of incoming requests
      // Purge data if necessary (and force new snapshot if anything was purged)
      // Make sure that the public (locking) getTimetableSnapshot function is not called.
      if (purgeExpiredData) {
        final boolean modified = purgeExpiredData();
        getTimetableSnapshot(modified);
      } else {
        getTimetableSnapshot(false);
      }
    } finally {
      // Always release lock
      bufferLock.unlock();
      if (keepLogging) {
        LOG.info("Reducing SIRI-VM logging until restart");
        keepLogging = false;
      }
    }
  }

  /**
   * Method to apply a trip update list to the most recent version of the timetable snapshot.
   *
   * @param transitModel       transitModel to update (needed for adding/changing stop patterns)
   * @param fullDataset true iff the list with updates represent all updates that are active right
   *                    now, i.e. all previous updates should be disregarded
   * @param updates     SIRI VehicleMonitoringDeliveries that should be applied atomically
   */
  public void applyEstimatedTimetable(
    final TransitModel transitModel,
    final String feedId,
    final boolean fullDataset,
    final List<EstimatedTimetableDeliveryStructure> updates
  ) {
    if (updates == null) {
      LOG.warn("updates is null");
      return;
    }

    // Acquire lock on buffer
    bufferLock.lock();

    try {
      if (fullDataset) {
        // Remove all updates from the buffer
        buffer.clear(feedId);
      }

      for (EstimatedTimetableDeliveryStructure etDelivery : updates) {
        List<EstimatedVersionFrameStructure> estimatedJourneyVersions = etDelivery.getEstimatedJourneyVersionFrames();
        if (estimatedJourneyVersions != null) {
          //Handle deliveries
          for (EstimatedVersionFrameStructure estimatedJourneyVersion : estimatedJourneyVersions) {
            List<EstimatedVehicleJourney> journeys = estimatedJourneyVersion.getEstimatedVehicleJourneies();
            LOG.debug("Handling {} EstimatedVehicleJourneys.", journeys.size());
            int handledCounter = 0;
            int skippedCounter = 0;
            int addedCounter = 0;
            int notMonitoredCounter = 0;
            for (EstimatedVehicleJourney journey : journeys) {
              if (journey.isExtraJourney() != null && journey.isExtraJourney()) {
                // Added trip
                try {
                  if (handleAddedTrip(transitModel, feedId, journey)) {
                    addedCounter++;
                  } else {
                    skippedCounter++;
                  }
                } catch (Throwable t) {
                  // Since this is work in progress - catch everything to continue processing updates
                  LOG.warn(
                    "Adding ExtraJourney with id='{}' failed, caused by '{}'.",
                    journey.getEstimatedVehicleJourneyCode(),
                    t.getMessage()
                  );
                  skippedCounter++;
                }
              } else {
                // Updated trip
                if (handleModifiedTrip(transitModel, feedId, journey)) {
                  handledCounter++;
                } else {
                  if (journey.isMonitored() != null && !journey.isMonitored()) {
                    notMonitoredCounter++;
                  } else {
                    skippedCounter++;
                  }
                }
              }
            }
            LOG.debug(
              "Processed EstimatedVehicleJourneys: updated {}, added {}, skipped {}, not monitored {}.",
              handledCounter,
              addedCounter,
              skippedCounter,
              notMonitoredCounter
            );
          }
        }
      }

      LOG.debug("message contains {} trip updates", updates.size());
      LOG.debug("end of update message");

      // Make a snapshot after each message in anticipation of incoming requests
      // Purge data if necessary (and force new snapshot if anything was purged)
      // Make sure that the public (locking) getTimetableSnapshot function is not called.
      if (purgeExpiredData) {
        final boolean modified = purgeExpiredData();
        getTimetableSnapshot(modified);
      } else {
        getTimetableSnapshot(false);
      }
    } finally {
      // Always release lock
      bufferLock.unlock();
    }
  }

  private TimetableSnapshot getTimetableSnapshot(final boolean force) {
    final long now = System.currentTimeMillis();
    if (force || now - lastSnapshotTime > maxSnapshotFrequency) {
      if (force || buffer.isDirty()) {
        LOG.debug("Committing {}", buffer);
        snapshot = buffer.commit(transitLayerUpdater, force);
      } else {
        LOG.debug("Buffer was unchanged, keeping old snapshot.");
      }
      lastSnapshotTime = System.currentTimeMillis();
    } else {
      LOG.debug("Snapshot frequency exceeded. Reusing snapshot {}", snapshot);
    }
    return snapshot;
  }

  private boolean handleModifiedTrip(
    TransitModel transitModel,
    VehicleActivityStructure activity,
    LocalDate serviceDate,
    String feedId
  ) {
    if (activity.getValidUntilTime().isBefore(ZonedDateTime.now())) {
      //Activity has expired
      return false;
    }

    if (
      activity.getMonitoredVehicleJourney() == null ||
      activity.getMonitoredVehicleJourney().getVehicleRef() == null ||
      activity.getMonitoredVehicleJourney().getLineRef() == null
    ) {
      //No vehicle reference or line reference
      return false;
    }

    Boolean isMonitored = activity.getMonitoredVehicleJourney().isMonitored();
    if (isMonitored != null && !isMonitored) {
      //Vehicle is reported as NOT monitored
      return false;
    }

    Set<Trip> trips = siriFuzzyTripMatcher.match(activity, feedId);

    if (trips == null || trips.isEmpty()) {
      if (keepLogging) {
        String lineRef =
          (
            activity.getMonitoredVehicleJourney().getLineRef() != null
              ? activity.getMonitoredVehicleJourney().getLineRef().getValue()
              : null
          );
        String vehicleRef =
          (
            activity.getMonitoredVehicleJourney().getVehicleRef() != null
              ? activity.getMonitoredVehicleJourney().getVehicleRef().getValue()
              : null
          );
        String tripId =
          (
            activity.getMonitoredVehicleJourney().getCourseOfJourneyRef() != null
              ? activity.getMonitoredVehicleJourney().getCourseOfJourneyRef().getValue()
              : null
          );
        LOG.debug(
          "No trip found for [isMonitored={}, lineRef={}, vehicleRef={}, tripId={}], skipping VehicleActivity.",
          isMonitored,
          lineRef,
          vehicleRef,
          tripId
        );
      }

      return false;
    }

    //Find the trip that best corresponds to MonitoredVehicleJourney
    Trip trip = getTripForJourney(trips, activity.getMonitoredVehicleJourney());

    if (trip == null) {
      return false;
    }

    final Set<TripPattern> patterns = getPatternsForTrip(
      trips,
      activity.getMonitoredVehicleJourney()
    );

    if (patterns == null) {
      return false;
    }
    boolean success = false;
    for (TripPattern pattern : patterns) {
      if (handleTripPatternUpdate(transitModel, pattern, activity, trip, serviceDate)) {
        success = true;
      }
    }

    if (!success) {
      LOG.info("Pattern not updated for trip " + trip.getId());
    }
    return success;
  }

  private boolean handleTripPatternUpdate(
    TransitModel transitModel,
    TripPattern pattern,
    VehicleActivityStructure activity,
    Trip trip,
    LocalDate serviceDate
  ) {
    // Apply update on the *scheduled* time table and set the updated trip times in the buffer
    Timetable currentTimetable = getCurrentTimetable(pattern, serviceDate);
    final TripTimes updatedTripTimes = createUpdatedTripTimes(
      currentTimetable,
      activity,
      trip.getId(),
      transitModel::getStopLocationById
    );
    if (updatedTripTimes == null) {
      return false;
    }

    // return success
    return buffer.update(pattern, updatedTripTimes, serviceDate);
  }

  /**
   * Get the latest timetable for TripPattern for a given service date.
   * <p>
   * Snapshot timetable is used as source if initialised, trip patterns scheduled timetable if not.
   */
  private Timetable getCurrentTimetable(TripPattern tripPattern, LocalDate serviceDate) {
    TimetableSnapshot timetableSnapshot = getTimetableSnapshot();
    if (timetableSnapshot != null) {
      return getTimetableSnapshot().resolve(tripPattern, serviceDate);
    }
    return tripPattern.getScheduledTimetable();
  }

  private boolean handleAddedTrip(
    TransitModel transitModel,
    String feedId,
    EstimatedVehicleJourney estimatedVehicleJourney
  ) {
    // Verifying values required in SIRI Profile

    // Added ServiceJourneyId
    String newServiceJourneyRef = estimatedVehicleJourney.getEstimatedVehicleJourneyCode();
    Objects.requireNonNull(newServiceJourneyRef, "EstimatedVehicleJourneyCode is required");

    // Replaced/duplicated ServiceJourneyId
    //        VehicleJourneyRef existingServiceJourneyRef = estimatedVehicleJourney.getVehicleJourneyRef();
    //        Objects.requireNonNull(existingServiceJourneyRef, "VehicleJourneyRef is required");

    // LineRef of added trip
    Objects.requireNonNull(estimatedVehicleJourney.getLineRef(), "LineRef is required");
    String lineRef = estimatedVehicleJourney.getLineRef().getValue();

    //OperatorRef of added trip
    Objects.requireNonNull(estimatedVehicleJourney.getOperatorRef(), "OperatorRef is required");
    String operatorRef = estimatedVehicleJourney.getOperatorRef().getValue();

    //Required in SIRI, but currently not in use by OTP
    //        Objects.requireNonNull(estimatedVehicleJourney.getRouteRef(), "RouteRef is required");
    //        String routeRef = estimatedVehicleJourney.getRouteRef().getValue();

    //        Objects.requireNonNull(estimatedVehicleJourney.getGroupOfLinesRef(), "GroupOfLinesRef is required");
    //        String groupOfLines = estimatedVehicleJourney.getGroupOfLinesRef().getValue();

    //        Objects.requireNonNull(estimatedVehicleJourney.getExternalLineRef(), "ExternalLineRef is required");
    String externalLineRef;
    if (estimatedVehicleJourney.getExternalLineRef() != null) {
      externalLineRef = estimatedVehicleJourney.getExternalLineRef().getValue();
    } else {
      externalLineRef = lineRef;
    }

    Operator operator = transitModel
      .getTransitModelIndex()
      .getOperatorForId()
      .get(new FeedScopedId(feedId, operatorRef));
    //        Objects.requireNonNull(operator, "Operator " + operatorRef + " is unknown");

    FeedScopedId tripId = new FeedScopedId(feedId, newServiceJourneyRef);

    Route replacedRoute = null;
    if (externalLineRef != null) {
      replacedRoute =
        transitModel
          .getTransitModelIndex()
          .getRouteForId(new FeedScopedId(feedId, externalLineRef));
    }

    FeedScopedId routeId = new FeedScopedId(feedId, lineRef);
    Route route = transitModel.getTransitModelIndex().getRouteForId(routeId);

    if (route == null) { // Route is unknown - create new
      var routeBuilder = Route.of(routeId);
      T2<TransitMode, String> transitMode = getTransitMode(
        estimatedVehicleJourney.getVehicleModes(),
        replacedRoute
      );
      routeBuilder.withMode(transitMode.first);
      routeBuilder.withNetexSubmode(transitMode.second);
      routeBuilder.withOperator(operator);

      // TODO - SIRI: Is there a better way to find authority/Agency?
      // Finding first Route with same Operator, and using same Authority
      Agency agency = transitModel
        .getTransitModelIndex()
        .getAllRoutes()
        .stream()
        .filter(route1 ->
          route1 != null && route1.getOperator() != null && route1.getOperator().equals(operator)
        )
        .findFirst()
        .get()
        .getAgency();
      routeBuilder.withAgency(agency);

      if (
        estimatedVehicleJourney.getPublishedLineNames() != null &&
        !estimatedVehicleJourney.getPublishedLineNames().isEmpty()
      ) {
        routeBuilder.withShortName(
          "" + estimatedVehicleJourney.getPublishedLineNames().get(0).getValue()
        );
      }
      route = routeBuilder.build();
      LOG.info("Adding route {} to transitModel.", routeId);
      transitModel.getTransitModelIndex().addRoutes(route);
    }

    var tripBuilder = Trip.of(tripId);
    tripBuilder.withRoute(route);

    LocalDate serviceDate = getServiceDateForEstimatedVehicleJourney(estimatedVehicleJourney);

    if (serviceDate == null) {
      return false;
    }

    FeedScopedId calServiceId = transitModel.getOrCreateServiceIdForDate(serviceDate);

    if (calServiceId == null) {
      return false;
    }

    tripBuilder.withServiceId(calServiceId);

    // Use destinationName as default headsign - if provided
    if (
      estimatedVehicleJourney.getDestinationNames() != null &&
      !estimatedVehicleJourney.getDestinationNames().isEmpty()
    ) {
      tripBuilder.withHeadsign(
        "" + estimatedVehicleJourney.getDestinationNames().get(0).getValue()
      );
    }

    tripBuilder.withOperator(operator);

    // TODO - SIRI: Populate these?
    tripBuilder.withShapeId(null); // Replacement-trip has different shape
    //        trip.setTripPrivateCode(null);
    //        trip.setTripPublicCode(null);
    tripBuilder.withGtfsBlockId(null);
    tripBuilder.withShortName(null);
    //        trip.setKeyValues(null);

    var trip = tripBuilder.build();

    List<StopLocation> addedStops = new ArrayList<>();
    List<StopTime> aimedStopTimes = new ArrayList<>();

    // TODO - SIRI: Handle RecordedCalls. Finding departureTime++ from first stop will fail when
    //              trip passes midnight, and the first stops are RecordedCalls.

    ZonedDateTime departureTime = null;

    List<EstimatedCall> estimatedCalls = estimatedVehicleJourney
      .getEstimatedCalls()
      .getEstimatedCalls();
    for (int i = 0; i < estimatedCalls.size(); i++) {
      EstimatedCall estimatedCall = estimatedCalls.get(i);

      var stop = getStopForStopId(feedId, estimatedCall.getStopPointRef().getValue());

      StopTime stopTime = new StopTime();
      stopTime.setStop(stop);
      stopTime.setStopSequence(i);
      stopTime.setTrip(trip);

      ZonedDateTime aimedArrivalTime = estimatedCall.getAimedArrivalTime();
      ZonedDateTime aimedDepartureTime = estimatedCall.getAimedDepartureTime();

      if (departureTime == null) {
        departureTime = aimedDepartureTime;
      }

      if (aimedArrivalTime != null) {
        stopTime.setArrivalTime(calculateSecondsSinceMidnight(departureTime, aimedArrivalTime));
      }
      if (aimedDepartureTime != null) {
        stopTime.setDepartureTime(calculateSecondsSinceMidnight(departureTime, aimedDepartureTime));
      }

      if (
        estimatedCall.getArrivalBoardingActivity() == ArrivalBoardingActivityEnumeration.ALIGHTING
      ) {
        stopTime.setDropOffType(SCHEDULED);
      } else {
        stopTime.setDropOffType(NONE);
      }

      if (
        estimatedCall.getDepartureBoardingActivity() ==
        DepartureBoardingActivityEnumeration.BOARDING
      ) {
        stopTime.setPickupType(SCHEDULED);
      } else {
        stopTime.setPickupType(NONE);
      }

      if (
        estimatedCall.getDestinationDisplaies() != null &&
        !estimatedCall.getDestinationDisplaies().isEmpty()
      ) {
        NaturalLanguageStringStructure destinationDisplay = estimatedCall
          .getDestinationDisplaies()
          .get(0);
        stopTime.setStopHeadsign(destinationDisplay.getValue());
      } else if (tripBuilder.getHeadsign() == null) {
        // Fallback to empty string
        stopTime.setStopHeadsign("");
      }

      if (i == 0) {
        // Fake arrival on first stop
        stopTime.setArrivalTime(stopTime.getDepartureTime());
      } else if (i == (estimatedCalls.size() - 1)) {
        // Fake departure from last stop
        stopTime.setDepartureTime(stopTime.getArrivalTime());
      }

      if (estimatedCall.isCancellation() != null && estimatedCall.isCancellation()) {
        stopTime.cancel();
      }

      addedStops.add(stop);
      aimedStopTimes.add(stopTime);
    }

    StopPattern stopPattern = new StopPattern(aimedStopTimes);

    var id = tripPatternIdGenerator.generateUniqueTripPatternId(trip);

    TripPattern pattern = TripPattern
      .of(id)
      .withRoute(tripBuilder.getRoute())
      .withStopPattern(stopPattern)
      .build();

    TripTimes tripTimes = new TripTimes(trip, aimedStopTimes, transitModel.getDeduplicator());

    boolean isJourneyPredictionInaccurate =
      (
        estimatedVehicleJourney.isPredictionInaccurate() != null &&
        estimatedVehicleJourney.isPredictionInaccurate()
      );

    // If added trip is updated with realtime - loop through and add delays
    for (int i = 0; i < estimatedCalls.size(); i++) {
      EstimatedCall estimatedCall = estimatedCalls.get(i);
      ZonedDateTime expectedArrival = estimatedCall.getExpectedArrivalTime();
      ZonedDateTime expectedDeparture = estimatedCall.getExpectedDepartureTime();

      int aimedArrivalTime = aimedStopTimes.get(i).getArrivalTime();
      int aimedDepartureTime = aimedStopTimes.get(i).getDepartureTime();

      if (expectedArrival != null) {
        int expectedArrivalTime = calculateSecondsSinceMidnight(departureTime, expectedArrival);
        tripTimes.updateArrivalDelay(i, expectedArrivalTime - aimedArrivalTime);
      }
      if (expectedDeparture != null) {
        int expectedDepartureTime = calculateSecondsSinceMidnight(departureTime, expectedDeparture);
        tripTimes.updateDepartureDelay(i, expectedDepartureTime - aimedDepartureTime);
      }

      boolean isCallPredictionInaccurate =
        estimatedCall.isPredictionInaccurate() != null && estimatedCall.isPredictionInaccurate();

      if (estimatedCall.isCancellation() != null && estimatedCall.isCancellation()) {
        tripTimes.setCancelled(i);
      } else if (isJourneyPredictionInaccurate | isCallPredictionInaccurate) {
        tripTimes.setPredictionInaccurate(i);
      }

      if (i == 0) {
        // Fake arrival on first stop
        tripTimes.updateArrivalTime(i, tripTimes.getDepartureTime(i));
      } else if (i == (estimatedCalls.size() - 1)) {
        // Fake departure from last stop
        tripTimes.updateDepartureTime(i, tripTimes.getArrivalTime(i));
      }
    }

    // Adding trip to index necessary to include values in graphql-queries
    // TODO - SIRI: should more data be added to index?
    transitModel.getTransitModelIndex().getTripForId().put(tripId, trip);
    transitModel.getTransitModelIndex().getPatternForTrip().put(trip, pattern);

    if (
      estimatedVehicleJourney.isCancellation() != null && estimatedVehicleJourney.isCancellation()
    ) {
      tripTimes.cancelTrip();
    } else {
      tripTimes.setRealTimeState(RealTimeState.ADDED);
    }

    tripTimes.setServiceCode(transitModel.getServiceCodes().get(calServiceId));

    pattern.add(tripTimes);

    Preconditions.checkState(
      tripTimes.timesIncreasing(),
      "Non-increasing triptimes for added trip"
    );

    return addTripToGraphAndBuffer(
      feedId,
      transitModel,
      trip,
      aimedStopTimes,
      addedStops,
      tripTimes,
      serviceDate,
      estimatedVehicleJourney
    );
  }

  /**
   * Resolves TransitMode from SIRI VehicleMode
   */
  private T2<TransitMode, String> getTransitMode(
    List<VehicleModesEnumeration> vehicleModes,
    Route replacedRoute
  ) {
    TransitMode transitMode = mapTransitMainMode(vehicleModes);

    String transitSubMode = resolveTransitSubMode(transitMode, replacedRoute);

    return new T2<>(transitMode, transitSubMode);
  }

  /**
   * Resolves submode based on added trips's mode and replacedRoute's mode
   *
   * @param transitMode   Mode of the added trip
   * @param replacedRoute Route that is being replaced
   * @return String-representation of submode
   */
  private String resolveTransitSubMode(TransitMode transitMode, Route replacedRoute) {
    if (replacedRoute != null) {
      TransitMode replacedRouteMode = replacedRoute.getMode();

      if (replacedRouteMode == TransitMode.RAIL) {
        if (transitMode.equals(TransitMode.RAIL)) {
          // Replacement-route is also RAIL
          return RailSubmodeEnumeration.REPLACEMENT_RAIL_SERVICE.value();
        } else if (transitMode.equals(TransitMode.BUS)) {
          // Replacement-route is BUS
          return BusSubmodeEnumeration.RAIL_REPLACEMENT_BUS.value();
        }
      }
    }
    return null;
  }

  private boolean handleModifiedTrip(
    TransitModel transitModel,
    String feedId,
    EstimatedVehicleJourney estimatedVehicleJourney
  ) {
    //Check if EstimatedVehicleJourney is reported as NOT monitored
    if (estimatedVehicleJourney.isMonitored() != null && !estimatedVehicleJourney.isMonitored()) {
      //Ignore the notMonitored-flag if the journey is NOT monitored because it has been cancelled
      if (
        estimatedVehicleJourney.isCancellation() != null &&
        !estimatedVehicleJourney.isCancellation()
      ) {
        return false;
      }
    }

    //Values used in logging
    String operatorRef =
      (
        estimatedVehicleJourney.getOperatorRef() != null
          ? estimatedVehicleJourney.getOperatorRef().getValue()
          : null
      );
    String vehicleModes = "" + estimatedVehicleJourney.getVehicleModes();
    String lineRef = estimatedVehicleJourney.getLineRef().getValue();
    String vehicleRef =
      (
        estimatedVehicleJourney.getVehicleRef() != null
          ? estimatedVehicleJourney.getVehicleRef().getValue()
          : null
      );

    LocalDate serviceDate = getServiceDateForEstimatedVehicleJourney(estimatedVehicleJourney);

    if (serviceDate == null) {
      return false;
    }

    Set<TripTimes> times = new HashSet<>();
    Set<TripPattern> patterns = new HashSet<>();

    Trip tripMatchedByServiceJourneyId = siriFuzzyTripMatcher.findTripByDatedVehicleJourneyRef(
      estimatedVehicleJourney,
      feedId
    );

    if (tripMatchedByServiceJourneyId != null) {
      /*
              Found exact match
             */
      TripPattern exactPattern = transitService.getPatternForTrip(tripMatchedByServiceJourneyId);

      if (exactPattern != null) {
        Timetable currentTimetable = getCurrentTimetable(exactPattern, serviceDate);
        TripTimes exactUpdatedTripTimes = createUpdatedTripTimes(
          currentTimetable,
          estimatedVehicleJourney,
          tripMatchedByServiceJourneyId.getId(),
          transitModel::getStopLocationById,
          timeZone,
          transitModel.getDeduplicator()
        );
        if (exactUpdatedTripTimes != null) {
          times.add(exactUpdatedTripTimes);
          patterns.add(exactPattern);
        } else {
          LOG.info(
            "Failed to update TripTimes for trip found by exact match {}",
            tripMatchedByServiceJourneyId.getId()
          );
          return false;
        }
      }
    } else {
      /*
                No exact match found - search for trips based on arrival-times/stop-patterns
             */
      Set<Trip> trips = siriFuzzyTripMatcher.match(estimatedVehicleJourney, feedId);

      if (trips == null || trips.isEmpty()) {
        LOG.debug(
          "No trips found for EstimatedVehicleJourney. [operator={}, vehicleModes={}, lineRef={}, vehicleRef={}]",
          operatorRef,
          vehicleModes,
          lineRef,
          vehicleRef
        );
        return false;
      }

      //Find the trips that best corresponds to EstimatedVehicleJourney
      Set<Trip> matchingTrips = getTripForJourney(trips, estimatedVehicleJourney);

      if (matchingTrips == null || matchingTrips.isEmpty()) {
        LOG.debug(
          "Found no matching trip for SIRI ET (serviceDate, departureTime). [operator={}, vehicleModes={}, lineRef={}, vehicleJourneyRef={}]",
          operatorRef,
          vehicleModes,
          lineRef,
          vehicleRef
        );
        return false;
      }

      for (Trip matchingTrip : matchingTrips) {
        TripPattern pattern = getPatternForTrip(matchingTrip, estimatedVehicleJourney);
        if (pattern != null) {
          Timetable currentTimetable = getCurrentTimetable(pattern, serviceDate);
          TripTimes updatedTripTimes = createUpdatedTripTimes(
            currentTimetable,
            estimatedVehicleJourney,
            matchingTrip.getId(),
            transitModel::getStopLocationById,
            timeZone,
            transitModel.getDeduplicator()
          );
          if (updatedTripTimes != null) {
            patterns.add(pattern);
            times.add(updatedTripTimes);
          }
        }
      }
    }

    if (patterns.isEmpty()) {
      LOG.debug(
        "Found no matching pattern for SIRI ET (firstStopId, lastStopId, numberOfStops). [operator={}, vehicleModes={}, lineRef={}, vehicleRef={}]",
        operatorRef,
        vehicleModes,
        lineRef,
        vehicleRef
      );
      return false;
    }

    if (times.isEmpty()) {
      return false;
    }

    boolean result = false;
    for (TripTimes tripTimes : times) {
      Trip trip = tripTimes.getTrip();
      for (TripPattern pattern : patterns) {
        if (tripTimes.getNumStops() == pattern.numberOfStops()) {
          if (!tripTimes.isCanceled()) {
            /*
                          UPDATED and MODIFIED tripTimes should be handled the same way to always allow latest realtime-update
                          to replace previous update regardless of realtimestate
                         */

            cancelScheduledTrip(trip, serviceDate);

            // Check whether trip id has been used for previously ADDED/MODIFIED trip message and remove
            // previously created trip
            removePreviousRealtimeUpdate(trip, serviceDate);

            // Calculate modified stop-pattern
            var modifiedStops = createModifiedStops(
              pattern,
              estimatedVehicleJourney,
              transitModel::getStopLocationById
            );
            List<StopTime> modifiedStopTimes = createModifiedStopTimes(
              pattern,
              tripTimes,
              estimatedVehicleJourney,
              transitModel::getStopLocationById
            );

            if (modifiedStops != null && modifiedStops.isEmpty()) {
              tripTimes.cancelTrip();
            } else {
              // Add new trip
              result =
                result |
                addTripToGraphAndBuffer(
                  feedId,
                  transitModel,
                  trip,
                  modifiedStopTimes,
                  modifiedStops,
                  tripTimes,
                  serviceDate,
                  estimatedVehicleJourney
                );
            }
          } else {
            result = result | buffer.update(pattern, tripTimes, serviceDate);
          }

          LOG.debug("Applied realtime data for trip {}", trip.getId().getId());
        } else {
          LOG.debug("Ignoring update since number of stops do not match");
        }
      }
    }

    return result;
  }

  private LocalDate getServiceDateForEstimatedVehicleJourney(
    EstimatedVehicleJourney estimatedVehicleJourney
  ) {
    ZonedDateTime date;
    if (
      estimatedVehicleJourney.getRecordedCalls() != null &&
      !estimatedVehicleJourney.getRecordedCalls().getRecordedCalls().isEmpty()
    ) {
      date =
        estimatedVehicleJourney
          .getRecordedCalls()
          .getRecordedCalls()
          .get(0)
          .getAimedDepartureTime();
    } else {
      EstimatedCall firstCall = estimatedVehicleJourney
        .getEstimatedCalls()
        .getEstimatedCalls()
        .get(0);
      date = firstCall.getAimedDepartureTime();
    }

    if (date == null) {
      return null;
    }

    return date.toLocalDate();
  }

  private int calculateSecondsSinceMidnight(ZonedDateTime dateTime) {
    return ServiceDateUtils.secondsSinceStartOfService(
      dateTime,
      dateTime,
      transitService.getTimeZone()
    );
  }

  private int calculateSecondsSinceMidnight(ZonedDateTime startOfService, ZonedDateTime dateTime) {
    return ServiceDateUtils.secondsSinceStartOfService(
      startOfService,
      dateTime,
      transitService.getTimeZone()
    );
  }

  /**
   * Add a (new) trip to the transitModel and the buffer
   *
   * @return true if successful
   */
  private boolean addTripToGraphAndBuffer(
    final String feedId,
    final TransitModel transitModel,
    final Trip trip,
    final List<StopTime> stopTimes,
    final List<StopLocation> stops,
    TripTimes updatedTripTimes,
    final LocalDate serviceDate,
    EstimatedVehicleJourney estimatedVehicleJourney
  ) {
    // Preconditions
    Objects.requireNonNull(stops);
    Preconditions.checkArgument(
      stopTimes.size() == stops.size(),
      "number of stop should match the number of stop time updates"
    );

    // Create StopPattern
    final StopPattern stopPattern = new StopPattern(stopTimes);

    // Get cached trip pattern or create one if it doesn't exist yet
    final TripPattern pattern = tripPatternCache.getOrCreateTripPattern(
      stopPattern,
      trip,
      transitModel,
      serviceDate
    );

    /*
     * Update pattern with triptimes so get correct dwell times and lower bound on running times.
     * New patterns only affects a single trip, previously added tripTimes is no longer valid, and is therefore removed
     */
    pattern.getScheduledTimetable().getTripTimes().clear();
    pattern.getScheduledTimetable().addTripTimes(updatedTripTimes);

    // Remove trip times to avoid real time trip times being visible for ignoreRealtimeInformation queries
    pattern.getScheduledTimetable().getTripTimes().clear();

    // Add to buffer as-is to include it in the 'realtimeAddedTripPattern'
    //TODO - Should this update be done twice?
    buffer.update(pattern, updatedTripTimes, serviceDate);

    // Add TripOnServiceDate to buffer if a dated service journey id is supplied in the SIRI message
    Supplier<Optional<String>> framedVehicleJourneySupplier = () ->
      Optional
        .ofNullable(estimatedVehicleJourney.getFramedVehicleJourneyRef())
        .map(FramedVehicleJourneyRefStructure::getDatedVehicleJourneyRef);
    Optional<String> dsjId = Optional
      .ofNullable(estimatedVehicleJourney.getDatedVehicleJourneyRef())
      .map(DatedVehicleJourneyRef::getValue)
      .or(framedVehicleJourneySupplier);

    if (dsjId.isPresent()) {
      FeedScopedId datedServiceJourneyId = new FeedScopedId(feedId, dsjId.get());
      var tripOnServiceDate = TripOnServiceDate
        .of(datedServiceJourneyId)
        .withTrip(trip)
        .withServiceDate(serviceDate)
        .build();

      buffer.addLastAddedTripOnServiceDate(
        trip,
        serviceDate,
        datedServiceJourneyId,
        tripOnServiceDate
      );
    }

    //TODO - SIRI: Add pattern to index?

    // Add new trip times to the buffer and return success
    return buffer.update(pattern, updatedTripTimes, serviceDate);
  }

  /**
   * Cancel scheduled trip in buffer given trip on service date
   *
   * @param serviceDate service date
   * @return true if scheduled trip was cancelled
   */
  private boolean cancelScheduledTrip(Trip trip, final LocalDate serviceDate) {
    boolean success = false;

    final TripPattern pattern = transitService.getPatternForTrip(trip);

    if (pattern != null) {
      // Cancel scheduled trip times for this trip in this pattern
      final Timetable timetable = pattern.getScheduledTimetable();
      final TripTimes tripTimes = timetable.getTripTimes(trip);

      if (tripTimes == null) {
        LOG.warn("Could not cancel scheduled trip {}", trip.getId());
      } else {
        final TripTimes newTripTimes = new TripTimes(tripTimes);
        newTripTimes.cancelTrip();
        buffer.update(pattern, newTripTimes, serviceDate);
        success = true;
      }
    }

    return success;
  }

  /**
   * Removes previous trip-update from buffer if there is an update with given trip on service date
   *
   * @param serviceDate service date
   * @return true if a previously added trip was removed
   */
  private boolean removePreviousRealtimeUpdate(final Trip trip, final LocalDate serviceDate) {
    boolean success = false;

    final TripPattern pattern = buffer.getRealtimeAddedTripPattern(trip.getId(), serviceDate);
    if (pattern != null) {
      /*
              Remove the previous realtime-added TripPattern from buffer.
              Only one version of the realtime-update should exist
             */
      buffer.removeLastAddedTripPattern(trip.getId(), serviceDate);
      buffer.removeRealtimeUpdatedTripTimes(pattern, trip.getId(), serviceDate);
      success = true;
    }

    return success;
  }

  private boolean purgeExpiredData() {
    final LocalDate today = LocalDate.now(timeZone);
    final LocalDate previously = today.minusDays(2); // Just to be safe...

    if (lastPurgeDate != null && lastPurgeDate.compareTo(previously) > 0) {
      return false;
    }

    LOG.debug("purging expired realtime data");

    lastPurgeDate = previously;

    return buffer.purgeExpiredData(previously);
  }

  private Set<TripPattern> getPatternsForTrip(
    Set<Trip> matches,
    VehicleActivityStructure.MonitoredVehicleJourney monitoredVehicleJourney
  ) {
    if (monitoredVehicleJourney.getOriginRef() == null) {
      return null;
    }

    ZonedDateTime date = monitoredVehicleJourney.getOriginAimedDepartureTime();
    if (date == null) {
      //If no date is set - assume Realtime-data is reported for 'today'.
      date = ZonedDateTime.now(transitService.getTimeZone());
    }
    LocalDate realTimeReportedServiceDate = date.toLocalDate();

    Set<TripPattern> patterns = new HashSet<>();
    for (Trip currentTrip : matches) {
      TripPattern tripPattern = transitService.getPatternForTrip(currentTrip);
      Set<LocalDate> serviceDates = transitService
        .getCalendarService()
        .getServiceDatesForServiceId(currentTrip.getServiceId());

      if (!serviceDates.contains(realTimeReportedServiceDate)) {
        // Current trip has no service on the date of the 'MonitoredVehicleJourney'
        continue;
      }

      var firstStop = tripPattern.getStop(0);
      var lastStop = tripPattern.lastStop();

      String siriOriginRef = monitoredVehicleJourney.getOriginRef().getValue();

      if (monitoredVehicleJourney.getDestinationRef() != null) {
        String siriDestinationRef = monitoredVehicleJourney.getDestinationRef().getValue();

        boolean firstStopIsMatch = firstStop.getId().getId().equals(siriOriginRef);
        boolean lastStopIsMatch = lastStop.getId().getId().equals(siriDestinationRef);

        if (!firstStopIsMatch && firstStop.isPartOfStation()) {
          var otherFirstStop = transitService.getStopForId(
            new FeedScopedId(firstStop.getId().getFeedId(), siriOriginRef)
          );
          firstStopIsMatch = firstStop.isPartOfSameStationAs(otherFirstStop);
        }

        if (!lastStopIsMatch && lastStop.isPartOfStation()) {
          var otherLastStop = transitService.getStopForId(
            new FeedScopedId(lastStop.getId().getFeedId(), siriDestinationRef)
          );
          lastStopIsMatch = lastStop.isPartOfSameStationAs(otherLastStop);
        }

        if (firstStopIsMatch & lastStopIsMatch) {
          // Origin and destination matches
          TripPattern realtimeAddedTripPattern = buffer.getRealtimeAddedTripPattern(
            currentTrip.getId(),
            realTimeReportedServiceDate
          );
          if (realtimeAddedTripPattern != null) {
            patterns.add(realtimeAddedTripPattern);
          } else {
            patterns.add(tripPattern);
          }
        }
      } else {
        //Match origin only - since destination is not defined
        if (firstStop.getId().getId().equals(siriOriginRef)) {
          tripPattern.getScheduledTimetable().getTripTimes().get(0).getDepartureTime(0); // TODO does this line do anything?
          patterns.add(tripPattern);
        }
      }
    }
    return patterns;
  }

  private TripPattern getPatternForTrip(Trip trip, EstimatedVehicleJourney journey) {
    Set<LocalDate> serviceDates = transitService
      .getCalendarService()
      .getServiceDatesForServiceId(trip.getServiceId());

    List<RecordedCall> recordedCalls =
      (
        journey.getRecordedCalls() != null
          ? journey.getRecordedCalls().getRecordedCalls()
          : new ArrayList<>()
      );
    List<EstimatedCall> estimatedCalls;
    if (journey.getEstimatedCalls() != null) {
      estimatedCalls = journey.getEstimatedCalls().getEstimatedCalls();
    } else {
      return null;
    }

    String journeyFirstStopId;
    String journeyLastStopId;
    LocalDate journeyDate;
    //Resolve first stop - check recordedCalls, then estimatedCalls
    if (recordedCalls != null && !recordedCalls.isEmpty()) {
      RecordedCall recordedCall = recordedCalls.get(0);
      journeyFirstStopId = recordedCall.getStopPointRef().getValue();
      journeyDate = recordedCall.getAimedDepartureTime().toLocalDate();
    } else if (estimatedCalls != null && !estimatedCalls.isEmpty()) {
      EstimatedCall estimatedCall = estimatedCalls.get(0);
      journeyFirstStopId = estimatedCall.getStopPointRef().getValue();
      journeyDate = estimatedCall.getAimedDepartureTime().toLocalDate();
    } else {
      return null;
    }

    //Resolve last stop - check estimatedCalls, then recordedCalls
    if (estimatedCalls != null && !estimatedCalls.isEmpty()) {
      EstimatedCall estimatedCall = estimatedCalls.get(estimatedCalls.size() - 1);
      journeyLastStopId = estimatedCall.getStopPointRef().getValue();
    } else if (recordedCalls != null && !recordedCalls.isEmpty()) {
      RecordedCall recordedCall = recordedCalls.get(recordedCalls.size() - 1);
      journeyLastStopId = recordedCall.getStopPointRef().getValue();
    } else {
      return null;
    }

    TripPattern realtimeAddedTripPattern = null;
    if (getTimetableSnapshot() != null) {
      realtimeAddedTripPattern =
        getTimetableSnapshot().getRealtimeAddedTripPattern(trip.getId(), journeyDate);
    }

    TripPattern tripPattern;
    if (realtimeAddedTripPattern != null) {
      tripPattern = realtimeAddedTripPattern;
    } else {
      tripPattern = transitService.getPatternForTrip(trip);
    }

    var firstStop = tripPattern.firstStop();
    var lastStop = tripPattern.lastStop();

    if (serviceDates.contains(journeyDate)) {
      boolean firstStopIsMatch = firstStop.getId().getId().equals(journeyFirstStopId);
      boolean lastStopIsMatch = lastStop.getId().getId().equals(journeyLastStopId);

      if (!firstStopIsMatch && firstStop.isPartOfStation()) {
        var otherFirstStop = transitService.getStopForId(
          new FeedScopedId(firstStop.getId().getFeedId(), journeyFirstStopId)
        );
        firstStopIsMatch = firstStop.isPartOfSameStationAs(otherFirstStop);
      }

      if (!lastStopIsMatch && lastStop.isPartOfStation()) {
        var otherLastStop = transitService.getStopForId(
          new FeedScopedId(lastStop.getId().getFeedId(), journeyLastStopId)
        );
        lastStopIsMatch = lastStop.isPartOfSameStationAs(otherLastStop);
      }

      if (firstStopIsMatch & lastStopIsMatch) {
        // Found matches
        return tripPattern;
      }

      return null;
    }

    return null;
  }

  /**
   * Finds the correct trip based on OTP-ServiceDate and SIRI-DepartureTime
   */
  private Trip getTripForJourney(
    Set<Trip> trips,
    VehicleActivityStructure.MonitoredVehicleJourney monitoredVehicleJourney
  ) {
    ZonedDateTime date = monitoredVehicleJourney.getOriginAimedDepartureTime();
    if (date == null) {
      //If no date is set - assume Realtime-data is reported for 'today'.
      date = ZonedDateTime.now();
    }
    LocalDate serviceDate = date.toLocalDate();

    List<Trip> results = new ArrayList<>();
    for (Trip trip : trips) {
      Set<LocalDate> serviceDatesForServiceId = transitService
        .getCalendarService()
        .getServiceDatesForServiceId(trip.getServiceId());

      for (LocalDate next : serviceDatesForServiceId) {
        if (next.equals(serviceDate)) {
          results.add(trip);
        }
      }
    }

    if (results.size() == 1) {
      return results.get(0);
    } else if (results.size() > 1) {
      // Multiple possible matches - check if lineRef/routeId matches
      if (
        monitoredVehicleJourney.getLineRef() != null &&
        monitoredVehicleJourney.getLineRef().getValue() != null
      ) {
        String lineRef = monitoredVehicleJourney.getLineRef().getValue();
        for (Trip trip : results) {
          if (lineRef.equals(trip.getRoute().getId().getId())) {
            // Return first trip where the lineRef matches routeId
            return trip;
          }
        }
      }

      // Line does not match any routeId - return first result.
      return results.get(0);
    }

    return null;
  }

  /**
   * Finds the correct trip based on OTP-ServiceDate and SIRI-DepartureTime
   */
  private Set<Trip> getTripForJourney(Set<Trip> trips, EstimatedVehicleJourney journey) {
    List<RecordedCall> recordedCalls =
      (
        journey.getRecordedCalls() != null
          ? journey.getRecordedCalls().getRecordedCalls()
          : new ArrayList<>()
      );
    List<EstimatedCall> estimatedCalls =
      (
        journey.getEstimatedCalls() != null ? journey.getEstimatedCalls().getEstimatedCalls() : null
      );

    ZonedDateTime date;
    int stopNumber = 1;
    String firstStopId;
    if (recordedCalls != null && !recordedCalls.isEmpty()) {
      RecordedCall recordedCall = recordedCalls.get(0);
      date = recordedCall.getAimedDepartureTime();
      firstStopId = recordedCall.getStopPointRef().getValue();
    } else if (estimatedCalls != null && !estimatedCalls.isEmpty()) {
      EstimatedCall estimatedCall = estimatedCalls.get(0);
      if (estimatedCall.getOrder() != null) {
        stopNumber = estimatedCall.getOrder().intValue();
      } else if (estimatedCall.getVisitNumber() != null) {
        stopNumber = estimatedCall.getVisitNumber().intValue();
      }
      firstStopId = estimatedCall.getStopPointRef().getValue();
      date = estimatedCall.getAimedDepartureTime();
    } else {
      return null;
    }

    if (date == null) {
      //If no date is set - assume Realtime-data is reported for 'today'.
      date = ZonedDateTime.now(transitService.getTimeZone());
    }
    LocalDate serviceDate = date.toLocalDate();

    int departureInSecondsSinceMidnight = calculateSecondsSinceMidnight(date);
    Set<Trip> result = new HashSet<>();
    for (Trip trip : trips) {
      Set<LocalDate> serviceDatesForServiceId = transitService
        .getCalendarService()
        .getServiceDatesForServiceId(trip.getServiceId());
      if (serviceDatesForServiceId.contains(serviceDate)) {
        TripPattern pattern = transitService.getPatternForTrip(trip);

        if (stopNumber < pattern.numberOfStops()) {
          boolean firstReportedStopIsFound = false;
          var stop = pattern.getStop(stopNumber - 1);
          if (firstStopId.equals(stop.getId().getId())) {
            firstReportedStopIsFound = true;
          } else {
            if (stop.isPartOfStation()) {
              var alternativeStop = transitService.getStopForId(
                new FeedScopedId(stop.getId().getFeedId(), firstStopId)
              );
              if (alternativeStop != null && stop.isPartOfSameStationAs(alternativeStop)) {
                firstReportedStopIsFound = true;
              }
            }
          }
          if (firstReportedStopIsFound) {
            for (TripTimes times : getCurrentTimetable(pattern, serviceDate).getTripTimes()) {
              if (
                times.getScheduledDepartureTime(stopNumber - 1) == departureInSecondsSinceMidnight
              ) {
                if (
                  transitService
                    .getCalendarService()
                    .getServiceDatesForServiceId(times.getTrip().getServiceId())
                    .contains(serviceDate)
                ) {
                  result.add(times.getTrip());
                }
              }
            }
          }
        }
      }
    }

    if (result.size() >= 1) {
      return result;
    } else {
      return null;
    }
  }

  /**
   * Retrieve stop given a feed id and stop id.
   *
   * @param feedId feed id for the stop id
   * @param stopId trip id without the agency
   * @return stop or null if stop doesn't exist
   */
  private StopLocation getStopForStopId(String feedId, String stopId) {
    return transitService.getStopForId(new FeedScopedId(feedId, stopId));
  }
}
