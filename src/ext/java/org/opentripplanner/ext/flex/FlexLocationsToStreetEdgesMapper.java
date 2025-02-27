package org.opentripplanner.ext.flex;

import java.util.HashSet;
import javax.inject.Inject;
import org.locationtech.jts.geom.Point;
import org.opentripplanner.graph_builder.model.GraphBuilderModule;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.graph.Vertex;
import org.opentripplanner.routing.impl.StreetVertexIndex;
import org.opentripplanner.routing.vertextype.StreetVertex;
import org.opentripplanner.transit.model.site.FlexStopLocation;
import org.opentripplanner.transit.service.TransitModel;
import org.opentripplanner.util.geometry.GeometryUtils;
import org.opentripplanner.util.logging.ProgressTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FlexLocationsToStreetEdgesMapper implements GraphBuilderModule {

  private static final Logger LOG = LoggerFactory.getLogger(FlexLocationsToStreetEdgesMapper.class);

  private final Graph graph;
  private final TransitModel transitModel;

  @Inject
  public FlexLocationsToStreetEdgesMapper(Graph graph, TransitModel transitModel) {
    this.graph = graph;
    this.transitModel = transitModel;
  }

  @Override
  public void buildGraph() {
    if (!transitModel.getStopModel().hasFlexLocations()) {
      return;
    }

    StreetVertexIndex streetIndex = graph.getStreetIndex();

    ProgressTracker progress = ProgressTracker.track(
      "Add flex locations to street vertices",
      1,
      transitModel.getStopModel().getAllFlexLocations().size()
    );

    LOG.info(progress.startMessage());
    // TODO: Make this into a parallel stream, first calculate vertices per location and then add them.
    for (FlexStopLocation flexStopLocation : transitModel.getStopModel().getAllFlexLocations()) {
      for (Vertex vertx : streetIndex.getVerticesForEnvelope(
        flexStopLocation.getGeometry().getEnvelopeInternal()
      )) {
        // Check that the vertex is connected to both driveable and walkable edges
        if (!(vertx instanceof StreetVertex)) {
          continue;
        }
        if (!((StreetVertex) vertx).isEligibleForCarPickupDropoff()) {
          continue;
        }

        // The street index overselects, so need to check for exact geometry inclusion
        Point p = GeometryUtils.getGeometryFactory().createPoint(vertx.getCoordinate());
        if (flexStopLocation.getGeometry().disjoint(p)) {
          continue;
        }

        StreetVertex streetVertex = (StreetVertex) vertx;

        if (streetVertex.flexStopLocations == null) {
          streetVertex.flexStopLocations = new HashSet<>();
        }

        streetVertex.flexStopLocations.add(flexStopLocation);
      }
      // Keep lambda! A method-ref would cause incorrect class and line number to be logged
      progress.step(m -> LOG.info(m));
    }
    LOG.info(progress.completeMessage());
  }

  @Override
  public void checkInputs() {
    // No inputs
  }
}
