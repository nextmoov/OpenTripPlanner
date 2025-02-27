package org.opentripplanner.ext.legacygraphqlapi.datafetchers;

import graphql.relay.Relay;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.opentripplanner.ext.legacygraphqlapi.LegacyGraphQLRequestContext;
import org.opentripplanner.ext.legacygraphqlapi.LegacyGraphQLUtils;
import org.opentripplanner.ext.legacygraphqlapi.generated.LegacyGraphQLDataFetchers;
import org.opentripplanner.ext.legacygraphqlapi.generated.LegacyGraphQLTypes;
import org.opentripplanner.ext.legacygraphqlapi.generated.LegacyGraphQLTypes.LegacyGraphQLStopAlertType;
import org.opentripplanner.ext.legacygraphqlapi.generated.LegacyGraphQLTypes.LegacyGraphQLWheelchairBoarding;
import org.opentripplanner.model.StopTimesInPattern;
import org.opentripplanner.model.TripTimeOnDate;
import org.opentripplanner.routing.RoutingService;
import org.opentripplanner.routing.alertpatch.EntitySelector;
import org.opentripplanner.routing.alertpatch.EntitySelector.StopAndRoute;
import org.opentripplanner.routing.alertpatch.TransitAlert;
import org.opentripplanner.routing.graphfinder.NearbyStop;
import org.opentripplanner.routing.services.TransitAlertService;
import org.opentripplanner.routing.stoptimes.ArrivalDeparture;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.transit.model.framework.TransitEntity;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.network.TripPattern;
import org.opentripplanner.transit.model.site.Station;
import org.opentripplanner.transit.model.site.Stop;
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.service.TransitService;
import org.opentripplanner.util.time.ServiceDateUtils;

public class LegacyGraphQLStopImpl implements LegacyGraphQLDataFetchers.LegacyGraphQLStop {

  @Override
  public DataFetcher<Iterable<TransitAlert>> alerts() {
    return environment -> {
      TransitAlertService alertService = getTransitService(environment).getTransitAlertService();
      var args = new LegacyGraphQLTypes.LegacyGraphQLStopAlertsArgs(environment.getArguments());
      List<LegacyGraphQLTypes.LegacyGraphQLStopAlertType> types = (List) args.getLegacyGraphQLTypes();
      FeedScopedId id = getValue(environment, StopLocation::getId, TransitEntity::getId);
      if (types != null) {
        Collection<TransitAlert> alerts = new ArrayList<>();
        if (types.contains(LegacyGraphQLStopAlertType.STOP)) {
          alerts.addAll(alertService.getStopAlerts(id));
        }
        if (
          types.contains(LegacyGraphQLStopAlertType.STOP_ON_ROUTES) ||
          types.contains(LegacyGraphQLStopAlertType.STOP_ON_TRIPS)
        ) {
          alerts.addAll(
            alertService
              .getAllAlerts()
              .stream()
              .filter(alert ->
                alert
                  .getEntities()
                  .stream()
                  .anyMatch(entity ->
                    (
                      types.contains(LegacyGraphQLStopAlertType.STOP_ON_ROUTES) &&
                      entity instanceof EntitySelector.StopAndRoute &&
                      ((StopAndRoute) entity).stopAndRoute.stop.equals(id)
                    ) ||
                    (
                      types.contains(LegacyGraphQLStopAlertType.STOP_ON_TRIPS) &&
                      entity instanceof EntitySelector.StopAndTrip &&
                      ((EntitySelector.StopAndTrip) entity).stopAndTrip.stop.equals(id)
                    )
                  )
              )
              .collect(Collectors.toList())
          );
        }
        if (
          types.contains(LegacyGraphQLStopAlertType.PATTERNS) ||
          types.contains(LegacyGraphQLStopAlertType.TRIPS)
        ) {
          getPatterns(environment)
            .forEach(pattern -> {
              if (types.contains(LegacyGraphQLStopAlertType.PATTERNS)) {
                alerts.addAll(
                  alertService.getDirectionAndRouteAlerts(
                    pattern.getDirection(),
                    pattern.getRoute().getId()
                  )
                );
              }
              if (types.contains(LegacyGraphQLStopAlertType.TRIPS)) {
                pattern
                  .scheduledTripsAsStream()
                  .forEach(trip -> alerts.addAll(alertService.getTripAlerts(trip.getId(), null)));
              }
            });
        }
        if (
          types.contains(LegacyGraphQLStopAlertType.ROUTES) ||
          types.contains(LegacyGraphQLStopAlertType.AGENCIES_OF_ROUTES)
        ) {
          getRoutes(environment)
            .forEach(route -> {
              if (types.contains(LegacyGraphQLStopAlertType.ROUTES)) {
                alerts.addAll(alertService.getRouteAlerts(route.getId()));
              }
              if (types.contains(LegacyGraphQLStopAlertType.AGENCIES_OF_ROUTES)) {
                alerts.addAll(alertService.getAgencyAlerts(route.getAgency().getId()));
              }
            });
        }
        return alerts.stream().distinct().collect(Collectors.toList());
      } else {
        return alertService.getStopAlerts(id);
      }
    };
  }

  @Override
  public DataFetcher<Object> cluster() {
    return environment -> null;
  }

  @Override
  public DataFetcher<String> code() {
    return environment -> getValue(environment, StopLocation::getCode, Station::getCode);
  }

  @Override
  public DataFetcher<String> desc() {
    return environment ->
      getValue(
        environment,
        stop -> LegacyGraphQLUtils.getTranslation(stop.getDescription(), environment),
        station -> LegacyGraphQLUtils.getTranslation(station.getDescription(), environment)
      );
  }

  @Override
  public DataFetcher<String> url() {
    return environment ->
      getValue(
        environment,
        stop -> LegacyGraphQLUtils.getTranslation(stop.getUrl(), environment),
        station -> LegacyGraphQLUtils.getTranslation(station.getUrl(), environment)
      );
  }

  @Override
  public DataFetcher<Object> locationType() {
    return environment -> getValue(environment, stop -> "STOP", station -> "STATION");
  }

  @Override
  public DataFetcher<Object> parentStation() {
    return environment -> getValue(environment, StopLocation::getParentStation, station -> null);
  }

  // TODO
  @Override
  public DataFetcher<String> direction() {
    return environment -> null;
  }

  @Override
  public DataFetcher<Object> geometries() {
    return environment -> getValue(environment, StopLocation::getGeometry, Station::getGeometry);
  }

  @Override
  public DataFetcher<String> gtfsId() {
    return environment ->
      getValue(environment, stop -> stop.getId().toString(), station -> station.getId().toString());
  }

  @Override
  public DataFetcher<Relay.ResolvedGlobalId> id() {
    return environment ->
      getValue(
        environment,
        stop -> new Relay.ResolvedGlobalId("Stop", stop.getId().toString()),
        station -> new Relay.ResolvedGlobalId("Stop", station.getId().toString())
      );
  }

  @Override
  public DataFetcher<Double> lat() {
    return environment -> getValue(environment, StopLocation::getLat, Station::getLat);
  }

  @Override
  public DataFetcher<Double> lon() {
    return environment -> getValue(environment, StopLocation::getLon, Station::getLon);
  }

  @Override
  public DataFetcher<String> name() {
    return environment ->
      getValue(
        environment,
        stop -> LegacyGraphQLUtils.getTranslation(stop.getName(), environment),
        station -> LegacyGraphQLUtils.getTranslation(station.getName(), environment)
      );
  }

  @Override
  public DataFetcher<Iterable<TripPattern>> patterns() {
    return this::getPatterns;
  }

  @Override
  public DataFetcher<String> platformCode() {
    return environment -> getValue(environment, StopLocation::getPlatformCode, station -> null);
  }

  @Override
  public DataFetcher<Iterable<Route>> routes() {
    return this::getRoutes;
  }

  @Override
  public DataFetcher<Iterable<TripTimeOnDate>> stopTimesForPattern() {
    return environment ->
      getValue(
        environment,
        stop -> {
          TransitService transitService = getTransitService(environment);
          LegacyGraphQLTypes.LegacyGraphQLStopStopTimesForPatternArgs args = new LegacyGraphQLTypes.LegacyGraphQLStopStopTimesForPatternArgs(
            environment.getArguments()
          );
          TripPattern pattern = transitService.getTripPatternForId(
            FeedScopedId.parseId(args.getLegacyGraphQLId())
          );

          if (pattern == null) {
            return null;
          }

          if (transitService.hasRealtimeAddedTripPatterns()) {
            return getTripTimeOnDatesForPatternAtStopIncludingTripsWithSkippedStops(
              pattern,
              stop,
              transitService,
              args
            );
          }

          // TODO: use args.getLegacyGraphQLOmitCanceled()

          return transitService.stopTimesForPatternAtStop(
            stop,
            pattern,
            LegacyGraphQLUtils.getTimeOrNow(args.getLegacyGraphQLStartTime()),
            Duration.ofSeconds(args.getLegacyGraphQLTimeRange()),
            args.getLegacyGraphQLNumberOfDepartures(),
            args.getLegacyGraphQLOmitNonPickups()
              ? ArrivalDeparture.DEPARTURES
              : ArrivalDeparture.BOTH
          );
        },
        station -> null
      );
  }

  @Override
  public DataFetcher<Iterable<Object>> stops() {
    return environment ->
      getValue(
        environment,
        stop -> null,
        station -> new ArrayList<Object>(station.getChildStops())
      );
  }

  @Override
  public DataFetcher<Iterable<StopTimesInPattern>> stoptimesForPatterns() {
    return environment -> {
      TransitService transitService = getTransitService(environment);
      var args = new LegacyGraphQLTypes.LegacyGraphQLStopStoptimesForPatternsArgs(
        environment.getArguments()
      );

      // TODO: use args.getLegacyGraphQLOmitCanceled()

      Function<StopLocation, List<StopTimesInPattern>> stopTFunction = stop ->
        transitService.stopTimesForStop(
          stop,
          LegacyGraphQLUtils.getTimeOrNow(args.getLegacyGraphQLStartTime()),
          Duration.ofSeconds(args.getLegacyGraphQLTimeRange()),
          args.getLegacyGraphQLNumberOfDepartures(),
          args.getLegacyGraphQLOmitNonPickups()
            ? ArrivalDeparture.DEPARTURES
            : ArrivalDeparture.BOTH,
          false
        );

      return getValue(
        environment,
        stopTFunction,
        station ->
          station
            .getChildStops()
            .stream()
            .map(stopTFunction)
            .flatMap(Collection::stream)
            .collect(Collectors.toList())
      );
    };
  }

  @Override
  public DataFetcher<Iterable<StopTimesInPattern>> stoptimesForServiceDate() {
    return environment -> {
      TransitService transitService = getTransitService(environment);
      var args = new LegacyGraphQLTypes.LegacyGraphQLStopStoptimesForServiceDateArgs(
        environment.getArguments()
      );
      LocalDate date;
      try {
        date = ServiceDateUtils.parseString(args.getLegacyGraphQLDate());
      } catch (ParseException e) {
        return null;
      }

      // TODO: use args.getLegacyGraphQLOmitCanceled()

      Function<StopLocation, List<StopTimesInPattern>> stopTFunction = stop ->
        transitService.getStopTimesForStop(
          stop,
          date,
          args.getLegacyGraphQLOmitNonPickups()
            ? ArrivalDeparture.DEPARTURES
            : ArrivalDeparture.BOTH
        );

      return getValue(
        environment,
        stopTFunction,
        station ->
          station
            .getChildStops()
            .stream()
            .map(stopTFunction)
            .flatMap(Collection::stream)
            .collect(Collectors.toList())
      );
    };
  }

  @Override
  public DataFetcher<Iterable<TripTimeOnDate>> stoptimesWithoutPatterns() {
    return environment -> {
      TransitService transitService = getTransitService(environment);
      var args = new LegacyGraphQLTypes.LegacyGraphQLStopStoptimesForPatternsArgs(
        environment.getArguments()
      );

      // TODO: use args.getLegacyGraphQLOmitCanceled()

      Function<StopLocation, Stream<StopTimesInPattern>> stopTFunction = stop ->
        transitService
          .stopTimesForStop(
            stop,
            LegacyGraphQLUtils.getTimeOrNow(args.getLegacyGraphQLStartTime()),
            Duration.ofSeconds(args.getLegacyGraphQLTimeRange()),
            args.getLegacyGraphQLNumberOfDepartures(),
            args.getLegacyGraphQLOmitNonPickups()
              ? ArrivalDeparture.DEPARTURES
              : ArrivalDeparture.BOTH,
            false
          )
          .stream();

      Stream<StopTimesInPattern> stream = getValue(
        environment,
        stopTFunction,
        station -> station.getChildStops().stream().flatMap(stopTFunction)
      );

      return stream
        .flatMap(stoptimesWithPattern -> stoptimesWithPattern.times.stream())
        .sorted(Comparator.comparing(t -> t.getServiceDayMidnight() + t.getRealtimeDeparture()))
        .limit(args.getLegacyGraphQLNumberOfDepartures())
        .collect(Collectors.toList());
    };
  }

  @Override
  public DataFetcher<String> timezone() {
    return environment ->
      getValue(
        environment,
        stop -> stop.getTimeZone().toString(),
        station -> station.getTimezone().toString()
      );
  }

  @Override
  public DataFetcher<Iterable<NearbyStop>> transfers() {
    return environment ->
      getValue(
        environment,
        stop -> {
          Integer maxDistance = new LegacyGraphQLTypes.LegacyGraphQLStopTransfersArgs(
            environment.getArguments()
          )
            .getLegacyGraphQLMaxDistance();

          return getTransitService(environment)
            .getTransfersByStop(stop)
            .stream()
            .filter(transfer -> maxDistance == null || transfer.getDistanceMeters() < maxDistance)
            .filter(transfer -> transfer.to instanceof Stop)
            .map(transfer ->
              new NearbyStop(transfer.to, transfer.getDistanceMeters(), transfer.getEdges(), null)
            )
            .collect(Collectors.toList());
        },
        station -> null
      );
  }

  @Override
  public DataFetcher<String> vehicleMode() {
    return environment ->
      getValue(
        environment,
        stop -> {
          if (stop.getGtfsVehicleType() != null) {
            return stop.getGtfsVehicleType().name();
          }
          return getTransitService(environment)
            .getPatternsForStop(stop)
            .stream()
            .map(TripPattern::getMode)
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
            .entrySet()
            .stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .map(Enum::toString)
            .orElse(null);
        },
        station -> {
          TransitService transitService = getTransitService(environment);
          return station
            .getChildStops()
            .stream()
            .flatMap(stop ->
              transitService.getPatternsForStop(stop).stream().map(TripPattern::getMode)
            )
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()))
            .entrySet()
            .stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .map(Enum::toString)
            .orElse(null);
        }
      );
  }

  // TODO
  @Override
  public DataFetcher<Integer> vehicleType() {
    return environment -> null;
  }

  @Override
  public DataFetcher<LegacyGraphQLWheelchairBoarding> wheelchairBoarding() {
    return environment -> {
      var boarding = getValue(
        environment,
        StopLocation::getWheelchairAccessibility,
        station -> null
      );
      return LegacyGraphQLUtils.toGraphQL(boarding);
    };
  }

  @Override
  public DataFetcher<String> zoneId() {
    return environment ->
      getValue(environment, StopLocation::getFirstZoneAsString, station -> null);
  }

  private Collection<TripPattern> getPatterns(DataFetchingEnvironment environment) {
    return getValue(
      environment,
      stop -> getTransitService(environment).getPatternsForStop(stop, true),
      station -> null
    );
  }

  private Collection<Route> getRoutes(DataFetchingEnvironment environment) {
    return getValue(
      environment,
      stop -> getTransitService(environment).getRoutesForStop(stop),
      station -> null
    );
  }

  private RoutingService getRoutingService(DataFetchingEnvironment environment) {
    return environment.<LegacyGraphQLRequestContext>getContext().getRoutingService();
  }

  private TransitService getTransitService(DataFetchingEnvironment environment) {
    return environment.<LegacyGraphQLRequestContext>getContext().getTransitService();
  }

  /**
   * TODO this functionality should be supported by {@link org.opentripplanner.routing.stoptimes.StopTimesHelper#stopTimesForPatternAtStop}
   */
  private List<TripTimeOnDate> getTripTimeOnDatesForPatternAtStopIncludingTripsWithSkippedStops(
    TripPattern originalPattern,
    StopLocation stop,
    TransitService transitService,
    LegacyGraphQLTypes.LegacyGraphQLStopStopTimesForPatternArgs args
  ) {
    Instant startTime = LegacyGraphQLUtils.getTimeOrNow(args.getLegacyGraphQLStartTime());
    LocalDate date = startTime.atZone(transitService.getTimeZone()).toLocalDate();

    return Stream
      .concat(
        getRealtimeAddedPatternsAsStream(originalPattern, transitService, date),
        Stream.of(originalPattern)
      )
      .flatMap(tripPattern ->
        transitService
          .stopTimesForPatternAtStop(
            stop,
            tripPattern,
            startTime,
            Duration.ofSeconds(args.getLegacyGraphQLTimeRange()),
            args.getLegacyGraphQLNumberOfDepartures(),
            args.getLegacyGraphQLOmitNonPickups()
              ? ArrivalDeparture.DEPARTURES
              : ArrivalDeparture.BOTH
          )
          .stream()
      )
      .sorted(
        Comparator.comparing((TripTimeOnDate tts) ->
          tts.getServiceDayMidnight() + tts.getRealtimeDeparture()
        )
      )
      .limit(args.getLegacyGraphQLNumberOfDepartures())
      .toList();
  }

  /**
   * Get a stream of {@link TripPattern} that were created realtime based of the provided pattern.
   * Only patterns that don't have removed (stops can still be skipped) or added stops are included.
   */
  private Stream<TripPattern> getRealtimeAddedPatternsAsStream(
    TripPattern originalPattern,
    TransitService transitService,
    LocalDate date
  ) {
    return originalPattern
      .scheduledTripsAsStream()
      .map(trip -> transitService.getRealtimeAddedTripPattern(trip.getId(), date))
      .filter(tripPattern ->
        tripPattern != null && tripPattern.isModifiedFromTripPatternWithEqualStops(originalPattern)
      );
  }

  private <T> T getValue(
    DataFetchingEnvironment environment,
    Function<StopLocation, T> stopTFunction,
    Function<Station, T> stationTFunction
  ) {
    Object source = environment.getSource();
    if (source instanceof StopLocation) {
      return stopTFunction.apply((StopLocation) source);
    } else if (source instanceof Station) {
      return stationTFunction.apply((Station) source);
    }
    return null;
  }
}
