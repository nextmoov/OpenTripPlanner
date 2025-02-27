package org.opentripplanner.ext.transmodelapi.model.stop;

import static java.lang.Boolean.TRUE;
import static org.opentripplanner.ext.transmodelapi.model.EnumTypes.TRANSPORT_MODE;
import static org.opentripplanner.ext.transmodelapi.model.EnumTypes.TRANSPORT_SUBMODE;

import graphql.Scalars;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLArgument;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLInterfaceType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLTypeReference;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.opentripplanner.ext.transmodelapi.TransmodelGraphQLUtils;
import org.opentripplanner.ext.transmodelapi.model.EnumTypes;
import org.opentripplanner.ext.transmodelapi.model.TransmodelTransportSubmode;
import org.opentripplanner.ext.transmodelapi.model.plan.JourneyWhiteListed;
import org.opentripplanner.ext.transmodelapi.support.GqlUtil;
import org.opentripplanner.model.StopTimesInPattern;
import org.opentripplanner.model.TripTimeOnDate;
import org.opentripplanner.routing.stoptimes.ArrivalDeparture;
import org.opentripplanner.transit.model.basic.I18NString;
import org.opentripplanner.transit.model.basic.SubMode;
import org.opentripplanner.transit.model.basic.TransitMode;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.transit.model.site.MultiModalStation;
import org.opentripplanner.transit.model.site.Station;
import org.opentripplanner.transit.model.site.StopCollection;
import org.opentripplanner.transit.model.site.StopLocation;
import org.opentripplanner.transit.model.timetable.Trip;
import org.opentripplanner.transit.service.TransitService;

public class StopPlaceType {

  public static final String NAME = "StopPlace";
  public static final GraphQLOutputType REF = new GraphQLTypeReference(NAME);

  public static GraphQLObjectType create(
    GraphQLInterfaceType placeInterface,
    GraphQLOutputType quayType,
    GraphQLOutputType tariffZoneType,
    GraphQLOutputType estimatedCallType,
    GqlUtil gqlUtil
  ) {
    return GraphQLObjectType
      .newObject()
      .name(NAME)
      .description(
        "Named place where public transport may be accessed. May be a building complex (e.g. a station) or an on-street location."
      )
      .withInterface(placeInterface)
      .field(GqlUtil.newTransitIdField())
      .field(
        GraphQLFieldDefinition
          .newFieldDefinition()
          .name("name")
          .type(new GraphQLNonNull(Scalars.GraphQLString))
          .argument(
            GraphQLArgument
              .newArgument()
              .name("lang")
              .description(
                "Fetch the name in the language given. The language should be represented as a ISO-639 language code. If the translation does not exits, the default name is returned."
              )
              .type(Scalars.GraphQLString)
              .build()
          )
          .dataFetcher(environment -> {
            String lang = environment.getArgument("lang");
            Locale locale = lang != null ? new Locale(lang) : null;
            return (((MonoOrMultiModalStation) environment.getSource()).getName().toString(locale));
          })
          .build()
      )
      .field(
        GraphQLFieldDefinition
          .newFieldDefinition()
          .name("latitude")
          .type(Scalars.GraphQLFloat)
          .dataFetcher(environment -> (((MonoOrMultiModalStation) environment.getSource()).getLat())
          )
          .build()
      )
      .field(
        GraphQLFieldDefinition
          .newFieldDefinition()
          .name("longitude")
          .type(Scalars.GraphQLFloat)
          .dataFetcher(environment -> (((MonoOrMultiModalStation) environment.getSource()).getLon())
          )
          .build()
      )
      .field(
        GraphQLFieldDefinition
          .newFieldDefinition()
          .name("description")
          .type(Scalars.GraphQLString)
          .dataFetcher(environment -> {
            I18NString description =
              ((MonoOrMultiModalStation) environment.getSource()).getDescription();
            if (description != null) {
              Locale locale = TransmodelGraphQLUtils.getLocale(environment);
              return (description.toString(locale));
            }
            return null;
          })
          .build()
      )
      .field(
        GraphQLFieldDefinition
          .newFieldDefinition()
          .name("weighting")
          .description(
            "Relative weighting of this stop with regards to interchanges. NOT IMPLEMENTED"
          )
          .type(EnumTypes.INTERCHANGE_WEIGHTING)
          .dataFetcher(environment -> 0)
          .build()
      )
      .field(
        GraphQLFieldDefinition
          .newFieldDefinition()
          .name("tariffZones")
          .type(new GraphQLNonNull(new GraphQLList(tariffZoneType)))
          .description("NOT IMPLEMENTED")
          .dataFetcher(environment -> List.of())
          .build()
      )
      .field(
        GraphQLFieldDefinition
          .newFieldDefinition()
          .name("transportMode")
          .description("The transport modes of quays under this stop place.")
          .type(new GraphQLList(TRANSPORT_MODE))
          .dataFetcher(environment ->
            ((MonoOrMultiModalStation) environment.getSource()).getChildStops()
              .stream()
              .map(StopLocation::getGtfsVehicleType)
              .filter(Objects::nonNull)
              .collect(Collectors.toSet())
          )
          .build()
      )
      .field(
        GraphQLFieldDefinition
          .newFieldDefinition()
          .name("transportSubmode")
          .description("The transport submode serviced by this stop place.")
          .type(new GraphQLList(TRANSPORT_SUBMODE))
          .dataFetcher(environment ->
            ((MonoOrMultiModalStation) environment.getSource()).getChildStops()
              .stream()
              .map(StopLocation::getNetexVehicleSubmode)
              .filter(it -> it != SubMode.UNKNOWN)
              .map(TransmodelTransportSubmode::fromValue)
              .collect(Collectors.toList())
          )
          .build()
      )
      //                .field(GraphQLFieldDefinition.newFieldDefinition()
      //                        .name("adjacentSites")
      //                        .description("This stop place's adjacent sites")
      //                        .type(new GraphQLList(Scalars.GraphQLString))
      //                        .dataFetcher(environment -> ((MonoOrMultiModalStation) environment.getSource()).getAdjacentSites())
      //                        .build())
      // TODO stopPlaceType?

      .field(
        GraphQLFieldDefinition
          .newFieldDefinition()
          .name("quays")
          .withDirective(gqlUtil.timingData)
          .description("Returns all quays that are children of this stop place")
          .type(new GraphQLList(quayType))
          .argument(
            GraphQLArgument
              .newArgument()
              .name("filterByInUse")
              .description("If true only quays with at least one visiting line are included.")
              .type(Scalars.GraphQLBoolean)
              .defaultValue(Boolean.FALSE)
              .build()
          )
          .dataFetcher(environment -> {
            var quays = ((MonoOrMultiModalStation) environment.getSource()).getChildStops();
            if (TRUE.equals(environment.getArgument("filterByInUse"))) {
              quays =
                quays
                  .stream()
                  .filter(stop -> {
                    return !GqlUtil
                      .getTransitService(environment)
                      .getPatternsForStop(stop, true)
                      .isEmpty();
                  })
                  .collect(Collectors.toList());
            }
            return quays;
          })
          .build()
      )
      .field(
        GraphQLFieldDefinition
          .newFieldDefinition()
          .name("parent")
          .description("Returns parent stop for this stop")
          .type(new GraphQLTypeReference(NAME))
          .dataFetcher(environment ->
            (((MonoOrMultiModalStation) environment.getSource()).getParentStation())
          )
          .build()
      )
      .field(
        GraphQLFieldDefinition
          .newFieldDefinition()
          .name("tariffZones")
          .type(new GraphQLNonNull(new GraphQLList(tariffZoneType)))
          .dataFetcher(environment ->
            ((MonoOrMultiModalStation) environment.getSource()).getChildStops()
              .stream()
              .flatMap(s -> s.getFareZones().stream())
              .collect(Collectors.toSet())
          )
          .build()
      )
      .field(
        GraphQLFieldDefinition
          .newFieldDefinition()
          .name("estimatedCalls")
          .withDirective(gqlUtil.timingData)
          .description("List of visits to this stop place as part of vehicle journeys.")
          .type(new GraphQLNonNull(new GraphQLList(new GraphQLNonNull(estimatedCallType))))
          .argument(
            GraphQLArgument
              .newArgument()
              .name("startTime")
              .type(gqlUtil.dateTimeScalar)
              .description(
                "DateTime for when to fetch estimated calls from. Default value is current time"
              )
              .build()
          )
          .argument(
            GraphQLArgument
              .newArgument()
              .name("timeRange")
              .type(Scalars.GraphQLInt)
              .defaultValue(24 * 60 * 60)
              .build()
          )
          .argument(
            GraphQLArgument
              .newArgument()
              .name("numberOfDepartures")
              .description("Limit the total number of departures returned.")
              .type(Scalars.GraphQLInt)
              .defaultValue(5)
              .build()
          )
          .argument(
            GraphQLArgument
              .newArgument()
              .name("numberOfDeparturesPerLineAndDestinationDisplay")
              .description(
                "Limit the number of departures per line and destination display returned. The parameter is only applied " +
                "when the value is between 1 and 'numberOfDepartures'."
              )
              .type(Scalars.GraphQLInt)
              .build()
          )
          .argument(
            GraphQLArgument
              .newArgument()
              .name("arrivalDeparture")
              .type(EnumTypes.ARRIVAL_DEPARTURE)
              .defaultValue(ArrivalDeparture.DEPARTURES)
              .build()
          )
          .argument(
            GraphQLArgument
              .newArgument()
              .name("whiteListed")
              .description("Whitelisted")
              .description(
                "Parameters for indicating the only authorities and/or lines or quays to list estimatedCalls for"
              )
              .type(JourneyWhiteListed.INPUT_TYPE)
              .build()
          )
          .argument(
            GraphQLArgument
              .newArgument()
              .name("whiteListedModes")
              .description("Only show estimated calls for selected modes.")
              .type(GraphQLList.list(TRANSPORT_MODE))
              .build()
          )
          .argument(
            GraphQLArgument
              .newArgument()
              .name("includeCancelledTrips")
              .description(
                "Indicates that realtime-cancelled trips should also be included. NOT IMPLEMENTED"
              )
              .type(Scalars.GraphQLBoolean)
              .defaultValue(false)
              .build()
          )
          .dataFetcher(environment -> {
            ArrivalDeparture arrivalDeparture = environment.getArgument("arrivalDeparture");
            boolean includeCancelledTrips = environment.getArgument("includeCancelledTrips");
            int numberOfDepartures = environment.getArgument("numberOfDepartures");
            Integer departuresPerLineAndDestinationDisplay = environment.getArgument(
              "numberOfDeparturesPerLineAndDestinationDisplay"
            );
            Duration timeRage = Duration.ofSeconds(environment.getArgument("timeRange"));

            MonoOrMultiModalStation monoOrMultiModalStation = environment.getSource();
            JourneyWhiteListed whiteListed = new JourneyWhiteListed(environment);
            Collection<TransitMode> transitModes = environment.getArgument("whiteListedModes");

            Instant startTime = environment.containsArgument("startTime")
              ? Instant.ofEpochMilli(environment.getArgument("startTime"))
              : Instant.now();

            return monoOrMultiModalStation
              .getChildStops()
              .stream()
              .flatMap(singleStop ->
                getTripTimesForStop(
                  singleStop,
                  startTime,
                  timeRage,
                  arrivalDeparture,
                  includeCancelledTrips,
                  numberOfDepartures,
                  departuresPerLineAndDestinationDisplay,
                  whiteListed.authorityIds,
                  whiteListed.lineIds,
                  transitModes,
                  environment
                )
              )
              .sorted(TripTimeOnDate.compareByDeparture())
              .distinct()
              .limit(numberOfDepartures)
              .collect(Collectors.toList());
          })
          .build()
      )
      .build();
  }

  public static Stream<TripTimeOnDate> getTripTimesForStop(
    StopLocation stop,
    Instant startTimeSeconds,
    Duration timeRage,
    ArrivalDeparture arrivalDeparture,
    boolean includeCancelledTrips,
    int numberOfDepartures,
    Integer departuresPerLineAndDestinationDisplay,
    Collection<FeedScopedId> authorityIdsWhiteListed,
    Collection<FeedScopedId> lineIdsWhiteListed,
    Collection<TransitMode> transitModes,
    DataFetchingEnvironment environment
  ) {
    TransitService transitService = GqlUtil.getTransitService(environment);
    boolean limitOnDestinationDisplay =
      departuresPerLineAndDestinationDisplay != null &&
      departuresPerLineAndDestinationDisplay > 0 &&
      departuresPerLineAndDestinationDisplay < numberOfDepartures;

    List<StopTimesInPattern> stopTimesInPatterns = transitService.stopTimesForStop(
      stop,
      startTimeSeconds,
      timeRage,
      numberOfDepartures,
      arrivalDeparture,
      includeCancelledTrips
    );

    Stream<StopTimesInPattern> stopTimesStream = stopTimesInPatterns.stream();

    if (transitModes != null && !transitModes.isEmpty()) {
      stopTimesStream = stopTimesStream.filter(it -> transitModes.contains(it.pattern.getMode()));
    }

    Stream<TripTimeOnDate> tripTimesStream = stopTimesStream.flatMap(p -> p.times.stream());

    tripTimesStream =
      JourneyWhiteListed.whiteListAuthoritiesAndOrLines(
        tripTimesStream,
        authorityIdsWhiteListed,
        lineIdsWhiteListed
      );

    if (!limitOnDestinationDisplay) {
      return tripTimesStream;
    }
    // Group by line and destination display, limit departures per group and merge
    return tripTimesStream
      .collect(Collectors.groupingBy(t -> destinationDisplayPerLine(((TripTimeOnDate) t))))
      .values()
      .stream()
      .flatMap(tripTimes ->
        tripTimes
          .stream()
          .sorted(TripTimeOnDate.compareByDeparture())
          .distinct()
          .limit(departuresPerLineAndDestinationDisplay)
      );
  }

  public static MonoOrMultiModalStation fetchStopPlaceById(
    FeedScopedId id,
    DataFetchingEnvironment environment
  ) {
    TransitService transitService = GqlUtil.getTransitService(environment);

    Station station = transitService.getStationById(id);

    if (station != null) {
      return new MonoOrMultiModalStation(
        station,
        transitService.getMultiModalStationForStation(station)
      );
    }

    MultiModalStation multiModalStation = transitService.getMultiModalStation(id);

    if (multiModalStation != null) {
      return new MonoOrMultiModalStation(multiModalStation);
    }
    return null;
  }

  public static Collection<MonoOrMultiModalStation> fetchStopPlaces(
    double minLat,
    double minLon,
    double maxLat,
    double maxLon,
    String authority,
    Boolean filterByInUse,
    String multiModalMode,
    DataFetchingEnvironment environment
  ) {
    final TransitService transitService = GqlUtil.getTransitService(environment);

    Envelope envelope = new Envelope(
      new Coordinate(minLon, minLat),
      new Coordinate(maxLon, maxLat)
    );

    Stream<Station> stations = transitService
      .queryStopSpatialIndex(envelope)
      .stream()
      .filter(stop -> envelope.contains(stop.getCoordinate().asJtsCoordinate()))
      .map(StopLocation::getParentStation)
      .filter(Objects::nonNull)
      .distinct();

    if (authority != null) {
      stations = stations.filter(s -> s.getId().getFeedId().equalsIgnoreCase(authority));
    }

    if (TRUE.equals(filterByInUse)) {
      stations = stations.filter(s -> isStopPlaceInUse(s, transitService));
    }

    // "child" - Only mono modal children stop places, not their multi modal parent stop
    if ("child".equals(multiModalMode)) {
      return stations
        .map(s -> {
          MultiModalStation parent = transitService.getMultiModalStationForStation(s);
          return new MonoOrMultiModalStation(s, parent);
        })
        .collect(Collectors.toList());
    }
    // "all" - Both multiModal parents and their mono modal child stop places
    else if ("all".equals(multiModalMode)) {
      Set<MonoOrMultiModalStation> result = new HashSet<>();
      stations.forEach(it -> {
        MultiModalStation p = transitService.getMultiModalStationForStation(it);
        result.add(new MonoOrMultiModalStation(it, p));
        if (p != null) {
          result.add(new MonoOrMultiModalStation(p));
        }
      });
      return result;
    }
    // Default "parent" - Multi modal parent stop places without their mono modal children, but add
    // mono modal stop places if they have no parent stop place
    else if ("parent".equals(multiModalMode)) {
      Set<MonoOrMultiModalStation> result = new HashSet<>();
      stations.forEach(it -> {
        MultiModalStation p = transitService.getMultiModalStationForStation(it);
        if (p != null) {
          result.add(new MonoOrMultiModalStation(p));
        } else {
          result.add(new MonoOrMultiModalStation(it, null));
        }
      });
      return result;
    } else {
      throw new IllegalArgumentException("Unexpected multiModalMode: " + multiModalMode);
    }
  }

  public static boolean isStopPlaceInUse(StopCollection station, TransitService transitService) {
    for (var quay : station.getChildStops()) {
      if (!transitService.getPatternsForStop(quay, true).isEmpty()) {
        return true;
      }
    }
    return false;
  }

  private static String destinationDisplayPerLine(TripTimeOnDate t) {
    Trip trip = t.getTrip();
    return trip == null ? t.getHeadsign() : trip.getRoute().getId() + "|" + t.getHeadsign();
  }
}
