package org.opentripplanner.graph_builder.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.opentripplanner.graph_builder.DataImportIssueStore.noopIssueStore;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.opentripplanner.ConstantsForTests;
import org.opentripplanner.graph_builder.module.osm.OpenStreetMapModule;
import org.opentripplanner.openstreetmap.OpenStreetMapProvider;
import org.opentripplanner.routing.edgetype.AreaEdge;
import org.opentripplanner.routing.edgetype.BoardingLocationToStopLink;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.vertextype.OsmBoardingLocationVertex;
import org.opentripplanner.routing.vertextype.TransitStopVertexBuilder;
import org.opentripplanner.test.support.VariableSource;
import org.opentripplanner.transit.model._data.TransitModelForTest;
import org.opentripplanner.transit.model.basic.NonLocalizedString;
import org.opentripplanner.transit.model.basic.TransitMode;
import org.opentripplanner.transit.model.framework.Deduplicator;
import org.opentripplanner.transit.model.site.Stop;
import org.opentripplanner.transit.service.StopModel;
import org.opentripplanner.transit.service.TransitModel;

/**
 * We test that the platform area at Herrenberg station (https://www.openstreetmap.org/way/27558650)
 * is correctly linked to the stop even though it is not the closest edge to the stop.
 */
class OsmBoardingLocationsModuleTest {

  File file = new File(ConstantsForTests.HERRENBERG_OSM);
  Stop platform = TransitModelForTest
    .stop("de:08115:4512:4:101")
    .withCoordinate(48.59328, 8.86128)
    .build();
  Stop busStop = TransitModelForTest.stopForTest("de:08115:4512:5:C", 48.59434, 8.86452);
  Stop floatingBusStop = TransitModelForTest.stopForTest("floating-bus-stop", 48.59417, 8.86464);

  static Stream<Arguments> testCases = Stream.of(
    Arguments.of(
      true,
      Set.of(
        "osm:node:302563833",
        "osm:node:3223067049",
        "osm:node:302563836",
        "osm:node:3223067680",
        "osm:node:302563834",
        "osm:node:768590748",
        "osm:node:302563839"
      )
    ),
    Arguments.of(false, Set.of("osm:node:768590748"))
  );

  @ParameterizedTest(
    name = "add boarding locations and link them to platform edges when skipVisibility={0}"
  )
  @VariableSource("testCases")
  void addAndLinkBoardingLocations(boolean skipVisibility, Set<String> linkedVertices) {
    var deduplicator = new Deduplicator();
    var stopModel = new StopModel();
    var graph = new Graph(stopModel, deduplicator);
    var transitModel = new TransitModel(stopModel, deduplicator);
    var extra = new HashMap<Class<?>, Object>();

    var provider = new OpenStreetMapProvider(file, false);
    var floatingBusVertex = new TransitStopVertexBuilder()
      .withGraph(graph)
      .withStop(floatingBusStop)
      .withTransitModel(transitModel)
      .withModes(Set.of(TransitMode.BUS))
      .build();
    var floatingBoardingLocation = new OsmBoardingLocationVertex(
      graph,
      "floating-bus-stop",
      floatingBusVertex.getX(),
      floatingBusVertex.getY(),
      new NonLocalizedString("bus stop not connected to street network"),
      Set.of(floatingBusVertex.getStop().getId().getId())
    );
    var osmModule = new OpenStreetMapModule(
      List.of(provider),
      Set.of("ref", "ref:IFOPT"),
      graph,
      transitModel.getTimeZone(),
      noopIssueStore()
    );
    osmModule.skipVisibility = skipVisibility;

    osmModule.buildGraph();

    var platformVertex = new TransitStopVertexBuilder()
      .withGraph(graph)
      .withStop(platform)
      .withTransitModel(transitModel)
      .withModes(Set.of(TransitMode.RAIL))
      .build();
    var busVertex = new TransitStopVertexBuilder()
      .withGraph(graph)
      .withStop(busStop)
      .withTransitModel(transitModel)
      .withModes(Set.of(TransitMode.BUS))
      .build();

    assertEquals(0, busVertex.getIncoming().size());
    assertEquals(0, busVertex.getOutgoing().size());

    assertEquals(0, platformVertex.getIncoming().size());
    assertEquals(0, platformVertex.getOutgoing().size());

    new OsmBoardingLocationsModule(graph).buildGraph();

    var boardingLocations = graph.getVerticesOfType(OsmBoardingLocationVertex.class);
    assertEquals(5, boardingLocations.size()); // 3 nodes connected to the street network, plus one "floating" and one area centroid created by the module

    assertEquals(1, platformVertex.getIncoming().size());
    assertEquals(1, platformVertex.getOutgoing().size());

    assertEquals(1, busVertex.getIncoming().size());
    assertEquals(1, busVertex.getOutgoing().size());

    var platformCentroids = boardingLocations
      .stream()
      .filter(l -> l.references.contains(platform.getId().getId()))
      .toList();

    var busBoardingLocation = boardingLocations
      .stream()
      .filter(b -> b.references.contains(busStop.getId().getId()))
      .findFirst()
      .get();

    assertConnections(
      busBoardingLocation,
      Set.of(BoardingLocationToStopLink.class, StreetEdge.class)
    );

    assertConnections(
      floatingBoardingLocation,
      Set.of(BoardingLocationToStopLink.class, StreetEdge.class)
    );

    assertEquals(1, platformCentroids.size());

    var platform = platformCentroids.get(0);

    assertConnections(platform, Set.of(BoardingLocationToStopLink.class, AreaEdge.class));

    assertEquals(
      linkedVertices,
      platform
        .getOutgoingStreetEdges()
        .stream()
        .map(Edge::getToVertex)
        .map(Vertex::getLabel)
        .collect(Collectors.toSet())
    );

    assertEquals(
      linkedVertices,
      platform
        .getIncomingStreetEdges()
        .stream()
        .map(Edge::getFromVertex)
        .map(Vertex::getLabel)
        .collect(Collectors.toSet())
    );

    platformCentroids
      .stream()
      .flatMap(c -> Stream.concat(c.getIncoming().stream(), c.getOutgoing().stream()))
      .forEach(e -> assertNotNull(e.getName(), "Edge " + e + " returns null for getName()"));

    platformCentroids
      .stream()
      .flatMap(c -> Stream.concat(c.getIncoming().stream(), c.getOutgoing().stream()))
      .filter(StreetEdge.class::isInstance)
      .forEach(e -> assertEquals("101;102", e.getName().toString()));
  }

  private void assertConnections(
    OsmBoardingLocationVertex busBoardingLocation,
    Set<Class<? extends Edge>> expected
  ) {
    Stream
      .of(busBoardingLocation.getIncoming(), busBoardingLocation.getOutgoing())
      .forEach(edges ->
        assertEquals(expected, edges.stream().map(Edge::getClass).collect(Collectors.toSet()))
      );
  }
}
