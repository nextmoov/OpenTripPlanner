package org.opentripplanner.routing.algorithm.mapping;

import au.com.origin.snapshots.junit5.SnapshotExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.opentripplanner.model.GenericLocation;
import org.opentripplanner.routing.api.request.RequestModes;
import org.opentripplanner.routing.api.request.RoutingRequest;
import org.opentripplanner.routing.api.request.StreetMode;
import org.opentripplanner.transit.model.basic.MainAndSubMode;

@ExtendWith(SnapshotExtension.class)
public class TransitSnapshotTest extends SnapshotTestBase {

  static GenericLocation ptc = GenericLocation.fromStopId(
    "Rose Quarter Transit Center",
    "prt",
    "79-tc"
  );

  static GenericLocation ps = GenericLocation.fromStopId("NE 12th & Couch", "prt", "6577");

  static GenericLocation p0 = new GenericLocation(
    "SE Stark    St. & SE 17th Ave. (P0)",
    null,
    45.519320,
    -122.648567
  );

  static GenericLocation p1 = new GenericLocation(
    "SE Morrison St. & SE 17th Ave. (P1)",
    null,
    45.51726,
    -122.64847
  );

  static GenericLocation p2 = new GenericLocation(
    "NW Northrup St. & NW 22nd Ave. (P2)",
    null,
    45.53122,
    -122.69659
  );

  static GenericLocation p3 = new GenericLocation(
    "NW Northrup St. & NW 24th Ave. (P3)",
    null,
    45.53100,
    -122.70029
  );

  static GenericLocation p4 = new GenericLocation(
    "NE Thompson St. & NE 18th Ave. (P4)",
    null,
    45.53896,
    -122.64699
  );

  @BeforeAll
  public static void beforeClass() {
    loadGraphBeforeClass(false);
  }

  @Test
  public void test_trip_planning_with_walk_only() {
    RoutingRequest request = createTestRequest(2009, 11, 17, 10, 0, 0);

    request.modes = RequestModes.of().withDirectMode(StreetMode.WALK).clearTransitModes().build();
    request.from = p0;
    request.to = p2;

    expectArriveByToMatchDepartAtAndSnapshot(request);
  }

  @Test
  public void test_trip_planning_with_walk_only_stop() {
    RoutingRequest request = createTestRequest(2009, 11, 17, 10, 0, 0);

    request.modes =
      RequestModes
        .of()
        .withAccessMode(StreetMode.WALK)
        .withEgressMode(StreetMode.WALK)
        .withDirectMode(StreetMode.WALK)
        .withTransferMode(StreetMode.WALK)
        .clearTransitModes()
        .build();
    request.from = ps;
    request.to = p2;

    expectArriveByToMatchDepartAtAndSnapshot(request);
  }

  @Test
  public void test_trip_planning_with_walk_only_stop_collection() {
    RoutingRequest request = createTestRequest(2009, 11, 17, 10, 0, 0);

    request.modes =
      RequestModes
        .of()
        .withAccessMode(StreetMode.WALK)
        .withEgressMode(StreetMode.WALK)
        .withDirectMode(StreetMode.WALK)
        .withTransferMode(StreetMode.WALK)
        .clearTransitModes()
        .build();
    request.from = ptc;
    request.to = p3;

    expectRequestResponseToMatchSnapshot(request);
    // not equal - expectArriveByToMatchDepartAtAndSnapshot(request);
  }

  @Test
  public void test_trip_planning_with_transit() {
    RoutingRequest request = createTestRequest(2009, 11, 17, 10, 0, 0);

    request.modes =
      RequestModes
        .of()
        .withAccessMode(StreetMode.WALK)
        .withEgressMode(StreetMode.WALK)
        .withDirectMode(StreetMode.WALK)
        .withTransferMode(StreetMode.WALK)
        .withTransitModes(MainAndSubMode.all())
        .build();
    request.from = p1;
    request.to = p2;

    expectRequestResponseToMatchSnapshot(request);
  }

  @Test
  public void test_trip_planning_with_transit_stop() {
    RoutingRequest request = createTestRequest(2009, 11, 17, 10, 0, 0);

    request.modes =
      RequestModes
        .of()
        .withAccessMode(StreetMode.WALK)
        .withEgressMode(StreetMode.WALK)
        .withDirectMode(StreetMode.WALK)
        .withTransferMode(StreetMode.WALK)
        .withTransitModes(MainAndSubMode.all())
        .build();
    request.from = ps;
    request.to = p3;

    expectArriveByToMatchDepartAtAndSnapshot(request);
  }

  @Test
  @Disabled
  public void test_trip_planning_with_transit_stop_collection() {
    RoutingRequest request = createTestRequest(2009, 11, 17, 10, 0, 0);

    request.modes =
      RequestModes
        .of()
        .withAccessMode(StreetMode.WALK)
        .withEgressMode(StreetMode.WALK)
        .withDirectMode(StreetMode.WALK)
        .withTransferMode(StreetMode.WALK)
        .withTransitModes(MainAndSubMode.all())
        .build();
    request.from = ptc;
    request.to = p3;

    expectArriveByToMatchDepartAtAndSnapshot(request);
  }
}
