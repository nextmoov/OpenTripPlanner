package org.opentripplanner.netex.mapping;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.xml.bind.JAXBElement;
import org.opentripplanner.graph_builder.DataImportIssueStore;
import org.opentripplanner.model.impl.EntityById;
import org.opentripplanner.netex.index.api.ReadOnlyHierarchicalMap;
import org.opentripplanner.netex.index.api.ReadOnlyHierarchicalMapById;
import org.opentripplanner.netex.mapping.support.FeedScopedIdFactory;
import org.opentripplanner.transit.model.framework.Deduplicator;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.transit.model.network.StopPattern;
import org.opentripplanner.transit.model.network.TripPattern;
import org.opentripplanner.transit.model.network.TripPatternBuilder;
import org.opentripplanner.transit.model.organization.Operator;
import org.opentripplanner.transit.model.site.FlexLocationGroup;
import org.opentripplanner.transit.model.site.FlexStopLocation;
import org.opentripplanner.transit.model.site.Stop;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.model.timetable.TripOnServiceDate;
import org.opentripplanner.transit.model.timetable.TripTimes;
import org.rutebanken.netex.model.DatedServiceJourney;
import org.rutebanken.netex.model.DatedServiceJourneyRefStructure;
import org.rutebanken.netex.model.DestinationDisplay;
import org.rutebanken.netex.model.FlexibleLine;
import org.rutebanken.netex.model.JourneyPattern;
import org.rutebanken.netex.model.OperatingDay;
import org.rutebanken.netex.model.Route;
import org.rutebanken.netex.model.ServiceJourney;
import org.rutebanken.netex.model.ServiceLink;

/**
 * Maps NeTEx JourneyPattern to OTP TripPattern. All ServiceJourneys in the same JourneyPattern
 * contain the same sequence of stops. This means that they can all use the same StopPattern. Each
 * ServiceJourney contains TimeTabledPassingTimes that are mapped to StopTimes.
 * <p>
 * Headsigns in NeTEx are only specified once and then valid for each subsequent
 * TimeTabledPassingTime until a new headsign is specified. This is accounted for in the mapper.
 * <p>
 * THIS CLASS IS NOT THREADSAFE! This mapper store its intermediate results as part of its state.
 */
class TripPatternMapper {

  public static final String HEADSIGN_EMPTY = "";

  private final DataImportIssueStore issueStore;

  private final FeedScopedIdFactory idFactory;

  private final EntityById<org.opentripplanner.transit.model.network.Route> otpRouteById;

  private final ReadOnlyHierarchicalMap<String, Route> routeById;

  private final Multimap<String, ServiceJourney> serviceJourniesByPatternId = ArrayListMultimap.create();

  private final ReadOnlyHierarchicalMapById<OperatingDay> operatingDayById;

  private final Multimap<String, DatedServiceJourney> datedServiceJourneysBySJId;

  private final ReadOnlyHierarchicalMapById<DatedServiceJourney> datedServiceJourneyById;

  private final ReadOnlyHierarchicalMap<String, ServiceJourney> serviceJourneyById;

  private final TripMapper tripMapper;

  private final StopTimesMapper stopTimesMapper;

  private final ServiceLinkMapper serviceLinkMapper;

  private final Deduplicator deduplicator;

  private TripPatternMapperResult result;

  TripPatternMapper(
    DataImportIssueStore issueStore,
    FeedScopedIdFactory idFactory,
    EntityById<Operator> operatorById,
    EntityById<Stop> stopsById,
    EntityById<FlexStopLocation> flexStopLocationsById,
    EntityById<FlexLocationGroup> flexLocationGroupsById,
    EntityById<org.opentripplanner.transit.model.network.Route> otpRouteById,
    ReadOnlyHierarchicalMap<String, Route> routeById,
    ReadOnlyHierarchicalMap<String, JourneyPattern> journeyPatternById,
    ReadOnlyHierarchicalMap<String, String> quayIdByStopPointRef,
    ReadOnlyHierarchicalMap<String, String> flexibleStopPlaceIdByStopPointRef,
    ReadOnlyHierarchicalMap<String, DestinationDisplay> destinationDisplayById,
    ReadOnlyHierarchicalMap<String, ServiceJourney> serviceJourneyById,
    ReadOnlyHierarchicalMapById<ServiceLink> serviceLinkById,
    ReadOnlyHierarchicalMapById<FlexibleLine> flexibleLinesById,
    ReadOnlyHierarchicalMapById<OperatingDay> operatingDayById,
    ReadOnlyHierarchicalMapById<DatedServiceJourney> datedServiceJourneyById,
    Multimap<String, DatedServiceJourney> datedServiceJourneysBySJId,
    Map<String, FeedScopedId> serviceIds,
    Deduplicator deduplicator,
    double maxStopToShapeSnapDistance
  ) {
    this.issueStore = issueStore;
    this.idFactory = idFactory;
    this.routeById = routeById;
    this.otpRouteById = otpRouteById;
    this.operatingDayById = operatingDayById;
    this.datedServiceJourneysBySJId = datedServiceJourneysBySJId;
    this.tripMapper =
      new TripMapper(
        idFactory,
        issueStore,
        operatorById,
        otpRouteById,
        routeById,
        journeyPatternById,
        serviceIds
      );
    this.stopTimesMapper =
      new StopTimesMapper(
        issueStore,
        idFactory,
        stopsById,
        flexStopLocationsById,
        flexLocationGroupsById,
        destinationDisplayById,
        quayIdByStopPointRef,
        flexibleStopPlaceIdByStopPointRef,
        flexibleLinesById,
        routeById
      );
    this.serviceLinkMapper =
      new ServiceLinkMapper(
        idFactory,
        serviceLinkById,
        quayIdByStopPointRef,
        stopsById,
        issueStore,
        maxStopToShapeSnapDistance
      );
    this.deduplicator = deduplicator;

    this.datedServiceJourneyById = datedServiceJourneyById;
    this.serviceJourneyById = serviceJourneyById;
    // Index service journey by pattern id
    for (ServiceJourney sj : serviceJourneyById.localValues()) {
      this.serviceJourniesByPatternId.put(sj.getJourneyPatternRef().getValue().getRef(), sj);
    }
  }

  TripPatternMapperResult mapTripPattern(JourneyPattern journeyPattern) {
    // Make sure the result is clean, by creating a new object.
    result = new TripPatternMapperResult();
    Collection<ServiceJourney> serviceJourneys = serviceJourniesByPatternId.get(
      journeyPattern.getId()
    );

    if (serviceJourneys.isEmpty()) {
      issueStore.add(
        "ServiceJourneyPatternIsEmpty",
        "ServiceJourneyPattern %s does not contain any serviceJourneys.",
        journeyPattern.getId()
      );
      return result;
    }

    List<Trip> trips = new ArrayList<>();

    for (ServiceJourney serviceJourney : serviceJourneys) {
      Trip trip = mapTrip(journeyPattern, serviceJourney);

      // Unable to map ServiceJourney, problem logged by the mapper above
      if (trip == null) {
        continue;
      }

      // Add the dated service journey to the model for this trip [if it exists]
      mapDatedServiceJourney(journeyPattern, serviceJourney, trip);

      StopTimesMapperResult stopTimes = stopTimesMapper.mapToStopTimes(
        journeyPattern,
        trip,
        serviceJourney.getPassingTimes().getTimetabledPassingTime(),
        serviceJourney
      );

      // Unable to map StopTimes, problem logged by the mapper above
      if (stopTimes == null) {
        continue;
      }

      result.scheduledStopPointsIndex.putAll(
        serviceJourney.getId(),
        stopTimes.scheduledStopPointIds
      );
      result.tripStopTimes.put(trip, stopTimes.stopTimes);
      result.stopTimeByNetexId.putAll(stopTimes.stopTimeByNetexId);

      trips.add(trip);
    }

    // No trips successfully mapped
    if (trips.isEmpty()) {
      return result;
    }

    // TODO OTP2 Trips containing FlexStopLocations are not added to StopPatterns until support
    //           for this is added.
    if (
      result.tripStopTimes
        .get(trips.get(0))
        .stream()
        .anyMatch(t ->
          t.getStop() instanceof FlexStopLocation || t.getStop() instanceof FlexLocationGroup
        )
    ) {
      return result;
    }

    // Create StopPattern from any trip (since they are part of the same JourneyPattern)
    StopPattern stopPattern = deduplicator.deduplicateObject(
      StopPattern.class,
      new StopPattern(result.tripStopTimes.get(trips.get(0)))
    );

    TripPatternBuilder tripPatternBuilder = TripPattern
      .of(idFactory.createId(journeyPattern.getId()))
      .withRoute(lookupRoute(journeyPattern))
      .withStopPattern(stopPattern)
      .withName(journeyPattern.getName() == null ? "" : journeyPattern.getName().getValue())
      .withHopGeometries(
        serviceLinkMapper.getGeometriesByJourneyPattern(journeyPattern, stopPattern)
      );

    TripPattern tripPattern = tripPatternBuilder.build();
    createTripTimes(trips, tripPattern);

    result.tripPatterns.put(stopPattern, tripPattern);

    return result;
  }

  private void mapDatedServiceJourney(
    JourneyPattern journeyPattern,
    ServiceJourney serviceJourney,
    Trip trip
  ) {
    if (datedServiceJourneysBySJId.containsKey(serviceJourney.getId())) {
      for (DatedServiceJourney datedServiceJourney : datedServiceJourneysBySJId.get(
        serviceJourney.getId()
      )) {
        result.tripOnServiceDates.add(
          mapDatedServiceJourney(journeyPattern, trip, datedServiceJourney)
        );
      }
    }
  }

  private TripOnServiceDate mapDatedServiceJourney(
    JourneyPattern journeyPattern,
    Trip trip,
    DatedServiceJourney datedServiceJourney
  ) {
    var opDay = operatingDayById.lookup(datedServiceJourney.getOperatingDayRef().getRef());

    if (opDay == null) {
      return null;
    }

    var serviceDate = opDay.getCalendarDate().toLocalDate();
    var id = idFactory.createId(datedServiceJourney.getId());
    var alteration = TripServiceAlterationMapper.mapAlteration(
      datedServiceJourney.getServiceAlteration()
    );

    var replacementFor = datedServiceJourney
      .getJourneyRef()
      .stream()
      .map(JAXBElement::getValue)
      .filter(DatedServiceJourneyRefStructure.class::isInstance)
      .map(DatedServiceJourneyRefStructure.class::cast)
      .map(DatedServiceJourneyRefStructure::getRef)
      .map(datedServiceJourneyById::lookup)
      .filter(Objects::nonNull)
      .map(replacement -> {
        if (datedServiceJourney.equals(replacement)) {
          issueStore.add(
            "InvalidDatedServiceJourneyRef",
            "DatedServiceJourney %s has reference to itself, skipping",
            datedServiceJourney.getId()
          );
          return null;
        }
        String serviceJourneyRef = replacement.getJourneyRef().get(0).getValue().getRef();
        ServiceJourney serviceJourney = serviceJourneyById.lookup(serviceJourneyRef);
        if (serviceJourney == null) {
          issueStore.add(
            "InvalidDatedServiceJourneyRef",
            "DatedServiceJourney %s has reference to %s, which is not found, skipping",
            datedServiceJourney.getId(),
            serviceJourneyRef
          );
          return null;
        }
        return mapDatedServiceJourney(
          journeyPattern,
          mapTrip(journeyPattern, serviceJourney),
          replacement
        );
      })
      .filter(Objects::nonNull)
      .toList();

    return TripOnServiceDate
      .of(id)
      .withTrip(trip)
      .withServiceDate(serviceDate)
      .withTripAlteration(alteration)
      .withReplacementFor(replacementFor)
      .build();
  }

  private org.opentripplanner.transit.model.network.Route lookupRoute(
    JourneyPattern journeyPattern
  ) {
    Route route = routeById.lookup(journeyPattern.getRouteRef().getRef());
    return otpRouteById.get(idFactory.createId(route.getLineRef().getValue().getRef()));
  }

  private void createTripTimes(List<Trip> trips, TripPattern tripPattern) {
    for (Trip trip : trips) {
      if (result.tripStopTimes.get(trip).size() == 0) {
        issueStore.add(
          "TripWithoutTripTimes",
          "Trip %s does not contain any trip times.",
          trip.getId()
        );
      } else {
        TripTimes tripTimes = new TripTimes(trip, result.tripStopTimes.get(trip), deduplicator);
        tripPattern.add(tripTimes);
      }
    }
  }

  private Trip mapTrip(JourneyPattern journeyPattern, ServiceJourney serviceJourney) {
    return tripMapper.mapServiceJourney(
      serviceJourney,
      () -> findTripHeadsign(journeyPattern, serviceJourney)
    );
  }

  private String findTripHeadsign(JourneyPattern journeyPattern, ServiceJourney serviceJourney) {
    var times = serviceJourney.getPassingTimes().getTimetabledPassingTime();
    if (times == null || times.isEmpty()) {
      return HEADSIGN_EMPTY;
    }
    String headsign = stopTimesMapper.findTripHeadsign(journeyPattern, times.get(0));
    return headsign == null ? HEADSIGN_EMPTY : headsign;
  }
}
