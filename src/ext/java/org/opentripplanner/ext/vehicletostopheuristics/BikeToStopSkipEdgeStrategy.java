package org.opentripplanner.ext.vehicletostopheuristics;

import java.util.Collection;
import java.util.function.Function;
import org.opentripplanner.routing.algorithm.astar.strategies.SkipEdgeStrategy;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.vertextype.TransitStopVertex;
import org.opentripplanner.transit.model.network.BikeAccess;
import org.opentripplanner.transit.model.site.Stop;
import org.opentripplanner.transit.model.timetable.Trip;

/**
 * When wanting to take a bike onto transit we want to improve the performance by limiting the
 * number of accesses to those stops which actually have trips where you can take the bike on. Once
 * we have reached enough of trips we skip all further edges.
 */
public class BikeToStopSkipEdgeStrategy implements SkipEdgeStrategy {

  private static final int LIMIT = 100;
  private static final double MAX_FACTOR = 1.2;

  private final Function<Stop, Collection<Trip>> getTripsForStop;

  int numberOfBikeableTripsReached = 0;
  double distanceLimit = Double.MAX_VALUE;

  public BikeToStopSkipEdgeStrategy(Function<Stop, Collection<Trip>> getTripsForStop) {
    this.getTripsForStop = getTripsForStop;
  }

  public static boolean bikeAccessForTrip(Trip trip) {
    if (trip.getBikesAllowed() != BikeAccess.UNKNOWN) {
      return trip.getBikesAllowed() == BikeAccess.ALLOWED;
    }

    return trip.getRoute().getBikesAllowed() == BikeAccess.ALLOWED;
  }

  @Override
  public boolean shouldSkipEdge(State current, Edge edge) {
    if (
      current.getVertex() instanceof TransitStopVertex stopVertex &&
      distanceLimit == Double.MAX_VALUE
    ) {
      numberOfBikeableTripsReached +=
        getTripsForStop
          .apply(stopVertex.getStop())
          .stream()
          .filter(BikeToStopSkipEdgeStrategy::bikeAccessForTrip)
          .count();
      if (numberOfBikeableTripsReached >= LIMIT) {
        distanceLimit = current.getWalkDistance() * MAX_FACTOR;
      }
    }
    return current.getWalkDistance() > distanceLimit;
  }
}
