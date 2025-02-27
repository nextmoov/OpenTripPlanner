package org.opentripplanner.ext.legacygraphqlapi.datafetchers;

import graphql.relay.Relay;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import java.text.ParseException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.opentripplanner.api.support.SemanticHash;
import org.opentripplanner.ext.legacygraphqlapi.LegacyGraphQLRequestContext;
import org.opentripplanner.ext.legacygraphqlapi.LegacyGraphQLUtils;
import org.opentripplanner.ext.legacygraphqlapi.generated.LegacyGraphQLDataFetchers;
import org.opentripplanner.ext.legacygraphqlapi.generated.LegacyGraphQLTypes;
import org.opentripplanner.ext.legacygraphqlapi.generated.LegacyGraphQLTypes.LegacyGraphQLWheelchairBoarding;
import org.opentripplanner.model.Timetable;
import org.opentripplanner.model.TripTimeOnDate;
import org.opentripplanner.routing.alertpatch.EntitySelector;
import org.opentripplanner.routing.alertpatch.TransitAlert;
import org.opentripplanner.routing.services.TransitAlertService;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.transit.model.network.Route;
import org.opentripplanner.transit.model.network.TripPattern;
import org.opentripplanner.transit.model.organization.Agency;
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.model.timetable.Direction;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.model.timetable.TripTimes;
import org.opentripplanner.transit.service.TransitService;
import org.opentripplanner.util.time.ServiceDateUtils;

public class LegacyGraphQLTripImpl implements LegacyGraphQLDataFetchers.LegacyGraphQLTrip {

  @Override
  public DataFetcher<Iterable<String>> activeDates() {
    return environment ->
      getTransitService(environment)
        .getCalendarService()
        .getServiceDatesForServiceId(getSource(environment).getServiceId())
        .stream()
        .map(ServiceDateUtils::asCompactString)
        .collect(Collectors.toList());
  }

  @Override
  public DataFetcher<Iterable<TransitAlert>> alerts() {
    return environment -> {
      TransitAlertService alertService = getTransitService(environment).getTransitAlertService();
      var args = new LegacyGraphQLTypes.LegacyGraphQLTripAlertsArgs(environment.getArguments());
      Iterable<LegacyGraphQLTypes.LegacyGraphQLTripAlertType> types = args.getLegacyGraphQLTypes();
      if (types != null) {
        Collection<TransitAlert> alerts = new ArrayList<>();
        types.forEach(type -> {
          switch (type) {
            case TRIP:
              alerts.addAll(alertService.getTripAlerts(getSource(environment).getId(), null));
              break;
            case AGENCY:
              alerts.addAll(alertService.getAgencyAlerts(getAgency(environment).getId()));
              break;
            case ROUTE_TYPE:
              int routeType = getRoute(environment).getGtfsType();
              alerts.addAll(
                alertService.getRouteTypeAlerts(
                  routeType,
                  getSource(environment).getId().getFeedId()
                )
              );
              alerts.addAll(
                alertService.getRouteTypeAndAgencyAlerts(routeType, getAgency(environment).getId())
              );
              break;
            case ROUTE:
              alerts.addAll(alertService.getRouteAlerts(getRoute(environment).getId()));
              break;
            case PATTERN:
              alerts.addAll(
                alertService.getDirectionAndRouteAlerts(
                  getSource(environment).getDirection(),
                  getRoute(environment).getId()
                )
              );
              break;
            case STOPS_ON_TRIP:
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
                          entity instanceof EntitySelector.StopAndRoute &&
                          ((EntitySelector.StopAndRoute) entity).stopAndRoute.routeOrTrip.equals(
                              getRoute(environment).getId()
                            )
                        ) ||
                        (
                          entity instanceof EntitySelector.StopAndTrip &&
                          ((EntitySelector.StopAndTrip) entity).stopAndTrip.routeOrTrip.equals(
                              getSource(environment).getId()
                            )
                        )
                      )
                  )
                  .collect(Collectors.toList())
              );
              getStops(environment)
                .forEach(stop -> {
                  FeedScopedId stopId = ((StopLocation) stop).getId();
                  alerts.addAll(alertService.getStopAlerts(stopId));
                });
              break;
          }
        });
        return alerts.stream().distinct().collect(Collectors.toList());
      } else {
        return alertService.getTripAlerts(getSource(environment).getId(), null);
      }
    };
  }

  @Override
  public DataFetcher<TripTimeOnDate> arrivalStoptime() {
    return environment -> {
      try {
        TransitService transitService = getTransitService(environment);
        TripPattern tripPattern = getTripPattern(environment);
        if (tripPattern == null) {
          return null;
        }
        Timetable timetable = tripPattern.getScheduledTimetable();

        TripTimes triptimes = timetable.getTripTimes(getSource(environment));
        LocalDate serviceDate = null;
        Instant midnight = null;

        var args = new LegacyGraphQLTypes.LegacyGraphQLTripArrivalStoptimeArgs(
          environment.getArguments()
        );
        if (args.getLegacyGraphQLServiceDate() != null) {
          serviceDate = ServiceDateUtils.parseString(args.getLegacyGraphQLServiceDate());
          midnight =
            ServiceDateUtils
              .asStartOfService(serviceDate, transitService.getTimeZone())
              .toInstant();
        }

        return new TripTimeOnDate(
          triptimes,
          triptimes.getNumStops() - 1,
          tripPattern,
          serviceDate,
          midnight
        );
      } catch (ParseException e) {
        //Invalid date format
        return null;
      }
    };
  }

  @Override
  public DataFetcher<String> bikesAllowed() {
    return environment ->
      switch (getSource(environment).getBikesAllowed()) {
        case UNKNOWN -> "NO_INFORMATION";
        case ALLOWED -> "POSSIBLE";
        case NOT_ALLOWED -> "NOT_POSSIBLE";
      };
  }

  @Override
  public DataFetcher<String> blockId() {
    return environment -> getSource(environment).getGtfsBlockId();
  }

  @Override
  public DataFetcher<TripTimeOnDate> departureStoptime() {
    return environment -> {
      try {
        TransitService transitService = getTransitService(environment);
        TripPattern tripPattern = getTripPattern(environment);
        if (tripPattern == null) {
          return null;
        }
        Timetable timetable = tripPattern.getScheduledTimetable();

        TripTimes triptimes = timetable.getTripTimes(getSource(environment));
        LocalDate serviceDate = null;
        Instant midnight = null;

        var args = new LegacyGraphQLTypes.LegacyGraphQLTripDepartureStoptimeArgs(
          environment.getArguments()
        );
        if (args.getLegacyGraphQLServiceDate() != null) {
          serviceDate = ServiceDateUtils.parseString(args.getLegacyGraphQLServiceDate());
          midnight =
            ServiceDateUtils
              .asStartOfService(serviceDate, transitService.getTimeZone())
              .toInstant();
        }

        return new TripTimeOnDate(triptimes, 0, tripPattern, serviceDate, midnight);
      } catch (ParseException e) {
        //Invalid date format
        return null;
      }
    };
  }

  @Override
  public DataFetcher<String> directionId() {
    return environment -> {
      Direction direction = getSource(environment).getDirection();
      if (direction == Direction.UNKNOWN) {
        return null;
      }
      return Integer.toString(direction.gtfsCode);
    };
  }

  @Override
  public DataFetcher<Iterable<Iterable<Double>>> geometry() {
    return environment -> {
      TripPattern tripPattern = getTripPattern(environment);
      if (tripPattern == null) {
        return null;
      }

      LineString geometry = tripPattern.getGeometry();
      if (geometry == null) {
        return null;
      }
      return Arrays
        .stream(geometry.getCoordinateSequence().toCoordinateArray())
        .map(coordinate -> Arrays.asList(coordinate.x, coordinate.y))
        .collect(Collectors.toList());
    };
  }

  @Override
  public DataFetcher<String> gtfsId() {
    return environment -> getSource(environment).getId().toString();
  }

  @Override
  public DataFetcher<Relay.ResolvedGlobalId> id() {
    return environment ->
      new Relay.ResolvedGlobalId("Trip", getSource(environment).getId().toString());
  }

  @Override
  public DataFetcher<TripPattern> pattern() {
    return this::getTripPattern;
  }

  @Override
  public DataFetcher<Route> route() {
    return environment -> getSource(environment).getRoute();
  }

  @Override
  public DataFetcher<String> routeShortName() {
    return environment -> {
      Trip trip = getSource(environment);
      return trip.getRoute().getShortName();
    };
  }

  @Override
  public DataFetcher<String> semanticHash() {
    return environment -> {
      TripPattern tripPattern = getTripPattern(environment);
      if (tripPattern == null) {
        return null;
      }
      return SemanticHash.forTripPattern(tripPattern, getSource(environment));
    };
  }

  @Override
  public DataFetcher<String> serviceId() {
    return environment -> getSource(environment).getServiceId().toString();
  }

  @Override
  public DataFetcher<String> shapeId() {
    return environment ->
      Optional
        .ofNullable(getSource(environment).getShapeId())
        .map(FeedScopedId::toString)
        .orElse(null);
  }

  @Override
  public DataFetcher<Iterable<Object>> stops() {
    return this::getStops;
  }

  @Override
  public DataFetcher<Iterable<TripTimeOnDate>> stoptimes() {
    return environment -> {
      TripPattern tripPattern = getTripPattern(environment);
      if (tripPattern == null) {
        return List.of();
      }
      return TripTimeOnDate.fromTripTimes(
        tripPattern.getScheduledTimetable(),
        getSource(environment)
      );
    };
  }

  @Override
  public DataFetcher<Iterable<TripTimeOnDate>> stoptimesForDate() {
    return environment -> {
      try {
        TransitService transitService = getTransitService(environment);
        Trip trip = getSource(environment);
        var args = new LegacyGraphQLTypes.LegacyGraphQLTripStoptimesForDateArgs(
          environment.getArguments()
        );

        ZoneId timeZone = transitService.getTimeZone();
        LocalDate serviceDate = args.getLegacyGraphQLServiceDate() != null
          ? ServiceDateUtils.parseString(args.getLegacyGraphQLServiceDate())
          : LocalDate.now(timeZone);

        TripPattern tripPattern = transitService.getRealtimeAddedTripPattern(
          trip.getId(),
          serviceDate
        );
        // timetableSnapshot is null or no realtime added pattern found
        if (tripPattern == null) {
          tripPattern = getTripPattern(environment);
        }
        // no matching pattern found anywhere
        if (tripPattern == null) {
          return List.of();
        }

        Instant midnight = ServiceDateUtils.asStartOfService(serviceDate, timeZone).toInstant();
        Timetable timetable = transitService.getTimetableForTripPattern(tripPattern, serviceDate);
        return TripTimeOnDate.fromTripTimes(timetable, trip, serviceDate, midnight);
      } catch (ParseException e) {
        return null; // Invalid date format
      }
    };
  }

  @Override
  public DataFetcher<Geometry> tripGeometry() {
    return environment -> {
      TripPattern tripPattern = getTripPattern(environment);
      if (tripPattern == null) {
        return null;
      }
      return tripPattern.getGeometry();
    };
  }

  @Override
  public DataFetcher<String> tripHeadsign() {
    return environment -> getSource(environment).getHeadsign();
  }

  @Override
  public DataFetcher<String> tripShortName() {
    return environment -> getSource(environment).getShortName();
  }

  @Override
  public DataFetcher<LegacyGraphQLWheelchairBoarding> wheelchairAccessible() {
    return environment ->
      LegacyGraphQLUtils.toGraphQL(getSource(environment).getWheelchairBoarding());
  }

  private List<Object> getStops(DataFetchingEnvironment environment) {
    TripPattern tripPattern = getTripPattern(environment);
    if (tripPattern == null) {
      return List.of();
    }
    return List.copyOf(tripPattern.getStops());
  }

  private Agency getAgency(DataFetchingEnvironment environment) {
    return getRoute(environment).getAgency();
  }

  private Route getRoute(DataFetchingEnvironment environment) {
    return getSource(environment).getRoute();
  }

  private TripPattern getTripPattern(DataFetchingEnvironment environment) {
    return getTransitService(environment).getPatternForTrip(environment.getSource());
  }

  private TransitService getTransitService(DataFetchingEnvironment environment) {
    return environment.<LegacyGraphQLRequestContext>getContext().getTransitService();
  }

  private Trip getSource(DataFetchingEnvironment environment) {
    return environment.getSource();
  }
}
