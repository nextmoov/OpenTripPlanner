package org.opentripplanner.model.impl;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.opentripplanner.ext.flex.trip.FlexTrip;
import org.opentripplanner.gtfs.mapping.StaySeatedNotAllowed;
import org.opentripplanner.model.FeedInfo;
import org.opentripplanner.model.Frequency;
import org.opentripplanner.model.OtpTransitService;
import org.opentripplanner.model.ShapePoint;
import org.opentripplanner.model.TripStopTimes;
import org.opentripplanner.model.calendar.CalendarServiceData;
import org.opentripplanner.model.calendar.ServiceCalendar;
import org.opentripplanner.model.calendar.ServiceCalendarDate;
import org.opentripplanner.model.calendar.ServiceDateInterval;
import org.opentripplanner.model.calendar.impl.CalendarServiceDataFactoryImpl;
import org.opentripplanner.model.transfer.ConstrainedTransfer;
import org.opentripplanner.model.transfer.TransferPoint;
import org.opentripplanner.transit.model.basic.Notice;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.transit.model.framework.TransitEntity;
import org.opentripplanner.transit.model.network.GroupOfRoutes;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.network.StopPattern;
import org.opentripplanner.transit.model.network.TripPattern;
import org.opentripplanner.transit.model.organization.Agency;
import org.opentripplanner.transit.model.organization.Branding;
import org.opentripplanner.transit.model.organization.Operator;
import org.opentripplanner.transit.model.site.BoardingArea;
import org.opentripplanner.transit.model.site.Entrance;
import org.opentripplanner.transit.model.site.FareZone;
import org.opentripplanner.transit.model.site.FlexLocationGroup;
import org.opentripplanner.transit.model.site.FlexStopLocation;
import org.opentripplanner.transit.model.site.GroupOfStations;
import org.opentripplanner.transit.model.site.MultiModalStation;
import org.opentripplanner.transit.model.site.Pathway;
import org.opentripplanner.transit.model.site.PathwayNode;
import org.opentripplanner.transit.model.site.Station;
import org.opentripplanner.transit.model.site.Stop;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.model.timetable.TripOnServiceDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is responsible for building a {@link OtpTransitService}. The instance returned by the
 * {@link #build()} method is read only, and this class provide a mutable collections to construct a
 * {@link OtpTransitService} instance.
 */
public class OtpTransitServiceBuilder {

  private static final Logger LOG = LoggerFactory.getLogger(OtpTransitServiceBuilder.class);

  private final EntityById<Agency> agenciesById = new EntityById<>();

  private final List<ServiceCalendarDate> calendarDates = new ArrayList<>();

  private final List<ServiceCalendar> calendars = new ArrayList<>();

  private final List<FeedInfo> feedInfos = new ArrayList<>();

  private final List<Frequency> frequencies = new ArrayList<>();

  private final EntityById<GroupOfStations> groupsOfStationsById = new EntityById<>();

  private final EntityById<MultiModalStation> multiModalStationsById = new EntityById<>();

  private final Multimap<TransitEntity, Notice> noticeAssignments = ArrayListMultimap.create();

  private final EntityById<Operator> operatorsById = new EntityById<>();

  private final List<Pathway> pathways = new ArrayList<>();

  private final EntityById<Route> routesById = new EntityById<>();

  private final Multimap<FeedScopedId, ShapePoint> shapePoints = ArrayListMultimap.create();

  private final EntityById<Station> stationsById = new EntityById<>();

  private final EntityById<Stop> stopsById = new EntityById<>();

  private final EntityById<Entrance> entrancesById = new EntityById<>();

  private final EntityById<PathwayNode> pathwayNodesById = new EntityById<>();

  private final EntityById<BoardingArea> boardingAreasById = new EntityById<>();

  private final EntityById<FlexStopLocation> locationsById = new EntityById<>();

  private final EntityById<FlexLocationGroup> locationGroupsById = new EntityById<>();

  private final TripStopTimes stopTimesByTrip = new TripStopTimes();

  private final EntityById<FareZone> fareZonesById = new EntityById<>();

  private final List<ConstrainedTransfer> transfers = new ArrayList<>();

  private final List<StaySeatedNotAllowed> staySeatedNotAllowed = new ArrayList<>();

  private final EntityById<Trip> tripsById = new EntityById<>();

  private final Multimap<StopPattern, TripPattern> tripPatterns = ArrayListMultimap.create();

  private final EntityById<FlexTrip<?, ?>> flexTripsById = new EntityById<>();

  private final EntityById<Branding> brandingsById = new EntityById<>();

  private final Multimap<FeedScopedId, GroupOfRoutes> groupsOfRoutesByRouteId = ArrayListMultimap.create();

  private final EntityById<TripOnServiceDate> tripOnServiceDates = new EntityById<>();

  private final EntityById<GroupOfRoutes> groupOfRouteById = new EntityById<>();

  public OtpTransitServiceBuilder() {}

  /* Accessors */

  public EntityById<Agency> getAgenciesById() {
    return agenciesById;
  }

  public List<ServiceCalendarDate> getCalendarDates() {
    return calendarDates;
  }

  public List<ServiceCalendar> getCalendars() {
    return calendars;
  }

  public List<FeedInfo> getFeedInfos() {
    return feedInfos;
  }

  public List<Frequency> getFrequencies() {
    return frequencies;
  }

  public EntityById<GroupOfStations> getGroupsOfStationsById() {
    return groupsOfStationsById;
  }

  public EntityById<MultiModalStation> getMultiModalStationsById() {
    return multiModalStationsById;
  }

  /**
   * get multimap of Notices by the TransitEntity id (Multiple types; hence the Serializable).
   * Entities that might have Notices are Routes, Trips, Stops and StopTimes.
   */
  public Multimap<TransitEntity, Notice> getNoticeAssignments() {
    return noticeAssignments;
  }

  public EntityById<Operator> getOperatorsById() {
    return operatorsById;
  }

  public List<Pathway> getPathways() {
    return pathways;
  }

  public EntityById<Route> getRoutes() {
    return routesById;
  }

  public Multimap<FeedScopedId, ShapePoint> getShapePoints() {
    return shapePoints;
  }

  public EntityById<Station> getStations() {
    return stationsById;
  }

  public EntityById<Stop> getStops() {
    return stopsById;
  }

  public EntityById<Entrance> getEntrances() {
    return entrancesById;
  }

  public EntityById<PathwayNode> getPathwayNodes() {
    return pathwayNodesById;
  }

  public EntityById<BoardingArea> getBoardingAreas() {
    return boardingAreasById;
  }

  public EntityById<FlexStopLocation> getLocations() {
    return locationsById;
  }

  public EntityById<FlexLocationGroup> getLocationGroups() {
    return locationGroupsById;
  }

  public TripStopTimes getStopTimesSortedByTrip() {
    return stopTimesByTrip;
  }

  public EntityById<FareZone> getFareZonesById() {
    return fareZonesById;
  }

  public List<ConstrainedTransfer> getTransfers() {
    return transfers;
  }

  public List<StaySeatedNotAllowed> getStaySeatedNotAllowed() {
    return staySeatedNotAllowed;
  }

  public EntityById<Trip> getTripsById() {
    return tripsById;
  }

  public Multimap<StopPattern, TripPattern> getTripPatterns() {
    return tripPatterns;
  }

  public EntityById<FlexTrip<?, ?>> getFlexTripsById() {
    return flexTripsById;
  }

  public EntityById<Branding> getBrandingsById() {
    return brandingsById;
  }

  public Multimap<FeedScopedId, GroupOfRoutes> getGroupsOfRoutesByRouteId() {
    return groupsOfRoutesByRouteId;
  }

  public EntityById<GroupOfRoutes> getGroupOfRouteById() {
    return groupOfRouteById;
  }

  public EntityById<TripOnServiceDate> getTripOnServiceDates() {
    return tripOnServiceDates;
  }

  public CalendarServiceData buildCalendarServiceData() {
    return CalendarServiceDataFactoryImpl.createCalendarServiceData(
      getCalendarDates(),
      getCalendars()
    );
  }

  public OtpTransitService build() {
    return new OtpTransitServiceImpl(this);
  }

  /**
   * Limit the transit service to a time period removing calendar dates and services outside the
   * period. If a service is start before and/or ends after the period then the service is modified
   * to match the period.
   */
  public void limitServiceDays(ServiceDateInterval periodLimit) {
    if (periodLimit.isUnbounded()) {
      LOG.info("Limiting transit service is skipped, the period is unbounded.");
      return;
    }

    LOG.warn("Limiting transit service days to time period: {}", periodLimit);

    int orgSize = calendarDates.size();
    calendarDates.removeIf(c -> !periodLimit.include(c.getDate()));
    logRemove("ServiceCalendarDate", orgSize, calendarDates.size(), "Outside time period.");

    List<ServiceCalendar> keepCal = new ArrayList<>();
    for (ServiceCalendar calendar : calendars) {
      if (calendar.getPeriod().overlap(periodLimit)) {
        calendar.setPeriod(calendar.getPeriod().intersection(periodLimit));
        keepCal.add(calendar);
      }
    }

    orgSize = calendars.size();
    if (orgSize != keepCal.size()) {
      calendars.clear();
      calendars.addAll(keepCal);
      logRemove("ServiceCalendar", orgSize, calendars.size(), "Outside time period.");
    }
    removeEntitiesWithInvalidReferences();
    LOG.info("Limiting transit service days to time period complete.");
  }

  /**
   * Find all serviceIds in both CalendarServices and CalendarServiceDates.
   */
  Set<FeedScopedId> findAllServiceIds() {
    Set<FeedScopedId> serviceIds = new HashSet<>();
    for (ServiceCalendar calendar : getCalendars()) {
      serviceIds.add(calendar.getServiceId());
    }
    for (ServiceCalendarDate date : getCalendarDates()) {
      serviceIds.add(date.getServiceId());
    }
    return serviceIds;
  }

  private static void logRemove(String type, int orgSize, int newSize, String reason) {
    if (orgSize == newSize) {
      return;
    }
    LOG.info("{} of {} {}(s) removed. Reason: {}", orgSize - newSize, orgSize, type, reason);
  }

  /**
   * Check all relations and remove entities which reference none existing entries. This may happen
   * as a result of inconsistent data or by deliberate removal of elements in the builder.
   */
  private void removeEntitiesWithInvalidReferences() {
    removeTripsWithNoneExistingServiceIds();
    removeStopTimesForNoneExistingTrips();
    fixOrRemovePatternsWhichReferenceNoneExistingTrips();
    removeTransfersForNoneExistingTrips();
    removeTripOnServiceDateForNonExistingTrip();
  }

  /** Remove all trips which reference none existing service ids */
  private void removeTripsWithNoneExistingServiceIds() {
    Set<FeedScopedId> serviceIds = findAllServiceIds();
    int orgSize = tripsById.size();
    tripsById.removeIf(t -> !serviceIds.contains(t.getServiceId()));
    logRemove("Trip", orgSize, tripsById.size(), "Trip service id does not exist.");
  }

  /** Remove all stopTimes which reference none existing trips */
  private void removeStopTimesForNoneExistingTrips() {
    int orgSize = stopTimesByTrip.size();
    stopTimesByTrip.removeIf(t -> !tripsById.containsKey(t.getId()));
    logRemove("StopTime", orgSize, stopTimesByTrip.size(), "StopTime trip does not exist.");
  }

  /** Remove none existing trips from patterns and then remove empty patterns */
  private void fixOrRemovePatternsWhichReferenceNoneExistingTrips() {
    int orgSize = tripPatterns.size();
    List<Map.Entry<StopPattern, TripPattern>> removePatterns = new ArrayList<>();

    for (Map.Entry<StopPattern, TripPattern> e : tripPatterns.entries()) {
      TripPattern ptn = e.getValue();
      ptn.removeTrips(t -> !tripsById.containsKey(t.getId()));
      if (ptn.scheduledTripsAsStream().findAny().isEmpty()) {
        removePatterns.add(e);
      }
    }
    for (Map.Entry<StopPattern, TripPattern> it : removePatterns) {
      tripPatterns.remove(it.getKey(), it.getValue());
    }
    logRemove("TripPattern", orgSize, tripPatterns.size(), "No trips for pattern exist.");
  }

  /** Remove all transfers which reference none existing trips */
  private void removeTransfersForNoneExistingTrips() {
    int orgSize = transfers.size();
    transfers.removeIf(this::transferTripReferencesDoNotExist);
    logRemove("Trip", orgSize, transfers.size(), "Transfer to/from trip does not exist.");
  }

  /**
   * Remove TripOnServiceDates if there are no trips using them
   */
  private void removeTripOnServiceDateForNonExistingTrip() {
    int orgSize = tripOnServiceDates.size();
    for (TripOnServiceDate tripOnServiceDate : tripOnServiceDates.values()) {
      if (!tripsById.containsKey(tripOnServiceDate.getTrip().getId())) {
        logRemove(
          "TripOnServiceDate",
          orgSize,
          tripOnServiceDates.size(),
          "Trip for TripOnServiceDate does not exist."
        );
      }
    }
  }

  /** Return {@code true} if the from/to trip reference is none null, but do not exist. */
  private boolean transferTripReferencesDoNotExist(ConstrainedTransfer t) {
    return (
      transferPointTripReferenceDoesNotExist(t.getFrom()) ||
      transferPointTripReferenceDoesNotExist(t.getTo())
    );
  }

  /**
   * Return {@code true} if the the point is a trip-transfer-point and the trip reference is
   * missing.
   */
  private boolean transferPointTripReferenceDoesNotExist(TransferPoint point) {
    if (!point.isTripTransferPoint()) {
      return false;
    }
    var trip = point.asTripTransferPoint().getTrip();
    return !tripsById.containsKey(trip.getId());
  }
}
