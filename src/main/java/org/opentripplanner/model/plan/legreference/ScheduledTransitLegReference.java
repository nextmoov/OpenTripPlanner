package org.opentripplanner.model.plan.legreference;

import java.time.LocalDate;
import java.time.ZoneId;
import org.opentripplanner.model.Timetable;
import org.opentripplanner.model.plan.ScheduledTransitLeg;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.transit.model.network.TripPattern;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.model.timetable.TripTimes;
import org.opentripplanner.transit.service.TransitService;
import org.opentripplanner.util.time.ServiceDateUtils;

/**
 * A reference which can be used to rebuild an exact copy of a {@link ScheduledTransitLeg} using the
 * {@Link RoutingService}
 */
public record ScheduledTransitLegReference(
  FeedScopedId tripId,
  LocalDate serviceDate,
  int fromStopPositionInPattern,
  int toStopPositionInPattern
)
  implements LegReference {
  @Override
  public ScheduledTransitLeg getLeg(TransitService transitService) {
    Trip trip = transitService.getTripForId(tripId);

    if (trip == null) {
      return null;
    }

    // Check if pattern is changed by real-time updater
    TripPattern tripPattern = transitService.getRealtimeAddedTripPattern(tripId, serviceDate);

    // Otherwise use scheduled pattern
    if (tripPattern == null) {
      tripPattern = transitService.getPatternForTrip(trip);
    }

    // no matching pattern found anywhere
    if (tripPattern == null) {
      return null;
    }

    Timetable timetable = transitService.getTimetableForTripPattern(tripPattern, serviceDate);

    TripTimes tripTimes = timetable.getTripTimes(trip);

    // TODO: What should we have here
    ZoneId timeZone = transitService.getTimeZone();

    int boardingTime = tripTimes.getDepartureTime(fromStopPositionInPattern);
    int alightingTime = tripTimes.getArrivalTime(toStopPositionInPattern);

    return new ScheduledTransitLeg(
      tripTimes,
      tripPattern,
      fromStopPositionInPattern,
      toStopPositionInPattern,
      ServiceDateUtils.toZonedDateTime(serviceDate, timeZone, boardingTime),
      ServiceDateUtils.toZonedDateTime(serviceDate, timeZone, alightingTime),
      serviceDate,
      timeZone,
      null,
      null,
      0, // TODO: What should we have here
      null
    );
  }
}
