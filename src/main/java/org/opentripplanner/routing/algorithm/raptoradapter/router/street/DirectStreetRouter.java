package org.opentripplanner.routing.algorithm.raptoradapter.router.street;

import java.util.Collections;
import java.util.List;
import org.opentripplanner.common.geometry.SphericalDistanceLibrary;
import org.opentripplanner.model.plan.Itinerary;
import org.opentripplanner.routing.algorithm.mapping.GraphPathToItineraryMapper;
import org.opentripplanner.routing.algorithm.mapping.ItinerariesHelper;
import org.opentripplanner.routing.api.request.RoutingRequest;
import org.opentripplanner.routing.api.request.StreetMode;
import org.opentripplanner.routing.core.RoutingContext;
import org.opentripplanner.routing.core.TemporaryVerticesContainer;
import org.opentripplanner.routing.error.PathNotFoundException;
import org.opentripplanner.routing.impl.GraphPathFinder;
import org.opentripplanner.routing.spt.GraphPath;
import org.opentripplanner.standalone.api.OtpServerContext;

public class DirectStreetRouter {

  public static List<Itinerary> route(OtpServerContext serverContext, RoutingRequest request) {
    if (request.modes.directMode == StreetMode.NOT_SET) {
      return Collections.emptyList();
    }

    RoutingRequest directRequest = request.getStreetSearchRequest(request.modes.directMode);
    try (
      var temporaryVertices = new TemporaryVerticesContainer(serverContext.graph(), directRequest)
    ) {
      final RoutingContext routingContext = new RoutingContext(
        directRequest,
        serverContext.graph(),
        temporaryVertices
      );

      if (!straightLineDistanceIsWithinLimit(routingContext)) {
        return Collections.emptyList();
      }

      // we could also get a persistent router-scoped GraphPathFinder but there's no setup cost here
      GraphPathFinder gpFinder = new GraphPathFinder(
        serverContext.traverseVisitor(),
        serverContext.routerConfig().streetRoutingTimeout()
      );
      List<GraphPath> paths = gpFinder.graphPathFinderEntryPoint(routingContext);

      // Convert the internal GraphPaths to itineraries
      final GraphPathToItineraryMapper graphPathToItineraryMapper = new GraphPathToItineraryMapper(
        serverContext.transitService().getTimeZone(),
        serverContext.graph().streetNotesService,
        serverContext.graph().ellipsoidToGeoidDifference
      );
      List<Itinerary> response = graphPathToItineraryMapper.mapItineraries(paths);
      ItinerariesHelper.decorateItinerariesWithRequestData(response, request);
      return response;
    } catch (PathNotFoundException e) {
      return Collections.emptyList();
    }
  }

  private static boolean straightLineDistanceIsWithinLimit(RoutingContext routingContext) {
    // TODO This currently only calculates the distances between the first fromVertex
    //      and the first toVertex
    double distance = SphericalDistanceLibrary.distance(
      routingContext.fromVertices.iterator().next().getCoordinate(),
      routingContext.toVertices.iterator().next().getCoordinate()
    );
    return distance < calculateDistanceMaxLimit(routingContext.opt);
  }

  /**
   * Calculates the maximum distance in meters based on the maxDirectStreetDuration and the
   * fastest mode available. This assumes that it is not possible to exceed the speed defined in the
   * RoutingRequest.
   */
  private static double calculateDistanceMaxLimit(RoutingRequest request) {
    double distanceLimit;
    StreetMode mode = request.modes.directMode;
    double durationLimit = request.getMaxDirectStreetDuration(mode).toSeconds();

    if (mode.includesDriving()) {
      distanceLimit = durationLimit * request.carSpeed;
    } else if (mode.includesBiking()) {
      distanceLimit = durationLimit * request.bikeSpeed;
    } else if (mode.includesWalking()) {
      distanceLimit = durationLimit * request.walkSpeed;
    } else {
      throw new IllegalStateException("Could not set max limit for StreetMode");
    }

    return distanceLimit;
  }
}
