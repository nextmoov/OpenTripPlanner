package org.opentripplanner.transit.model.site;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.opentripplanner.transit.model.basic.I18NString;
import org.opentripplanner.transit.model.basic.WgsCoordinate;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.transit.model.framework.TransitEntity;

/**
 * A grouping that can contain a mix of Stations and MultiModalStations. It can be used to link
 * several StopPlaces into a hub. It can be a grouping of major stops within a city or a cluster of
 * stops that naturally belong together.
 */
public class GroupOfStations
  extends TransitEntity<GroupOfStations, GroupOfStationsBuilder>
  implements StopCollection {

  private final Set<StopCollection> childStations;
  private final I18NString name;
  private final GroupOfStationsPurpose purposeOfGrouping;
  private final WgsCoordinate coordinate;

  GroupOfStations(GroupOfStationsBuilder builder) {
    super(builder.getId());
    // Required fields
    this.name = I18NString.assertHasValue(builder.name());
    this.childStations = Objects.requireNonNull(builder.childStations());

    // Optional fields
    this.purposeOfGrouping = builder.purposeOfGrouping();
    // TODO: Coordinate should be
    this.coordinate = builder.coordinate();
  }

  public static GroupOfStationsBuilder of(FeedScopedId id) {
    return new GroupOfStationsBuilder(id);
  }

  @Nonnull
  public I18NString getName() {
    return name;
  }

  @Override
  @Nullable
  public WgsCoordinate getCoordinate() {
    return coordinate;
  }

  @Nonnull
  public Collection<StopLocation> getChildStops() {
    return this.childStations.stream().flatMap(s -> s.getChildStops().stream()).toList();
  }

  @Nonnull
  public Collection<StopCollection> getChildStations() {
    return this.childStations;
  }

  /**
   * Categorization for the grouping
   */
  @Nullable
  public GroupOfStationsPurpose getPurposeOfGrouping() {
    return purposeOfGrouping;
  }

  @Override
  public boolean sameAs(@Nonnull GroupOfStations other) {
    return (
      getId().equals(other.getId()) &&
      Objects.equals(name, other.getName()) &&
      Objects.equals(childStations, other.getChildStations()) &&
      Objects.equals(coordinate, other.getCoordinate()) &&
      Objects.equals(purposeOfGrouping, other.getPurposeOfGrouping())
    );
  }

  @Nonnull
  @Override
  public GroupOfStationsBuilder copy() {
    return new GroupOfStationsBuilder(this);
  }
}
