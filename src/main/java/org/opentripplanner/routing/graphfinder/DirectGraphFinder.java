package org.opentripplanner.routing.graphfinder;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.opentripplanner.common.geometry.SphericalDistanceLibrary;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.transit.model.basic.TransitMode;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.transit.model.site.Stop;
import org.opentripplanner.transit.service.StopModelIndex;
import org.opentripplanner.transit.service.TransitService;

/**
 * A Graph finder used in conjunction with a graph, which does not have a street network included.
 * Also usable if performance is more important or if the "as the crow flies" distance id required.
 */
public class DirectGraphFinder implements GraphFinder {

  private final StopModelIndex stopModelIndex;

  public DirectGraphFinder(Graph graph) {
    this.stopModelIndex = graph.getStopModel().getStopModelIndex();
  }

  /**
   * Return all stops within a certain radius of the given vertex, using straight-line distance
   * independent of streets. If the origin vertex is a StopVertex, the result will include it.
   */
  @Override
  public List<NearbyStop> findClosestStops(double lat, double lon, double radiusMeters) {
    List<NearbyStop> stopsFound = new ArrayList<>();
    Coordinate coordinate = new Coordinate(lon, lat);
    Envelope envelope = new Envelope(coordinate);
    envelope.expandBy(
      SphericalDistanceLibrary.metersToLonDegrees(radiusMeters, coordinate.y),
      SphericalDistanceLibrary.metersToDegrees(radiusMeters)
    );
    for (Stop it : stopModelIndex.queryStopSpatialIndex(envelope)) {
      double distance = Math.round(
        SphericalDistanceLibrary.distance(coordinate, it.getCoordinate().asJtsCoordinate())
      );
      if (distance < radiusMeters) {
        NearbyStop sd = new NearbyStop(it, distance, null, null);
        stopsFound.add(sd);
      }
    }

    stopsFound.sort(NearbyStop::compareTo);

    return stopsFound;
  }

  @Override
  public List<PlaceAtDistance> findClosestPlaces(
    double lat,
    double lon,
    double maxDistance,
    int maxResults,
    List<TransitMode> filterByModes,
    List<PlaceType> filterByPlaceTypes,
    List<FeedScopedId> filterByStops,
    List<FeedScopedId> filterByRoutes,
    List<String> filterByBikeRentalStations,
    TransitService transitService
  ) {
    throw new UnsupportedOperationException("Not implemented");
  }
}
