package org.opentripplanner.routing.algorithm.raptoradapter.transit.frequency;

import java.time.LocalDate;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.cost.DefaultTripSchedule;
import org.opentripplanner.transit.model.network.TripPattern;
import org.opentripplanner.transit.model.timetable.TripTimes;
import org.opentripplanner.transit.raptor.api.transit.RaptorTripPattern;

/**
 * Represents a result of a {@link TripFrequencyAlightSearch}, with materialized {@link TripTimes}
 */
final class FrequencyAlightEvent<T extends DefaultTripSchedule>
  extends FrequencyBoardOrAlightEvent<T> {

  public FrequencyAlightEvent(
    RaptorTripPattern raptorTripPattern,
    TripTimes tripTimes,
    TripPattern pattern,
    int stopPositionInPattern,
    int departureTime,
    int headway,
    int offset,
    LocalDate serviceDate
  ) {
    super(
      raptorTripPattern,
      tripTimes,
      pattern,
      stopPositionInPattern,
      departureTime,
      offset,
      headway,
      serviceDate
    );
  }

  @Override
  public int arrival(int stopPosInPattern) {
    return tripTimes.getArrivalTime(stopPosInPattern) + offset;
  }

  // Remove headway here to report an early enough departure time for the raptor search
  @Override
  public int departure(int stopPosInPattern) {
    return tripTimes.getDepartureTime(stopPosInPattern) - headway + offset;
  }
}
