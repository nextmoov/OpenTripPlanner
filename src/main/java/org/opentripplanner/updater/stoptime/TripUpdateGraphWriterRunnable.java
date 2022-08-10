package org.opentripplanner.updater.stoptime;

import com.google.transit.realtime.GtfsRealtime.TripUpdate;
import java.util.List;
import java.util.Objects;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.transit.service.TransitModel;
import org.opentripplanner.updater.GraphWriterRunnable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class TripUpdateGraphWriterRunnable implements GraphWriterRunnable {

  private static final Logger LOG = LoggerFactory.getLogger(TripUpdateGraphWriterRunnable.class);

  /**
   * True iff the list with updates represent all updates that are active right now, i.e. all
   * previous updates should be disregarded
   */
  private final boolean fullDataset;

  /**
   * The list with updates to apply to the graph
   */
  private final List<TripUpdate> updates;

  private final String feedId;

  TripUpdateGraphWriterRunnable(
    final boolean fullDataset,
    final List<TripUpdate> updates,
    final String feedId
  ) {
    // Preconditions
    Objects.requireNonNull(updates);
    Objects.requireNonNull(feedId);

    // Set fields
    this.fullDataset = fullDataset;
    this.updates = updates;
    this.feedId = feedId;
  }

  @Override
  public void run(Graph graph, TransitModel transitModel) {
    // Apply updates to graph using realtime snapshot source. The source is retrieved from the graph using the
    // setup method which return the instance, we do not need to provide any creator because the
    // TimetableSnapshotSource should already be set up
    TimetableSnapshotSource snapshotSource = transitModel.getOrSetupTimetableSnapshotProvider(null);
    if (snapshotSource != null) {
      snapshotSource.applyTripUpdates(fullDataset, updates, feedId);
    } else {
      LOG.error(
        "Could not find realtime data snapshot source in graph." +
        " The following updates are not applied: {}",
        updates
      );
    }
  }
}
