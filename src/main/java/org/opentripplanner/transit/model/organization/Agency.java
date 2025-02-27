/* This file is based on code copied from project OneBusAway, see the LICENSE file for further information. */
package org.opentripplanner.transit.model.organization;

import static org.opentripplanner.util.lang.AssertUtils.assertHasValue;

import java.time.ZoneId;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.transit.model.framework.LogInfo;
import org.opentripplanner.transit.model.framework.TransitEntity;

/**
 * This class is tha same as a GTFS Agency and Netex Authority.
 */
public final class Agency extends TransitEntity<Agency, AgencyBuilder> implements LogInfo {

  private final String name;
  private final ZoneId timezone;
  private final String url;
  private final String lang;
  private final String phone;
  private final String fareUrl;
  private final String brandingUrl;

  Agency(AgencyBuilder builder) {
    super(builder.getId());
    // Required fields
    this.name = assertHasValue(builder.getName());
    this.timezone = ZoneId.of(assertHasValue(builder.getTimezone()));

    // Optional fields
    this.url = builder.getUrl();
    this.lang = builder.getLang();
    this.phone = builder.getPhone();
    this.fareUrl = builder.getFareUrl();
    this.brandingUrl = builder.getBrandingUrl();
  }

  public static AgencyBuilder of(@Nonnull FeedScopedId id) {
    return new AgencyBuilder(id);
  }

  @Nonnull
  public String getName() {
    return logName();
  }

  @Nonnull
  public ZoneId getTimezone() {
    return timezone;
  }

  @Nullable
  public String getUrl() {
    return url;
  }

  @Nullable
  public String getLang() {
    return lang;
  }

  @Nullable
  public String getPhone() {
    return phone;
  }

  @Nullable
  public String getFareUrl() {
    return fareUrl;
  }

  @Nullable
  public String getBrandingUrl() {
    return brandingUrl;
  }

  @Override
  @Nonnull
  public AgencyBuilder copy() {
    return new AgencyBuilder(this);
  }

  @Override
  @Nonnull
  public String logName() {
    return name;
  }

  @Override
  public boolean sameAs(@Nonnull Agency other) {
    return (
      getId().equals(other.getId()) &&
      Objects.equals(name, other.name) &&
      Objects.equals(timezone, other.timezone) &&
      Objects.equals(url, other.url) &&
      Objects.equals(lang, other.lang) &&
      Objects.equals(phone, other.phone) &&
      Objects.equals(fareUrl, other.fareUrl) &&
      Objects.equals(brandingUrl, other.brandingUrl)
    );
  }
}
