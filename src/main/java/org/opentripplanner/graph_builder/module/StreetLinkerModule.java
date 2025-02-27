package org.opentripplanner.graph_builder.module;

import static org.opentripplanner.graph_builder.DataImportIssueStore.noopIssueStore;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.opentripplanner.graph_builder.DataImportIssueStore;
import org.opentripplanner.graph_builder.issues.ParkAndRideEntranceRemoved;
import org.opentripplanner.graph_builder.linking.LinkingDirection;
import org.opentripplanner.graph_builder.model.GraphBuilderModule;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.TraverseModeSet;
import org.opentripplanner.routing.edgetype.StreetTransitEntranceLink;
import org.opentripplanner.routing.edgetype.StreetTransitStopLink;
import org.opentripplanner.routing.edgetype.StreetVehicleParkingLink;
import org.opentripplanner.routing.edgetype.VehicleParkingEdge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.vehicle_parking.VehicleParkingHelper;
import org.opentripplanner.routing.vehicle_parking.VehicleParkingService;
import org.opentripplanner.routing.vertextype.TransitEntranceVertex;
import org.opentripplanner.routing.vertextype.TransitStopVertex;
import org.opentripplanner.routing.vertextype.VehicleParkingEntranceVertex;
import org.opentripplanner.transit.model.site.FlexLocationGroup;
import org.opentripplanner.transit.model.site.Stop;
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.service.TransitModel;
import org.opentripplanner.util.OTPFeature;
import org.opentripplanner.util.logging.ProgressTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link GraphBuilderModule} plugin that links various
 * objects in the graph to the street network. It should be run after both the transit network and
 * street network are loaded. It links four things: transit stops, transit entrances, bike rental
 * stations, and bike parks. Therefore it should be run even when there's no GTFS data present to
 * make bike rental services and bike parks usable.
 */
public class StreetLinkerModule implements GraphBuilderModule {

  private static final Logger LOG = LoggerFactory.getLogger(StreetLinkerModule.class);
  private final Graph graph;
  private final TransitModel transitModel;
  private final DataImportIssueStore issueStore;
  private Boolean addExtraEdgesToAreas;

  public StreetLinkerModule(
    Graph graph,
    TransitModel transitModel,
    DataImportIssueStore issueStore,
    boolean addExtraEdgesToAreas
  ) {
    this.graph = graph;
    this.transitModel = transitModel;
    this.issueStore = issueStore;
    this.addExtraEdgesToAreas = addExtraEdgesToAreas;
  }

  /** For test only */
  public static void linkStreetsForTestOnly(Graph graph, TransitModel model) {
    new StreetLinkerModule(graph, model, noopIssueStore(), false).buildGraph();
  }

  @Override
  public void buildGraph() {
    transitModel.index();
    graph.index();
    graph.getLinker().setAddExtraEdgesToAreas(this.addExtraEdgesToAreas);

    if (graph.hasStreets) {
      linkTransitStops(graph, transitModel);
      linkTransitEntrances(graph);
      linkVehicleParks(graph, issueStore);
    }

    // Calculates convex hull of a graph which is shown in routerInfo API point
    graph.calculateConvexHull();
  }

  @Override
  public void checkInputs() {
    //no inputs
  }

  public void linkTransitStops(Graph graph, TransitModel transitModel) {
    List<TransitStopVertex> vertices = graph.getVerticesOfType(TransitStopVertex.class);
    var progress = ProgressTracker.track("Linking transit stops to graph", 5000, vertices.size());
    LOG.info(progress.startMessage());

    Set<StopLocation> stopLocationsUsedForFlexTrips = Set.of();

    if (OTPFeature.FlexRouting.isOn()) {
      stopLocationsUsedForFlexTrips =
        transitModel
          .getAllFlexTrips()
          .stream()
          .flatMap(t -> t.getStops().stream())
          .collect(Collectors.toSet());

      stopLocationsUsedForFlexTrips.addAll(
        stopLocationsUsedForFlexTrips
          .stream()
          .filter(s -> s instanceof FlexLocationGroup)
          .flatMap(g ->
            ((FlexLocationGroup) g).getLocations().stream().filter(e -> e instanceof Stop)
          )
          .toList()
      );
    }

    for (TransitStopVertex tStop : vertices) {
      // Stops with pathways do not need to be connected to the street network, since there are explicit entraces defined for that
      if (tStop.hasPathways()) {
        continue;
      }
      // check if stop is already linked, to allow multiple linking cycles
      if (tStop.getDegreeOut() + tStop.getDegreeIn() > 0) {
        continue;
      }
      TraverseModeSet modes = new TraverseModeSet(TraverseMode.WALK);

      if (OTPFeature.FlexRouting.isOn()) {
        // If regular stops are used for flex trips, they also need to be connected to car routable
        // street edges.
        if (stopLocationsUsedForFlexTrips.contains(tStop.getStop())) {
          modes = new TraverseModeSet(TraverseMode.WALK, TraverseMode.CAR);
        }
      }

      graph
        .getLinker()
        .linkVertexPermanently(
          tStop,
          modes,
          LinkingDirection.BOTH_WAYS,
          (vertex, streetVertex) ->
            List.of(
              new StreetTransitStopLink((TransitStopVertex) vertex, streetVertex),
              new StreetTransitStopLink(streetVertex, (TransitStopVertex) vertex)
            )
        );
      //noinspection Convert2MethodRef
      progress.step(m -> LOG.info(m));
    }
    LOG.info(progress.completeMessage());
  }

  private static void linkVehicleParkingWithLinker(
    Graph graph,
    VehicleParkingEntranceVertex vehicleParkingVertex
  ) {
    if (vehicleParkingVertex.isWalkAccessible()) {
      graph
        .getLinker()
        .linkVertexPermanently(
          vehicleParkingVertex,
          new TraverseModeSet(TraverseMode.WALK),
          LinkingDirection.BOTH_WAYS,
          (vertex, streetVertex) ->
            List.of(
              new StreetVehicleParkingLink((VehicleParkingEntranceVertex) vertex, streetVertex),
              new StreetVehicleParkingLink(streetVertex, (VehicleParkingEntranceVertex) vertex)
            )
        );
    }

    if (vehicleParkingVertex.isCarAccessible()) {
      graph
        .getLinker()
        .linkVertexPermanently(
          vehicleParkingVertex,
          new TraverseModeSet(TraverseMode.CAR),
          LinkingDirection.BOTH_WAYS,
          (vertex, streetVertex) ->
            List.of(
              new StreetVehicleParkingLink((VehicleParkingEntranceVertex) vertex, streetVertex),
              new StreetVehicleParkingLink(streetVertex, (VehicleParkingEntranceVertex) vertex)
            )
        );
    }
  }

  private void linkTransitEntrances(Graph graph) {
    LOG.info("Linking transit entrances to graph...");
    for (TransitEntranceVertex tEntrance : graph.getVerticesOfType(TransitEntranceVertex.class)) {
      graph
        .getLinker()
        .linkVertexPermanently(
          tEntrance,
          new TraverseModeSet(TraverseMode.WALK),
          LinkingDirection.BOTH_WAYS,
          (vertex, streetVertex) ->
            List.of(
              new StreetTransitEntranceLink((TransitEntranceVertex) vertex, streetVertex),
              new StreetTransitEntranceLink(streetVertex, (TransitEntranceVertex) vertex)
            )
        );
    }
  }

  private void linkVehicleParks(Graph graph, DataImportIssueStore issueStore) {
    if (graph.hasLinkedBikeParks) {
      LOG.info("Already linked vehicle parks to graph...");
      return;
    }
    LOG.info("Linking vehicle parks to graph...");
    for (VehicleParkingEntranceVertex vehicleParkingEntranceVertex : graph.getVerticesOfType(
      VehicleParkingEntranceVertex.class
    )) {
      if (vehicleParkingEntranceHasLinks(vehicleParkingEntranceVertex)) {
        continue;
      }

      if (vehicleParkingEntranceVertex.getParkingEntrance().getVertex() == null) {
        linkVehicleParkingWithLinker(graph, vehicleParkingEntranceVertex);
        continue;
      }

      if (graph.containsVertex(vehicleParkingEntranceVertex.getParkingEntrance().getVertex())) {
        VehicleParkingHelper.linkToGraph(vehicleParkingEntranceVertex);
        continue;
      }

      issueStore.add(
        new ParkAndRideEntranceRemoved(vehicleParkingEntranceVertex.getParkingEntrance())
      );
      removeVehicleParkingEntranceVertexFromGraph(vehicleParkingEntranceVertex, graph);
    }
    graph.hasLinkedBikeParks = true;
  }

  private boolean vehicleParkingEntranceHasLinks(
    VehicleParkingEntranceVertex vehicleParkingEntranceVertex
  ) {
    return !(
      vehicleParkingEntranceVertex
        .getIncoming()
        .stream()
        .allMatch(VehicleParkingEdge.class::isInstance) &&
      vehicleParkingEntranceVertex
        .getOutgoing()
        .stream()
        .allMatch(VehicleParkingEdge.class::isInstance)
    );
  }

  private void removeVehicleParkingEntranceVertexFromGraph(
    VehicleParkingEntranceVertex vehicleParkingEntranceVertex,
    Graph graph
  ) {
    var vehicleParkingEdge = vehicleParkingEntranceVertex
      .getOutgoing()
      .stream()
      .filter(VehicleParkingEdge.class::isInstance)
      .map(VehicleParkingEdge.class::cast)
      .findFirst()
      .orElseThrow(() ->
        new IllegalStateException(
          "VehicleParkingEdge missing from vertex: " + vehicleParkingEntranceVertex
        )
      );

    var entrance = vehicleParkingEntranceVertex.getParkingEntrance();

    var vehicleParking = vehicleParkingEdge.getVehicleParking();

    boolean removeVehicleParking =
      vehicleParking.getEntrances().size() == 1 &&
      vehicleParking.getEntrances().get(0).equals(entrance);

    vehicleParkingEntranceVertex.getIncoming().forEach(graph::removeEdge);
    vehicleParkingEntranceVertex.getOutgoing().forEach(graph::removeEdge);
    graph.remove(vehicleParkingEntranceVertex);

    if (removeVehicleParking) {
      var vehicleParkingService = graph.getService(VehicleParkingService.class);
      vehicleParkingService.removeVehicleParking(vehicleParking);
    } else {
      vehicleParking.getEntrances().remove(entrance);
    }
  }
}
