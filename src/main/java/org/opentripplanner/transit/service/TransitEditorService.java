package org.opentripplanner.transit.service;

import java.util.function.Function;
import org.opentripplanner.model.FeedInfo;
import org.opentripplanner.model.TimetableSnapshotProvider;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.TransitLayer;
import org.opentripplanner.transit.model.basic.TransitMode;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.organization.Agency;

/**
 * Entry point for requests (both read-only and read-write) towards the transit API.
 */
public interface TransitEditorService extends TransitService {
  void addAgency(String feedId, Agency agency);

  void addFeedInfo(FeedInfo info);

  void addRoutes(Route route);

  void addTransitMode(TransitMode mode);

  <T extends TimetableSnapshotProvider> T getOrSetupTimetableSnapshotProvider(
    Function<TransitModel, T> creator
  );

  void setTransitLayer(TransitLayer transitLayer);
}
