package org.opentripplanner.graph_builder.module.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.opentripplanner.graph_builder.DataImportIssueStore.noopIssueStore;
import static org.opentripplanner.graph_builder.module.osm.WayPropertiesBuilder.withModes;
import static org.opentripplanner.routing.edgetype.StreetTraversalPermission.ALL;
import static org.opentripplanner.routing.edgetype.StreetTraversalPermission.PEDESTRIAN;

import java.io.File;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.opentripplanner.common.model.P2;
import org.opentripplanner.openstreetmap.OpenStreetMapProvider;
import org.opentripplanner.openstreetmap.model.OSMWay;
import org.opentripplanner.openstreetmap.model.OSMWithTags;
import org.opentripplanner.routing.api.request.RoutingRequest;
import org.opentripplanner.routing.core.RoutingContext;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.TraverseModeSet;
import org.opentripplanner.routing.edgetype.StreetEdge;
import org.opentripplanner.routing.graph.Edge;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.impl.GraphPathFinder;
import org.opentripplanner.routing.spt.GraphPath;
import org.opentripplanner.routing.vertextype.IntersectionVertex;
import org.opentripplanner.transit.model.basic.LocalizedString;
import org.opentripplanner.transit.model.basic.NonLocalizedString;
import org.opentripplanner.transit.model.framework.Deduplicator;
import org.opentripplanner.transit.service.StopModel;
import org.opentripplanner.transit.service.TransitModel;

public class OpenStreetMapModuleTest {

  @Test
  public void testGraphBuilder() {
    var deduplicator = new Deduplicator();
    var stopModel = new StopModel();
    var gg = new Graph(stopModel, deduplicator);
    var transitModel = new TransitModel(stopModel, deduplicator);

    File file = new File(
      URLDecoder.decode(getClass().getResource("map.osm.pbf").getFile(), StandardCharsets.UTF_8)
    );

    OpenStreetMapProvider provider = new OpenStreetMapProvider(file, true);

    OpenStreetMapModule osmModule = new OpenStreetMapModule(
      List.of(provider),
      Set.of(),
      gg,
      transitModel.getTimeZone(),
      noopIssueStore()
    );
    osmModule.setDefaultWayPropertySetSource(new DefaultWayPropertySetSource());

    osmModule.buildGraph();

    // Kamiennogorska at south end of segment
    Vertex v1 = gg.getVertex("osm:node:280592578");

    // Kamiennogorska at Mariana Smoluchowskiego
    Vertex v2 = gg.getVertex("osm:node:288969929");

    // Mariana Smoluchowskiego, north end
    Vertex v3 = gg.getVertex("osm:node:280107802");

    // Mariana Smoluchowskiego, south end (of segment connected to v2)
    Vertex v4 = gg.getVertex("osm:node:288970952");

    assertNotNull(v1);
    assertNotNull(v2);
    assertNotNull(v3);
    assertNotNull(v4);

    Edge e1 = null, e2 = null, e3 = null;
    for (Edge e : v2.getOutgoing()) {
      if (e.getToVertex() == v1) {
        e1 = e;
      } else if (e.getToVertex() == v3) {
        e2 = e;
      } else if (e.getToVertex() == v4) {
        e3 = e;
      }
    }

    assertNotNull(e1);
    assertNotNull(e2);
    assertNotNull(e3);

    assertTrue(
      e1.getDefaultName().contains("Kamiennog\u00F3rska"),
      "name of e1 must be like \"Kamiennog\u00F3rska\"; was " + e1.getDefaultName()
    );
    assertTrue(
      e2.getDefaultName().contains("Mariana Smoluchowskiego"),
      "name of e2 must be like \"Mariana Smoluchowskiego\"; was " + e2.getDefaultName()
    );
  }

  /**
   * Detailed testing of OSM graph building using a very small chunk of NYC (SOHO-ish).
   */
  @Test
  public void testBuildGraphDetailed() throws Exception {
    var deduplicator = new Deduplicator();
    var stopModel = new StopModel();
    var gg = new Graph(stopModel, deduplicator);
    var transitModel = new TransitModel(stopModel, deduplicator);

    File file = new File(
      URLDecoder.decode(
        getClass().getResource("NYC_small.osm.pbf").getFile(),
        StandardCharsets.UTF_8
      )
    );
    OpenStreetMapProvider provider = new OpenStreetMapProvider(file, true);
    OpenStreetMapModule osmModule = new OpenStreetMapModule(
      List.of(provider),
      Set.of(),
      gg,
      transitModel.getTimeZone(),
      noopIssueStore()
    );
    osmModule.setDefaultWayPropertySetSource(new DefaultWayPropertySetSource());

    osmModule.buildGraph();

    // These vertices are labeled in the OSM file as having traffic lights.
    IntersectionVertex iv1 = (IntersectionVertex) gg.getVertex("osm:node:1919595918");
    IntersectionVertex iv2 = (IntersectionVertex) gg.getVertex("osm:node:42442273");
    IntersectionVertex iv3 = (IntersectionVertex) gg.getVertex("osm:node:1919595927");
    IntersectionVertex iv4 = (IntersectionVertex) gg.getVertex("osm:node:42452026");
    assertTrue(iv1.trafficLight);
    assertTrue(iv2.trafficLight);
    assertTrue(iv3.trafficLight);
    assertTrue(iv4.trafficLight);

    // These are not.
    IntersectionVertex iv5 = (IntersectionVertex) gg.getVertex("osm:node:42435485");
    IntersectionVertex iv6 = (IntersectionVertex) gg.getVertex("osm:node:42439335");
    IntersectionVertex iv7 = (IntersectionVertex) gg.getVertex("osm:node:42436761");
    IntersectionVertex iv8 = (IntersectionVertex) gg.getVertex("osm:node:42442291");
    assertFalse(iv5.trafficLight);
    assertFalse(iv6.trafficLight);
    assertFalse(iv7.trafficLight);
    assertFalse(iv8.trafficLight);

    Set<P2<Vertex>> edgeEndpoints = new HashSet<>();
    for (StreetEdge se : gg.getStreetEdges()) {
      P2<Vertex> endpoints = new P2<>(se.getFromVertex(), se.getToVertex());
      // Check that we don't get any duplicate edges on this small graph.
      if (edgeEndpoints.contains(endpoints)) {
        fail();
      }
      edgeEndpoints.add(endpoints);
    }
  }

  @Test
  public void testBuildAreaWithoutVisibility() throws Exception {
    testBuildingAreas(true);
  }

  @Test
  public void testBuildAreaWithVisibility() throws Exception {
    testBuildingAreas(false);
  }

  @Test
  public void testWayDataSet() {
    OSMWithTags way = new OSMWay();
    way.addTag("highway", "footway");
    way.addTag("cycleway", "lane");
    way.addTag("access", "no");
    way.addTag("surface", "gravel");

    WayPropertySet wayPropertySet = new WayPropertySet();

    // where there are no way specifiers, the default is used
    assertEquals(wayPropertySet.getDataForWay(way), wayPropertySet.defaultProperties);

    // add two equal matches: lane only...
    OSMSpecifier lane_only = new OSMSpecifier("cycleway=lane");

    WayProperties lane_is_safer = withModes(ALL).bicycleSafety(1.5).build();

    wayPropertySet.addProperties(lane_only, lane_is_safer);

    // and footway only
    OSMSpecifier footway_only = new OSMSpecifier("highway=footway");

    WayProperties footways_allow_peds = new WayPropertiesBuilder(PEDESTRIAN).build();

    wayPropertySet.addProperties(footway_only, footways_allow_peds);

    WayProperties dataForWay = wayPropertySet.getDataForWay(way);
    // the first one is found
    assertEquals(dataForWay, lane_is_safer);

    // add a better match
    OSMSpecifier lane_and_footway = new OSMSpecifier("cycleway=lane;highway=footway");

    WayProperties safer_and_peds = new WayPropertiesBuilder(PEDESTRIAN).bicycleSafety(0.75).build();

    wayPropertySet.addProperties(lane_and_footway, safer_and_peds);
    dataForWay = wayPropertySet.getDataForWay(way);
    assertEquals(dataForWay, safer_and_peds);

    // add a mixin
    OSMSpecifier gravel = new OSMSpecifier("surface=gravel");
    WayProperties gravel_is_dangerous = new WayPropertiesBuilder(ALL).bicycleSafety(2).build();
    wayPropertySet.addProperties(gravel, gravel_is_dangerous, true);

    dataForWay = wayPropertySet.getDataForWay(way);
    assertEquals(dataForWay.getBicycleSafetyFeatures().first, 1.5);

    // test a left-right distinction
    way = new OSMWay();
    way.addTag("highway", "footway");
    way.addTag("cycleway", "lane");
    way.addTag("cycleway:right", "track");

    OSMSpecifier track_only = new OSMSpecifier("highway=footway;cycleway=track");
    WayProperties track_is_safest = new WayPropertiesBuilder(ALL).bicycleSafety(0.25).build();

    wayPropertySet.addProperties(track_only, track_is_safest);
    dataForWay = wayPropertySet.getDataForWay(way);
    assertEquals(0.25, dataForWay.getBicycleSafetyFeatures().first); // right (with traffic) comes
    // from track
    assertEquals(0.75, dataForWay.getBicycleSafetyFeatures().second); // left comes from lane

    way = new OSMWay();
    way.addTag("highway", "footway");
    way.addTag("footway", "sidewalk");
    way.addTag("RLIS:reviewed", "no");
    WayPropertySet propset = new WayPropertySet();
    CreativeNamer namer = new CreativeNamer("platform");
    propset.addCreativeNamer(
      new OSMSpecifier("railway=platform;highway=footway;footway=sidewalk"),
      namer
    );
    namer = new CreativeNamer("sidewalk");
    propset.addCreativeNamer(new OSMSpecifier("highway=footway;footway=sidewalk"), namer);
    assertEquals("sidewalk", propset.getCreativeNameForWay(way).toString());
  }

  @Test
  public void testCreativeNaming() {
    OSMWithTags way = new OSMWay();
    way.addTag("highway", "footway");
    way.addTag("cycleway", "lane");
    way.addTag("access", "no");

    CreativeNamer namer = new CreativeNamer();
    namer.setCreativeNamePattern(
      "Highway with cycleway {cycleway} and access {access} and morx {morx}"
    );
    assertEquals(
      "Highway with cycleway lane and access no and morx ",
      namer.generateCreativeName(way).toString()
    );
  }

  @Test
  public void testLocalizedString() {
    LocalizedString localizedString = new LocalizedString(
      "corner",
      new NonLocalizedString("first"),
      new NonLocalizedString("second")
    );

    assertEquals("Kreuzung first mit second", localizedString.toString(new Locale("de")));
  }

  /**
   * This reads test file with area and tests if it can be routed if visibility is used and if it
   * isn't
   * <p>
   * Routing needs to be successful in both options since without visibility calculation area rings
   * are used.
   *
   * @param skipVisibility if true visibility calculations are skipped
   */
  private void testBuildingAreas(boolean skipVisibility) {
    var deduplicator = new Deduplicator();
    var stopModel = new StopModel();
    var graph = new Graph(stopModel, deduplicator);
    var transitModel = new TransitModel(stopModel, deduplicator);

    File file = new File(
      URLDecoder.decode(
        getClass().getResource("usf_area.osm.pbf").getFile(),
        StandardCharsets.UTF_8
      )
    );
    OpenStreetMapProvider provider = new OpenStreetMapProvider(file, false);

    OpenStreetMapModule loader = new OpenStreetMapModule(
      List.of(provider),
      Set.of(),
      graph,
      transitModel.getTimeZone(),
      noopIssueStore()
    );
    loader.skipVisibility = skipVisibility;
    loader.setDefaultWayPropertySetSource(new DefaultWayPropertySetSource());

    loader.buildGraph();

    RoutingRequest request = new RoutingRequest(new TraverseModeSet(TraverseMode.WALK));

    //This are vertices that can be connected only over edges on area (with correct permissions)
    //It tests if it is possible to route over area without visibility calculations
    Vertex bottomV = graph.getVertex("osm:node:580290955");
    Vertex topV = graph.getVertex("osm:node:559271124");

    RoutingContext routingContext = new RoutingContext(request, graph, bottomV, topV);

    GraphPathFinder graphPathFinder = new GraphPathFinder(null, Duration.ofSeconds(3));
    List<GraphPath> pathList = graphPathFinder.graphPathFinderEntryPoint(routingContext);

    assertNotNull(pathList);
    assertFalse(pathList.isEmpty());
    for (GraphPath path : pathList) {
      assertFalse(path.states.isEmpty());
    }
  }
  // disabled pending discussion with author (AMB)
  // @Test
  // public void testMultipolygon() throws Exception {
  // Graph gg = new Graph();
  // OpenStreetMapGraphBuilderImpl loader = new OpenStreetMapGraphBuilderImpl();
  //
  // FileBasedOpenStreetMapProviderImpl pr = new FileBasedOpenStreetMapProviderImpl();
  // pr.setPath(new File(getClass().getResource("otp-multipolygon-test.osm").getPath()));
  // loader.setProvider(pr);
  //
  // loader.buildGraph(gg, extra);
  //
  // assertNotNull(gg.getVertex("way -3535 from 4"));
  // }
}
