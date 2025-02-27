package org.opentripplanner.routing.street;

import static org.opentripplanner.test.support.PolylineAssert.assertThatPolylinesAreEqual;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.opentripplanner.ConstantsForTests;
import org.opentripplanner.TestOtpModel;
import org.opentripplanner.model.GenericLocation;
import org.opentripplanner.routing.algorithm.mapping.GraphPathToItineraryMapper;
import org.opentripplanner.routing.api.request.RoutingRequest;
import org.opentripplanner.routing.core.RoutingContext;
import org.opentripplanner.routing.core.TemporaryVerticesContainer;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.TraverseModeSet;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.impl.GraphPathFinder;
import org.opentripplanner.util.PolylineEncoder;

public class CarRoutingTest {

  static final Instant dateTime = Instant.now();

  private static Graph herrenbergGraph;

  @BeforeAll
  public static void setup() {
    TestOtpModel model = ConstantsForTests.buildOsmGraph(ConstantsForTests.HERRENBERG_OSM);
    herrenbergGraph = model.graph();
    herrenbergGraph.index();
  }

  /**
   * The OTP algorithm tries hard to never visit the same node twice. This is generally a good idea
   * because it avoids useless loops in the traversal leading to way faster processing time.
   * <p>
   * However there is are certain rare pathological cases where through a series of turn
   * restrictions and roadworks you absolutely must visit a vertex twice if you want to produce a
   * result. One example would be a route like this: https://tinyurl.com/ycqux93g (Note: At the time
   * of writing this Hindenburgstr. (https://www.openstreetmap.org/way/415545869) is closed due to
   * roadworks.)
   * <p>
   * This test checks that such a loop is possible.
   * <p>
   * More information: https://github.com/opentripplanner/OpenTripPlanner/issues/3393
   */
  @Test
  @DisplayName("car routes can contain loops (traversing the same edge twice)")
  public void shouldAllowLoopCausedByTurnRestrictions() {
    TestOtpModel model = ConstantsForTests.buildOsmGraph(
      ConstantsForTests.HERRENBERG_HINDENBURG_STR_UNDER_CONSTRUCTION_OSM
    );
    var hindenburgStrUnderConstruction = model.graph();

    var gueltsteinerStr = new GenericLocation(48.59240, 8.87024);
    var aufDemGraben = new GenericLocation(48.59487, 8.87133);

    var polyline = computePolyline(hindenburgStrUnderConstruction, gueltsteinerStr, aufDemGraben);

    assertThatPolylinesAreEqual(
      polyline,
      "ouqgH}mcu@gAE]U}BaA]Q}@]uAs@[SAm@Ee@AUEi@XEQkBQ?Bz@Dt@Dh@@TGBC@KBSHGx@"
    );
  }

  @Test
  public void shouldRespectGeneralNoThroughTraffic() {
    var mozartStr = new GenericLocation(48.59521, 8.88391);
    var fritzLeharStr = new GenericLocation(48.59460, 8.88291);

    var polyline1 = computePolyline(herrenbergGraph, mozartStr, fritzLeharStr);
    assertThatPolylinesAreEqual(polyline1, "_grgHkcfu@OjBC\\ARGjAKzAfBz@j@n@Rk@E}D");

    var polyline2 = computePolyline(herrenbergGraph, fritzLeharStr, mozartStr);
    assertThatPolylinesAreEqual(polyline2, "gcrgHc}eu@D|DSj@k@o@gB{@J{AFkA@SB]NkB");
  }

  /**
   * Tests that that https://www.openstreetmap.org/way/35097400 is not taken due to
   * motor_vehicle=destination.
   */
  @Test
  public void shouldRespectMotorCarNoThru() {
    var schiessmauer = new GenericLocation(48.59737, 8.86350);
    var zeppelinStr = new GenericLocation(48.59972, 8.86239);

    var polyline1 = computePolyline(herrenbergGraph, schiessmauer, zeppelinStr);
    assertThatPolylinesAreEqual(
      polyline1,
      "otrgH{cbu@v@|D?bAElBEv@Cj@APGAY?YD]Fm@X_@Pw@d@eAn@k@VM@]He@Fo@Bi@??c@?Q@gD?Q?Q@mD?S"
    );

    var polyline2 = computePolyline(herrenbergGraph, zeppelinStr, schiessmauer);
    assertThatPolylinesAreEqual(
      polyline2,
      "ccsgH{|au@?RAlD?P?PAfD?P?b@h@?n@Cd@G\\ILAj@WdAo@v@e@^Ql@Y\\GXEX?F@@QBk@Dw@DmB?cAw@}D"
    );
  }

  @Test
  public void planningFromNoThroughTrafficPlaceTest() {
    var noThroughTrafficPlace = new GenericLocation(48.59634, 8.87020);
    var destination = new GenericLocation(48.59463, 8.87218);

    var polyline1 = computePolyline(herrenbergGraph, noThroughTrafficPlace, destination);
    assertThatPolylinesAreEqual(
      polyline1,
      "corgHkncu@OEYUOMH?J?LINMNMHTDO@YMm@HS`A}BPGRWLYDEt@HJ@b@?Fc@DONm@t@OXCBz@B\\"
    );

    var polyline2 = computePolyline(herrenbergGraph, destination, noThroughTrafficPlace);
    assertThatPolylinesAreEqual(
      polyline2,
      "scrgH_zcu@C]C{@YBu@NOl@ENGb@c@?KAu@IEDMXSVQFaA|BIRLl@AXENIUOLOLMHK?I?NLXTND"
    );
  }

  private static String computePolyline(Graph graph, GenericLocation from, GenericLocation to) {
    RoutingRequest request = new RoutingRequest();
    request.setDateTime(dateTime);
    request.from = from;
    request.to = to;

    request.streetSubRequestModes = new TraverseModeSet(TraverseMode.CAR);

    var temporaryVertices = new TemporaryVerticesContainer(graph, request);
    final RoutingContext routingContext = new RoutingContext(request, graph, temporaryVertices);

    var gpf = new GraphPathFinder(null, Duration.ofSeconds(5));
    var paths = gpf.graphPathFinderEntryPoint(routingContext);

    GraphPathToItineraryMapper graphPathToItineraryMapper = new GraphPathToItineraryMapper(
      ZoneId.of("Europe/Berlin"),
      graph.streetNotesService,
      graph.ellipsoidToGeoidDifference
    );

    var itineraries = graphPathToItineraryMapper.mapItineraries(paths);
    temporaryVertices.close();

    // make sure that we only get CAR legs
    itineraries.forEach(i ->
      i.getLegs().forEach(l -> Assertions.assertEquals(l.getMode(), TraverseMode.CAR))
    );
    Geometry legGeometry = itineraries.get(0).getLegs().get(0).getLegGeometry();
    return PolylineEncoder.encodeGeometry(legGeometry).points();
  }
}
