package org.opentripplanner.netex;

import java.util.List;
import org.opentripplanner.ext.flex.FlexTripsMapper;
import org.opentripplanner.graph_builder.DataImportIssueStore;
import org.opentripplanner.graph_builder.model.GraphBuilderModule;
import org.opentripplanner.graph_builder.module.AddTransitModelEntitiesToGraph;
import org.opentripplanner.graph_builder.module.GtfsFeedId;
import org.opentripplanner.model.OtpTransitService;
import org.opentripplanner.model.calendar.CalendarServiceData;
import org.opentripplanner.model.calendar.ServiceDateInterval;
import org.opentripplanner.model.impl.OtpTransitServiceBuilder;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.standalone.config.BuildConfig;
import org.opentripplanner.transit.service.TransitModel;
import org.opentripplanner.util.OTPFeature;

/**
 * This module is used for importing the NeTEx CEN Technical Standard for exchanging Public
 * Transport schedules and related data (<a href="http://netex-cen.eu/">http://netex-cen.eu/</a>).
 * Currently it only supports the Norwegian profile (<a href="https://enturas.atlassian.net/wiki/spaces/PUBLIC/">https://enturas.atlassian.net/wiki/spaces/PUBLIC/</a>),
 * but it is intended to be updated later to support other profiles.
 */
public class NetexModule implements GraphBuilderModule {

  private final int subwayAccessTime;
  private final String netexFeedId;

  private final Graph graph;
  private final TransitModel transitModel;
  private final DataImportIssueStore issueStore;

  /**
   * @see BuildConfig#transitServiceStart
   * @see BuildConfig#transitServiceEnd
   */
  private final ServiceDateInterval transitPeriodLimit;

  private final List<NetexBundle> netexBundles;

  public NetexModule(
    String netexFeedId,
    Graph graph,
    TransitModel transitModel,
    DataImportIssueStore issueStore,
    int subwayAccessTime,
    ServiceDateInterval transitPeriodLimit,
    List<NetexBundle> netexBundles
  ) {
    this.netexFeedId = netexFeedId;
    this.graph = graph;
    this.transitModel = transitModel;
    this.issueStore = issueStore;
    this.subwayAccessTime = subwayAccessTime;
    this.transitPeriodLimit = transitPeriodLimit;
    this.netexBundles = netexBundles;
  }

  @Override
  public void buildGraph() {
    try {
      var calendarServiceData = new CalendarServiceData();
      boolean hasActiveTransit = false;

      for (NetexBundle netexBundle : netexBundles) {
        netexBundle.checkInputs();

        OtpTransitServiceBuilder transitBuilder = netexBundle.loadBundle(
          graph.deduplicator,
          issueStore
        );
        transitBuilder.limitServiceDays(transitPeriodLimit);
        for (var tripOnServiceDate : transitBuilder.getTripOnServiceDates().values()) {
          transitModel.getTripOnServiceDates().put(tripOnServiceDate.getId(), tripOnServiceDate);
        }
        calendarServiceData.add(transitBuilder.buildCalendarServiceData());

        if (OTPFeature.FlexRouting.isOn()) {
          transitBuilder
            .getFlexTripsById()
            .addAll(FlexTripsMapper.createFlexTrips(transitBuilder, issueStore));
        }

        OtpTransitService otpService = transitBuilder.build();

        // if this or previously processed netex bundle has transit that has not been filtered out
        hasActiveTransit = hasActiveTransit || otpService.hasActiveTransit();

        // TODO OTP2 - Move this into the AddTransitModelEntitiesToGraph
        //           - and make sure they also work with GTFS feeds - GTFS do no
        //           - have operators and notice assignments.
        transitModel.getOperators().addAll(otpService.getAllOperators());
        transitModel.addNoticeAssignments(otpService.getNoticeAssignments());

        GtfsFeedId feedId = new GtfsFeedId.Builder().id(netexFeedId).build();

        AddTransitModelEntitiesToGraph.addToGraph(
          feedId,
          otpService,
          subwayAccessTime,
          graph,
          transitModel
        );

        transitModel.validateTimeZones();
      }

      transitModel.updateCalendarServiceData(hasActiveTransit, calendarServiceData, issueStore);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void checkInputs() {
    netexBundles.forEach(NetexBundle::checkInputs);
  }
}
