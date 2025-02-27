package org.opentripplanner.transit.model.network;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.opentripplanner.model.Timetable;
import org.opentripplanner.transit.model.framework.AbstractEntityBuilder;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.util.geometry.CompactLineStringUtils;
import org.opentripplanner.util.geometry.GeometryUtils;

@SuppressWarnings("UnusedReturnValue")
public final class TripPatternBuilder
  extends AbstractEntityBuilder<TripPattern, TripPatternBuilder> {

  private Route route;
  private StopPattern stopPattern;
  private Timetable scheduledTimetable;
  private String name;

  private boolean createdByRealtimeUpdate;

  private TripPattern originalTripPattern;
  private List<LineString> hopGeometries;

  TripPatternBuilder(FeedScopedId id) {
    super(id);
  }

  TripPatternBuilder(TripPattern original) {
    super(original);
    this.name = original.getName();
    this.route = original.getRoute();
    this.stopPattern = original.getStopPattern();
    this.scheduledTimetable = original.getScheduledTimetable();
    this.createdByRealtimeUpdate = original.isCreatedByRealtimeUpdater();
    this.originalTripPattern = original.getOriginalTripPattern();
    this.hopGeometries =
      original.getGeometry() == null
        ? null
        : IntStream.range(0, original.numberOfStops()).mapToObj(original::getHopGeometry).toList();
  }

  public TripPatternBuilder withName(String name) {
    this.name = name;
    return this;
  }

  public TripPatternBuilder withRoute(Route route) {
    this.route = route;
    return this;
  }

  public TripPatternBuilder withStopPattern(StopPattern stopPattern) {
    this.stopPattern = stopPattern;
    return this;
  }

  public TripPatternBuilder withCreatedByRealtimeUpdater(boolean createdByRealtimeUpdate) {
    this.createdByRealtimeUpdate = createdByRealtimeUpdate;
    return this;
  }

  public TripPatternBuilder withOriginalTripPattern(TripPattern originalTripPattern) {
    this.originalTripPattern = originalTripPattern;
    return this;
  }

  public TripPatternBuilder withHopGeometries(List<LineString> hopGeometries) {
    this.hopGeometries = hopGeometries;
    return this;
  }

  @Override
  protected TripPattern buildFromValues() {
    return new TripPattern(this);
  }

  public Route getRoute() {
    return route;
  }

  public StopPattern getStopPattern() {
    return stopPattern;
  }

  public Timetable getScheduledTimetable() {
    return scheduledTimetable;
  }

  public String getName() {
    return name;
  }

  public TripPattern getOriginalTripPattern() {
    return originalTripPattern;
  }

  public boolean isCreatedByRealtimeUpdate() {
    return createdByRealtimeUpdate;
  }

  public byte[][] hopGeometries() {
    List<LineString> geometries;
    if (this.hopGeometries != null) {
      geometries = this.hopGeometries;
    } else if (this.originalTripPattern != null) {
      geometries = generateHopGeometriesFromOriginalTripPattern();
    } else {
      return null;
    }

    return geometries
      .stream()
      .map(hopGeometry -> CompactLineStringUtils.compactLineString(hopGeometry, false))
      .toArray(byte[][]::new);
  }

  /**
   * This will copy the geometry from another TripPattern to this one. It checks if each hop is
   * between the same stops before copying that hop geometry. If the stops are different but lie
   * within same station, old geometry will be used with overwrite on first and last point (to match
   * new stop places). Otherwise, it will default to straight lines between hops.
   */
  private List<LineString> generateHopGeometriesFromOriginalTripPattern() {
    // This accounts for the new TripPattern provided by a real-time update and the one that is
    // being replaced having a different number of stops. In that case the geometry will be
    // preserved up until the first mismatching stop, and a straight line will be used for
    // all segments after that.
    int sizeOfShortestPattern = Math.min(
      stopPattern.getSize(),
      originalTripPattern.numberOfStops()
    );

    List<LineString> hopGeometries = new ArrayList<>();

    for (int i = 0; i < sizeOfShortestPattern - 1; i++) {
      LineString hopGeometry = originalTripPattern.getHopGeometry(i);

      if (hopGeometry != null && stopPattern.sameStops(originalTripPattern.getStopPattern(), i)) {
        // Copy hop geometry from previous pattern
        hopGeometries.add(originalTripPattern.getHopGeometry(i));
      } else if (
        hopGeometry != null && stopPattern.sameStations(originalTripPattern.getStopPattern(), i)
      ) {
        // Use old geometry but patch first and last point with new stops
        var newStart = new Coordinate(
          stopPattern.getStop(i).getCoordinate().longitude(),
          stopPattern.getStop(i).getCoordinate().latitude()
        );

        var newEnd = new Coordinate(
          stopPattern.getStop(i + 1).getCoordinate().longitude(),
          stopPattern.getStop(i + 1).getCoordinate().latitude()
        );

        Coordinate[] coordinates = originalTripPattern.getHopGeometry(i).getCoordinates().clone();
        coordinates[0].setCoordinate(newStart);
        coordinates[coordinates.length - 1].setCoordinate(newEnd);

        hopGeometries.add(GeometryUtils.getGeometryFactory().createLineString(coordinates));
      } else {
        // Create new straight-line geometry for hop
        hopGeometries.add(
          GeometryUtils
            .getGeometryFactory()
            .createLineString(
              new Coordinate[] {
                stopPattern.getStop(i).getCoordinate().asJtsCoordinate(),
                stopPattern.getStop(i + 1).getCoordinate().asJtsCoordinate(),
              }
            )
        );
      }
    }
    return hopGeometries;
  }
}
