package org.opentripplanner.routing.algorithm.raptoradapter.transit;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;
import org.opentripplanner.model.transfer.TransferService;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.constrainedtransfer.TransferIndexGenerator;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.mappers.TripPatternMapper;
import org.opentripplanner.routing.algorithm.raptoradapter.transit.request.RaptorRequestTransferCache;
import org.opentripplanner.routing.core.RoutingContext;
import org.opentripplanner.transit.model.site.Stop;
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.service.StopModelIndex;

public class TransitLayer {

  /**
   * Transit data required for routing, indexed by each local date(Graph TimeZone) it runs through.
   * A Trip "runs through" a date if any of its arrivals or departures is happening on that date.
   */
  private final HashMap<LocalDate, List<TripPatternForDate>> tripPatternsRunningOnDate;

  /**
   * Index of outer list is from stop index, inner list index has no specific meaning. To stop index
   * is a field of the Transfer object.
   */
  private final List<List<Transfer>> transfersByStopIndex;

  /**
   * Trip to trip transfers like with properties like guaranteedTransfer, staySeated and priority.
   */
  private final TransferService transferService;

  private final StopModelIndex stopIndex;

  private final ZoneId transitDataZoneId;

  private final RaptorRequestTransferCache transferCache;

  private final TripPatternMapper tripPatternMapper;

  private final TransferIndexGenerator transferIndexGenerator;

  private final int[] stopBoardAlightCosts;

  /**
   * Makes a shallow copy of the TransitLayer, except for the tripPatternsForDate, where a shallow
   * copy of the HashMap is made. This is sufficient, as the TransitLayerUpdater will replace entire
   * keys and their values in the map.
   */
  public TransitLayer(TransitLayer transitLayer) {
    this(
      transitLayer.tripPatternsRunningOnDate,
      transitLayer.transfersByStopIndex,
      transitLayer.transferService,
      transitLayer.stopIndex,
      transitLayer.transitDataZoneId,
      transitLayer.transferCache,
      transitLayer.tripPatternMapper,
      transitLayer.transferIndexGenerator,
      transitLayer.stopBoardAlightCosts
    );
  }

  public TransitLayer(
    Map<LocalDate, List<TripPatternForDate>> tripPatternsRunningOnDate,
    List<List<Transfer>> transfersByStopIndex,
    TransferService transferService,
    StopModelIndex stopIndex,
    ZoneId transitDataZoneId,
    RaptorRequestTransferCache transferCache,
    TripPatternMapper tripPatternMapper,
    TransferIndexGenerator transferIndexGenerator,
    int[] stopBoardAlightCosts
  ) {
    this.tripPatternsRunningOnDate = new HashMap<>(tripPatternsRunningOnDate);
    this.transfersByStopIndex = transfersByStopIndex;
    this.transferService = transferService;
    this.stopIndex = stopIndex;
    this.transitDataZoneId = transitDataZoneId;
    this.transferCache = transferCache;
    this.tripPatternMapper = tripPatternMapper;
    this.transferIndexGenerator = transferIndexGenerator;
    this.stopBoardAlightCosts = stopBoardAlightCosts;
  }

  public int getIndexByStop(Stop stop) {
    return stopIndex.indexOf(stop);
  }

  @Nullable
  public StopLocation getStopByIndex(int stop) {
    return stop == -1 ? null : this.stopIndex.stopByIndex(stop);
  }

  public StopModelIndex getStopIndex() {
    return this.stopIndex;
  }

  public Collection<TripPatternForDate> getTripPatternsForDate(LocalDate date) {
    return tripPatternsRunningOnDate.getOrDefault(date, List.of());
  }

  /**
   * This is the time zone which is used for interpreting all local "service" times (in transfers,
   * trip schedules and so on). This is the time zone of the internal OTP time - which is used in
   * logging and debugging. This is independent of the time zone of imported data and of the time
   * zone used on any API - it can be the same, but it does not need to.
   */
  public ZoneId getTransitDataZoneId() {
    return transitDataZoneId;
  }

  public int getStopCount() {
    return stopIndex.size();
  }

  public List<TripPatternForDate> getTripPatternsRunningOnDateCopy(LocalDate runningPeriodDate) {
    List<TripPatternForDate> tripPatternForDate = tripPatternsRunningOnDate.get(runningPeriodDate);
    return tripPatternForDate != null ? new ArrayList<>(tripPatternForDate) : new ArrayList<>();
  }

  public List<TripPatternForDate> getTripPatternsStartingOnDateCopy(LocalDate date) {
    List<TripPatternForDate> tripPatternsRunningOnDate = getTripPatternsRunningOnDateCopy(date);
    return tripPatternsRunningOnDate
      .stream()
      .filter(t -> t.getLocalDate().equals(date))
      .collect(Collectors.toList());
  }

  public TransferService getTransferService() {
    return transferService;
  }

  public RaptorTransferIndex getRaptorTransfersForRequest(RoutingContext routingContext) {
    return transferCache.get(transfersByStopIndex, routingContext);
  }

  public RaptorRequestTransferCache getTransferCache() {
    return transferCache;
  }

  public TripPatternMapper getTripPatternMapper() {
    return tripPatternMapper;
  }

  public TransferIndexGenerator getTransferIndexGenerator() {
    return transferIndexGenerator;
  }

  public int[] getStopBoardAlightCosts() {
    return stopBoardAlightCosts;
  }

  /**
   * Replaces all the TripPatternForDates for a single date. This is an atomic operation according
   * to the HashMap implementation.
   */
  public void replaceTripPatternsForDate(
    LocalDate date,
    List<TripPatternForDate> tripPatternForDates
  ) {
    this.tripPatternsRunningOnDate.replace(date, tripPatternForDates);
  }
}
