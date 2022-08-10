package org.opentripplanner.model.plan;

import java.time.Duration;
import java.util.List;

/**
 * Calculate derived itinerary fields
 */
class ItinerariesCalculateLegTotals {

  int totalDurationSeconds = 0;
  int transitTimeSeconds = 0;
  int nTransitLegs = 0;
  int nonTransitTimeSeconds = 0;
  double nonTransitDistanceMeters = 0.0;
  int waitingTimeSeconds;
  boolean walkOnly = true;
  boolean streetOnly = true;
  double totalElevationGained = 0.0;
  double totalElevationLost = 0.0;

  public ItinerariesCalculateLegTotals(List<Leg> legs) {
    if (legs.isEmpty()) {
      return;
    }
    calculate(legs);
  }

  int transfers() {
    return nTransitLegs == 0 ? 0 : nTransitLegs - 1;
  }

  private void calculate(List<Leg> legs) {
    totalDurationSeconds =
      (int) Duration
        .between(legs.get(0).getStartTime(), legs.get(legs.size() - 1).getEndTime())
        .toSeconds();

    for (Leg leg : legs) {
      long dt = leg.getDuration();

      if (leg.isTransitLeg()) {
        transitTimeSeconds += dt;
        if (!leg.isInterlinedWithPreviousLeg()) {
          ++nTransitLegs;
        }
      } else if (leg.isStreetLeg()) {
        nonTransitTimeSeconds += dt;
        nonTransitDistanceMeters += leg.getDistanceMeters();
      }
      if (!leg.isWalkingLeg()) {
        walkOnly = false;
      }
      if (!leg.isStreetLeg()) {
        this.streetOnly = false;
      }
      if (leg.getElevationGained() != null && leg.getElevationLost() != null) {
        this.totalElevationGained += leg.getElevationGained();
        this.totalElevationLost += leg.getElevationLost();
      }
    }
    this.waitingTimeSeconds = totalDurationSeconds - (transitTimeSeconds + nonTransitTimeSeconds);
  }
}
