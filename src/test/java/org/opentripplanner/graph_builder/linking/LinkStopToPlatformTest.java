package org.opentripplanner.graph_builder.linking;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.TraverseModeSet;
import org.opentripplanner.routing.edgetype.AreaEdge;
import org.opentripplanner.routing.edgetype.AreaEdgeList;
import org.opentripplanner.routing.edgetype.StreetTransitStopLink;
import org.opentripplanner.routing.edgetype.StreetTraversalPermission;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.vertextype.IntersectionVertex;
import org.opentripplanner.routing.vertextype.TransitStopVertex;
import org.opentripplanner.routing.vertextype.TransitStopVertexBuilder;
import org.opentripplanner.transit.model._data.TransitModelForTest;
import org.opentripplanner.transit.model.basic.I18NString;
import org.opentripplanner.transit.model.basic.LocalizedString;
import org.opentripplanner.transit.model.framework.Deduplicator;
import org.opentripplanner.transit.model.site.Stop;
import org.opentripplanner.transit.service.StopModel;
import org.opentripplanner.transit.service.TransitModel;
import org.opentripplanner.util.geometry.GeometryUtils;

public class LinkStopToPlatformTest {

  private static final GeometryFactory geometryFactory = GeometryUtils.getGeometryFactory();

  private Graph graph;
  private TransitModel transitModel;

  @BeforeEach
  public void before() {
    // Set up transit platform

    var deduplicator = new Deduplicator();
    var stopModel = new StopModel();
    graph = new Graph(stopModel, deduplicator);
    transitModel = new TransitModel(stopModel, deduplicator);

    ArrayList<IntersectionVertex> vertices = new ArrayList<>();

    vertices.add(new IntersectionVertex(graph, "1", 10.22054, 59.13568, "Platform vertex 1"));
    vertices.add(new IntersectionVertex(graph, "2", 10.22432, 59.13519, "Platform vertex 2"));
    vertices.add(new IntersectionVertex(graph, "3", 10.22492, 59.13514, "Platform vertex 3"));
    vertices.add(new IntersectionVertex(graph, "4", 10.22493, 59.13518, "Platform vertex 4"));
    vertices.add(new IntersectionVertex(graph, "5", 10.22056, 59.13575, "Platform vertex 5"));

    AreaEdgeList areaEdgeList = new AreaEdgeList(
      GeometryUtils.getGeometryFactory().createPolygon(),
      Set.of()
    );

    ArrayList<AreaEdge> edges = new ArrayList<>();

    edges.add(createAreaEdge(vertices.get(0), vertices.get(1), areaEdgeList, "edge 1"));
    edges.add(createAreaEdge(vertices.get(1), vertices.get(2), areaEdgeList, "edge 2"));
    edges.add(createAreaEdge(vertices.get(2), vertices.get(3), areaEdgeList, "edge 3"));
    edges.add(createAreaEdge(vertices.get(3), vertices.get(4), areaEdgeList, "edge 4"));
    edges.add(createAreaEdge(vertices.get(4), vertices.get(0), areaEdgeList, "edge 5"));

    edges.add(createAreaEdge(vertices.get(1), vertices.get(0), areaEdgeList, "edge 6"));
    edges.add(createAreaEdge(vertices.get(2), vertices.get(1), areaEdgeList, "edge 7"));
    edges.add(createAreaEdge(vertices.get(3), vertices.get(2), areaEdgeList, "edge 8"));
    edges.add(createAreaEdge(vertices.get(4), vertices.get(3), areaEdgeList, "edge 9"));
    edges.add(createAreaEdge(vertices.get(0), vertices.get(4), areaEdgeList, "edge 10"));

    Stop stop = TransitModelForTest.stop("TestStop").withCoordinate(59.13545, 10.22213).build();

    TransitStopVertex stopVertex = new TransitStopVertexBuilder()
      .withGraph(graph)
      .withStop(stop)
      .withTransitModel(transitModel)
      .build();
  }

  /**
   * Tests that extra edges are added when linking stops to platform areas to prevent detours around
   * the platform.
   */
  @Test
  public void testLinkStopWithoutExtraEdges() {
    VertexLinker linker = graph.getLinker();

    for (TransitStopVertex tStop : graph.getVerticesOfType(TransitStopVertex.class)) {
      linker.linkVertexPermanently(
        tStop,
        new TraverseModeSet(TraverseMode.WALK),
        LinkingDirection.BOTH_WAYS,
        (vertex, streetVertex) ->
          List.of(
            new StreetTransitStopLink((TransitStopVertex) vertex, streetVertex),
            new StreetTransitStopLink(streetVertex, (TransitStopVertex) vertex)
          )
      );
    }

    assertEquals(16, graph.getEdges().size());
  }

  @Test
  @Disabled
  public void testLinkStopWithExtraEdges() {
    // TODO Reimplement this functionality #3152
    //        SimpleStreetSplitter splitter = SimpleStreetSplitter.createForTest(graph);
    //        splitter.setAddExtraEdgesToAreas(true);
    //        splitter.link();
    //
    //        assertEquals(38, graph.getEdges().size());
  }

  private AreaEdge createAreaEdge(
    IntersectionVertex v1,
    IntersectionVertex v2,
    AreaEdgeList area,
    String nameString
  ) {
    LineString line = geometryFactory.createLineString(
      new Coordinate[] { v1.getCoordinate(), v2.getCoordinate() }
    );
    I18NString name = new LocalizedString(nameString);

    return new AreaEdge(
      v1,
      v2,
      line,
      name,
      line.getLength(),
      StreetTraversalPermission.PEDESTRIAN_AND_BICYCLE,
      false,
      area
    );
  }
}
