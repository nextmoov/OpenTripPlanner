package org.opentripplanner.graph_builder.module.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.opentripplanner.graph_builder.DataImportIssueStore.noopIssueStore;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentripplanner.datastore.api.DataSource;
import org.opentripplanner.datastore.api.FileType;
import org.opentripplanner.datastore.file.FileDataSource;
import org.opentripplanner.openstreetmap.OpenStreetMapProvider;
import org.opentripplanner.routing.algorithm.astar.AStarBuilder;
import org.opentripplanner.routing.api.request.RoutingRequest;
import org.opentripplanner.routing.core.RoutingContext;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.TraverseModeSet;
import org.opentripplanner.routing.core.intersection_model.ConstantIntersectionTraversalCostModel;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.spt.DominanceFunction;
import org.opentripplanner.routing.spt.GraphPath;
import org.opentripplanner.routing.spt.ShortestPathTree;
import org.opentripplanner.transit.model.framework.Deduplicator;
import org.opentripplanner.transit.service.StopModel;
import org.opentripplanner.transit.service.TransitModel;

public class TriangleInequalityTest {

  private static Graph graph;
  private static TransitModel transitModel;

  private Vertex start;
  private Vertex end;

  @BeforeAll
  public static void onlyOnce() {
    var deduplicator = new Deduplicator();
    var stopModel = new StopModel();
    graph = new Graph(stopModel, deduplicator);
    transitModel = new TransitModel(stopModel, deduplicator);

    File file = new File(
      URLDecoder.decode(
        TriangleInequalityTest.class.getResource("NYC_small.osm.pbf").getFile(),
        StandardCharsets.UTF_8
      )
    );
    DataSource source = new FileDataSource(file, FileType.OSM);
    OpenStreetMapProvider provider = new OpenStreetMapProvider(source, true);

    OpenStreetMapModule osmModule = new OpenStreetMapModule(
      List.of(provider),
      Set.of(),
      graph,
      transitModel.getTimeZone(),
      noopIssueStore()
    );
    osmModule.setDefaultWayPropertySetSource(new DefaultWayPropertySetSource());
    osmModule.buildGraph();
  }

  @BeforeEach
  public void before() {
    start = graph.getVertex("osm:node:1919595913");
    end = graph.getVertex("osm:node:42448554");
  }

  @Test
  public void testTriangleInequalityDefaultModes() {
    checkTriangleInequality();
  }

  @Test
  public void testTriangleInequalityWalkingOnly() {
    TraverseModeSet modes = new TraverseModeSet(TraverseMode.WALK);
    checkTriangleInequality(modes);
  }

  @Test
  public void testTriangleInequalityDrivingOnly() {
    TraverseModeSet modes = new TraverseModeSet(TraverseMode.CAR);
    checkTriangleInequality(modes);
  }

  @Test
  public void testTriangleInequalityWalkTransit() {
    TraverseModeSet modes = new TraverseModeSet(TraverseMode.WALK, TraverseMode.TRANSIT);
    checkTriangleInequality(modes);
  }

  @Test
  public void testTriangleInequalityWalkBike() {
    TraverseModeSet modes = new TraverseModeSet(TraverseMode.WALK, TraverseMode.BICYCLE);
    checkTriangleInequality(modes);
  }

  @Test
  public void testTriangleInequalityDefaultModesBasicSPT() {
    checkTriangleInequality(null);
  }

  @Test
  public void testTriangleInequalityWalkingOnlyBasicSPT() {
    TraverseModeSet modes = new TraverseModeSet(TraverseMode.WALK);
    checkTriangleInequality(modes);
  }

  @Test
  public void testTriangleInequalityDrivingOnlyBasicSPT() {
    TraverseModeSet modes = new TraverseModeSet(TraverseMode.CAR);
    checkTriangleInequality(modes);
  }

  @Test
  public void testTriangleInequalityWalkTransitBasicSPT() {
    TraverseModeSet modes = new TraverseModeSet(TraverseMode.WALK, TraverseMode.TRANSIT);
    checkTriangleInequality(modes);
  }

  @Test
  public void testTriangleInequalityWalkBikeBasicSPT() {
    TraverseModeSet modes = new TraverseModeSet(TraverseMode.WALK, TraverseMode.BICYCLE);
    checkTriangleInequality(modes);
  }

  @Test
  public void testTriangleInequalityDefaultModesMultiSPT() {
    checkTriangleInequality(null);
  }

  @Test
  public void testTriangleInequalityWalkingOnlyMultiSPT() {
    TraverseModeSet modes = new TraverseModeSet(TraverseMode.WALK);
    checkTriangleInequality(modes);
  }

  @Test
  public void testTriangleInequalityDrivingOnlyMultiSPT() {
    TraverseModeSet modes = new TraverseModeSet(TraverseMode.CAR);
    checkTriangleInequality(modes);
  }

  @Test
  public void testTriangleInequalityWalkTransitMultiSPT() {
    TraverseModeSet modes = new TraverseModeSet(TraverseMode.WALK, TraverseMode.TRANSIT);
    checkTriangleInequality(modes);
  }

  @Test
  public void testTriangleInequalityWalkBikeMultiSPT() {
    TraverseModeSet modes = new TraverseModeSet(TraverseMode.WALK, TraverseMode.BICYCLE);
    checkTriangleInequality(modes);
  }

  private GraphPath getPath(RoutingRequest options, Edge startBackEdge, Vertex u, Vertex v) {
    return AStarBuilder
      .oneToOne()
      .setOriginBackEdge(startBackEdge)
      .setContext(new RoutingContext(options, graph, u, v))
      .getShortestPathTree()
      .getPath(v);
  }

  private void checkTriangleInequality() {
    checkTriangleInequality(null);
  }

  private void checkTriangleInequality(TraverseModeSet traverseModes) {
    assertNotNull(start);
    assertNotNull(end);

    RoutingRequest prototypeOptions = new RoutingRequest();

    // All reluctance terms are 1.0 so that duration is monotonically increasing in weight.
    prototypeOptions.stairsReluctance = (1.0);
    prototypeOptions.setNonTransitReluctance(1.0);
    prototypeOptions.turnReluctance = (1.0);
    prototypeOptions.carSpeed = 1.0;
    prototypeOptions.walkSpeed = 1.0;
    prototypeOptions.bikeSpeed = 1.0;

    graph.setIntersectionTraversalCostModel(new ConstantIntersectionTraversalCostModel(10.0));

    if (traverseModes != null) {
      prototypeOptions.setStreetSubRequestModes(traverseModes);
    }

    ShortestPathTree tree = AStarBuilder
      .oneToOne()
      .setDominanceFunction(new DominanceFunction.EarliestArrival())
      .setContext(new RoutingContext(prototypeOptions, graph, start, end))
      .getShortestPathTree();

    GraphPath path = tree.getPath(end);
    assertNotNull(path);

    double startEndWeight = path.getWeight();
    int startEndDuration = path.getDuration();
    assertTrue(startEndWeight > 0);
    assertEquals(startEndWeight, startEndDuration, 1.0 * path.edges.size());

    // Try every vertex in the graph as an intermediate.
    boolean violated = false;
    for (Vertex intermediate : graph.getVertices()) {
      if (intermediate == start || intermediate == end) {
        continue;
      }

      GraphPath startIntermediatePath = getPath(prototypeOptions, null, start, intermediate);
      if (startIntermediatePath == null) {
        continue;
      }

      Edge back = startIntermediatePath.states.getLast().getBackEdge();
      GraphPath intermediateEndPath = getPath(prototypeOptions, back, intermediate, end);
      if (intermediateEndPath == null) {
        continue;
      }

      double startIntermediateWeight = startIntermediatePath.getWeight();
      int startIntermediateDuration = startIntermediatePath.getDuration();
      double intermediateEndWeight = intermediateEndPath.getWeight();
      int intermediateEndDuration = intermediateEndPath.getDuration();

      // TODO(flamholz): fix traversal so that there's no rounding at the second resolution.
      assertEquals(
        startIntermediateWeight,
        startIntermediateDuration,
        1.0 * startIntermediatePath.edges.size()
      );
      assertEquals(
        intermediateEndWeight,
        intermediateEndDuration,
        1.0 * intermediateEndPath.edges.size()
      );

      double diff = startIntermediateWeight + intermediateEndWeight - startEndWeight;
      if (diff < -0.01) {
        System.out.println("Triangle inequality violated - diff = " + diff);
        violated = true;
      }
      //assertTrue(startIntermediateDuration + intermediateEndDuration >=
      //        startEndDuration);
    }

    assertFalse(violated);
  }
}
