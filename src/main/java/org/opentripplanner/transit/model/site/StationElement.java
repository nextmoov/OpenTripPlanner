package org.opentripplanner.transit.model.site;

import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.opentripplanner.transit.model.basic.I18NString;
import org.opentripplanner.transit.model.basic.WgsCoordinate;
import org.opentripplanner.transit.model.basic.WheelchairAccessibility;
import org.opentripplanner.transit.model.framework.TransitEntity;

/**
 * Acts as the supertype for all entities, except stations, created from the GTFS stops table. Most
 * of the fields are shared between the types, and eg. in pathways the namespace any of them can be
 * used as from and to.
 */
public abstract class StationElement<
  E extends StationElement<E, B>, B extends StationElementBuilder<E, B>
>
  extends TransitEntity<E, B> {

  private final I18NString name;

  private final String code;

  private final I18NString description;

  private final WgsCoordinate coordinate;

  private final WheelchairAccessibility wheelchairAccessibility;

  private final StopLevel level;

  private final Station parentStation;

  StationElement(B builder) {
    super(builder.getId());
    // Required fields
    this.name = builder.name();
    this.wheelchairAccessibility =
      Objects.requireNonNullElse(
        builder.wheelchairAccessibility(),
        WheelchairAccessibility.NO_INFORMATION
      );

    // Optional fields
    this.coordinate = builder.coordinate();
    this.code = builder.code();
    this.description = builder.description();
    this.level = builder.level();
    this.parentStation = builder.parentStation();
  }

  /**
   * Name of the station element if provided.
   */
  @Nonnull
  public I18NString getName() {
    return name;
  }

  /**
   * Public facing stop code (short text or number).
   */
  @Nullable
  public String getCode() {
    return code;
  }

  /**
   * Additional information about the station element (if needed).
   */
  @Nullable
  public I18NString getDescription() {
    return description;
  }

  /**
   * The coordinate for the given stop element exist. The {@link #getCoordinate()} will use the
   * parent station coordinate if not set, but this method will return based on this instance; Hence
   * the {@link #getCoordinate()} might return a coordinate, while this method return {@code
   * false}.
   */
  boolean isCoordinateSet() {
    return coordinate != null;
  }

  /**
   * Center point/location for the station element. Returns the coordinate of the parent station, if
   * the coordinate is not defined for this station element.
   */
  @Nonnull
  public WgsCoordinate getCoordinate() {
    if (coordinate != null) {
      return coordinate;
    }
    if (parentStation != null) {
      return parentStation.getCoordinate();
    }
    throw new IllegalStateException("Coordinate not set for: " + toString());
  }

  /**
   * Returns whether this station element is accessible for wheelchair users.
   */
  @Nonnull
  public WheelchairAccessibility getWheelchairAccessibility() {
    return wheelchairAccessibility;
  }

  /** Level name for elevator descriptions */
  @Nullable
  public StopLevel level() {
    return level;
  }

  /** Level name for elevator descriptions */
  @Nullable
  public String getLevelName() {
    return level == null ? null : level.getName();
  }

  /** Level index for hop counts in elevators. Is {@code null} if not set. */
  @Nullable
  public Double getLevelIndex() {
    return level == null ? null : level.getIndex();
  }

  /** Parent station for the station element */
  @Nullable
  public Station getParentStation() {
    return parentStation;
  }

  /** Return {@code true} if this stop (element) is part of a station, have a parent station. */
  public boolean isPartOfStation() {
    return parentStation != null;
  }

  /**
   * Return {@code true} if this stop (element) has the same parent station as the other stop
   * (element).
   */
  public boolean isPartOfSameStationAs(StopLocation other) {
    if (other == null) {
      return false;
    }
    return isPartOfStation() && parentStation.equals(other.getParentStation());
  }

  @Override
  public boolean sameAs(@Nonnull E other) {
    return (
      getId().equals(other.getId()) &&
      Objects.equals(name, other.getName()) &&
      Objects.equals(code, other.getCode()) &&
      Objects.equals(description, other.getDescription()) &&
      Objects.equals(coordinate, other.getCoordinate()) &&
      Objects.equals(wheelchairAccessibility, other.getWheelchairAccessibility()) &&
      Objects.equals(level, other.level()) &&
      Objects.equals(parentStation, other.getParentStation())
    );
  }
}
