package org.opentripplanner.graph_builder.module;

import java.awt.Color;
import java.io.IOException;
import java.io.Serializable;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.onebusaway.csv_entities.EntityHandler;
import org.onebusaway.gtfs.impl.GtfsRelationalDaoImpl;
import org.onebusaway.gtfs.model.Agency;
import org.onebusaway.gtfs.model.FareAttribute;
import org.onebusaway.gtfs.model.IdentityBean;
import org.onebusaway.gtfs.model.Pathway;
import org.onebusaway.gtfs.model.Route;
import org.onebusaway.gtfs.model.ServiceCalendar;
import org.onebusaway.gtfs.model.ServiceCalendarDate;
import org.onebusaway.gtfs.model.ShapePoint;
import org.onebusaway.gtfs.model.Stop;
import org.onebusaway.gtfs.model.Trip;
import org.onebusaway.gtfs.serialization.GtfsReader;
import org.onebusaway.gtfs.services.GenericMutableDao;
import org.onebusaway.gtfs.services.GtfsMutableRelationalDao;
import org.opentripplanner.ext.fares.impl.DefaultFareServiceFactory;
import org.opentripplanner.ext.flex.FlexTripsMapper;
import org.opentripplanner.graph_builder.DataImportIssueStore;
import org.opentripplanner.graph_builder.model.GraphBuilderModule;
import org.opentripplanner.graph_builder.model.GtfsBundle;
import org.opentripplanner.graph_builder.module.geometry.GeometryProcessor;
import org.opentripplanner.graph_builder.module.interlining.InterlineProcessor;
import org.opentripplanner.gtfs.GenerateTripPatternsOperation;
import org.opentripplanner.gtfs.RepairStopTimesForEachTripOperation;
import org.opentripplanner.gtfs.mapping.GTFSToOtpTransitServiceMapper;
import org.opentripplanner.model.OtpTransitService;
import org.opentripplanner.model.TripStopTimes;
import org.opentripplanner.model.calendar.CalendarServiceData;
import org.opentripplanner.model.calendar.ServiceDateInterval;
import org.opentripplanner.model.impl.OtpTransitServiceBuilder;
import org.opentripplanner.routing.fares.FareService;
import org.opentripplanner.routing.fares.FareServiceFactory;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.standalone.config.BuildConfig;
import org.opentripplanner.transit.model.framework.FeedScopedId;
import org.opentripplanner.transit.service.TransitModel;
import org.opentripplanner.util.OTPFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GtfsModule implements GraphBuilderModule {

  private static final Logger LOG = LoggerFactory.getLogger(GtfsModule.class);
  private final EntityHandler counter = new EntityCounter();
  private final Set<String> agencyIdsSeen = new HashSet<>();
  /**
   * @see BuildConfig#transitServiceStart
   * @see BuildConfig#transitServiceEnd
   */
  private final ServiceDateInterval transitPeriodLimit;
  private final List<GtfsBundle> gtfsBundles;
  private final FareServiceFactory fareServiceFactory;
  private final boolean discardMinTransferTimes;
  private final boolean blockBasedInterlining;
  private final int maxInterlineDistance;

  private final TransitModel transitModel;
  private final Graph graph;
  private final DataImportIssueStore issueStore;
  private int nextAgencyId = 1; // used for generating agency IDs to resolve ID conflicts

  public GtfsModule(
    List<GtfsBundle> bundles,
    TransitModel transitModel,
    Graph graph,
    DataImportIssueStore issueStore,
    ServiceDateInterval transitPeriodLimit,
    FareServiceFactory fareServiceFactory,
    boolean discardMinTransferTimes,
    boolean blockBasedInterlining,
    int maxInterlineDistance
  ) {
    this.gtfsBundles = bundles;
    this.transitModel = transitModel;
    this.graph = graph;
    this.issueStore = issueStore;
    this.transitPeriodLimit = transitPeriodLimit;
    this.fareServiceFactory = fareServiceFactory;
    this.discardMinTransferTimes = discardMinTransferTimes;
    this.blockBasedInterlining = blockBasedInterlining;
    this.maxInterlineDistance = maxInterlineDistance;
  }

  public GtfsModule(
    List<GtfsBundle> bundles,
    TransitModel transitModel,
    Graph graph,
    ServiceDateInterval transitPeriodLimit
  ) {
    this(
      bundles,
      transitModel,
      graph,
      DataImportIssueStore.noopIssueStore(),
      transitPeriodLimit,
      new DefaultFareServiceFactory(),
      false,
      true,
      100
    );
  }

  @Override
  public void buildGraph() {
    CalendarServiceData calendarServiceData = new CalendarServiceData();

    boolean hasTransit = false;

    try {
      for (GtfsBundle gtfsBundle : gtfsBundles) {
        GtfsMutableRelationalDao gtfsDao = loadBundle(gtfsBundle);
        GTFSToOtpTransitServiceMapper mapper = new GTFSToOtpTransitServiceMapper(
          gtfsBundle.getFeedId().getId(),
          issueStore,
          discardMinTransferTimes,
          gtfsDao
        );
        mapper.mapStopTripAndRouteDataIntoBuilder();

        OtpTransitServiceBuilder builder = mapper.getBuilder();
        var fareRulesService = mapper.getFareRulesService();

        builder.limitServiceDays(transitPeriodLimit);

        calendarServiceData.add(builder.buildCalendarServiceData());

        if (OTPFeature.FlexRouting.isOn()) {
          builder.getFlexTripsById().addAll(FlexTripsMapper.createFlexTrips(builder, issueStore));
        }

        repairStopTimesForEachTrip(builder.getStopTimesSortedByTrip(), issueStore);

        GeometryProcessor geometryProcessor = new GeometryProcessor(
          builder,
          gtfsBundle.getMaxStopToShapeSnapDistance(),
          issueStore
        );

        // NB! The calls below have side effects - the builder state is updated!
        createTripPatterns(
          graph,
          transitModel,
          builder,
          calendarServiceData.getServiceIds(),
          geometryProcessor,
          issueStore
        );

        OtpTransitService otpTransitService = builder.build();

        // if this or previously processed gtfs bundle has transit that has not been filtered out
        hasTransit = hasTransit || otpTransitService.hasActiveTransit();

        addTransitModelToGraph(graph, transitModel, gtfsBundle, otpTransitService);

        if (blockBasedInterlining) {
          new InterlineProcessor(
            transitModel.getTransferService(),
            builder.getStaySeatedNotAllowed(),
            maxInterlineDistance,
            issueStore
          )
            .run(transitModel.getAllTripPatterns());
        }

        fareServiceFactory.processGtfs(fareRulesService, otpTransitService);
        graph.putService(FareService.class, fareServiceFactory.makeFareService());
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    } finally {
      // Note the close method of each bundle should NOT throw an exception, so this
      // code should be safe without the try/catch block.
      gtfsBundles.forEach(GtfsBundle::close);
    }

    transitModel.validateTimeZones();

    transitModel.updateCalendarServiceData(hasTransit, calendarServiceData, issueStore);
  }

  @Override
  public void checkInputs() {
    for (GtfsBundle bundle : gtfsBundles) {
      bundle.checkInputs();
    }
  }

  /* Private Methods */

  /**
   * This method has side effects, the {@code stopTimesByTrip} is updated.
   */
  private void repairStopTimesForEachTrip(
    TripStopTimes stopTimesByTrip,
    DataImportIssueStore issueStore
  ) {
    new RepairStopTimesForEachTripOperation(stopTimesByTrip, issueStore).run();
  }

  /**
   * This method has side effects, the {@code builder} is updated with new TripPatterns.
   */
  private void createTripPatterns(
    Graph graph,
    TransitModel transitModel,
    OtpTransitServiceBuilder builder,
    Set<FeedScopedId> calServiceIds,
    GeometryProcessor geometryProcessor,
    DataImportIssueStore issueStore
  ) {
    GenerateTripPatternsOperation buildTPOp = new GenerateTripPatternsOperation(
      builder,
      issueStore,
      graph.deduplicator,
      calServiceIds,
      geometryProcessor
    );
    buildTPOp.run();
    transitModel.setHasFrequencyService(
      transitModel.hasFrequencyService() || buildTPOp.hasFrequencyBasedTrips()
    );
    transitModel.setHasScheduledService(
      transitModel.hasScheduledService() || buildTPOp.hasScheduledTrips()
    );
  }

  private void addTransitModelToGraph(
    Graph graph,
    TransitModel transitModel,
    GtfsBundle gtfsBundle,
    OtpTransitService otpTransitService
  ) {
    AddTransitModelEntitiesToGraph.addToGraph(
      gtfsBundle.getFeedId(),
      otpTransitService,
      gtfsBundle.subwayAccessTime,
      graph,
      transitModel
    );
  }

  private GtfsMutableRelationalDao loadBundle(GtfsBundle gtfsBundle) throws IOException {
    StoreImpl store = new StoreImpl(new GtfsRelationalDaoImpl());
    store.open();
    LOG.info("reading {}", gtfsBundle.toString());

    GtfsFeedId gtfsFeedId = gtfsBundle.getFeedId();

    GtfsReader reader = new GtfsReader();
    reader.setInputSource(gtfsBundle.getCsvInputSource());
    reader.setEntityStore(store);
    reader.setInternStrings(true);
    reader.setDefaultAgencyId(gtfsFeedId.getId());

    if (LOG.isDebugEnabled()) reader.addEntityHandler(counter);

    for (Class<?> entityClass : reader.getEntityClasses()) {
      LOG.info("reading entities: " + entityClass.getName());
      reader.readEntities(entityClass);
      store.flush();
      // NOTE that agencies are first in the list and read before all other entity types, so it is effective to
      // set the agencyId here. Each feed ("bundle") is loaded by a separate reader, so there is no risk of
      // agency mappings accumulating.
      if (entityClass == Agency.class) {
        for (Agency agency : reader.getAgencies()) {
          String agencyId = agency.getId();
          LOG.info("This Agency has the ID {}", agencyId);
          // Somehow, when the agency's id field is missing, OBA replaces it with the agency's name.
          // TODO Figure out how and why this is happening.
          if (agencyId == null || agencyIdsSeen.contains(gtfsFeedId.getId() + agencyId)) {
            // Loop in case generated name is already in use.
            String generatedAgencyId = null;
            while (generatedAgencyId == null || agencyIdsSeen.contains(generatedAgencyId)) {
              generatedAgencyId = "F" + nextAgencyId;
              nextAgencyId++;
            }
            LOG.warn(
              "The agency ID '{}' was already seen, or I think it's bad. Replacing with '{}'.",
              agencyId,
              generatedAgencyId
            );
            reader.addAgencyIdMapping(agencyId, generatedAgencyId); // NULL key should work
            agency.setId(generatedAgencyId);
            agencyId = generatedAgencyId;
          }
          if (agencyId != null) agencyIdsSeen.add(gtfsFeedId.getId() + agencyId);
        }
      }
    }

    for (ShapePoint shapePoint : store.getAllEntitiesForType(ShapePoint.class)) {
      shapePoint.getShapeId().setAgencyId(reader.getDefaultAgencyId());
    }
    for (Route route : store.getAllEntitiesForType(Route.class)) {
      route.getId().setAgencyId(reader.getDefaultAgencyId());
      generateRouteColor(route);
    }
    for (Stop stop : store.getAllEntitiesForType(Stop.class)) {
      stop.getId().setAgencyId(reader.getDefaultAgencyId());
    }
    for (Trip trip : store.getAllEntitiesForType(Trip.class)) {
      trip.getId().setAgencyId(reader.getDefaultAgencyId());
    }
    for (ServiceCalendar serviceCalendar : store.getAllEntitiesForType(ServiceCalendar.class)) {
      serviceCalendar.getServiceId().setAgencyId(reader.getDefaultAgencyId());
    }
    for (ServiceCalendarDate serviceCalendarDate : store.getAllEntitiesForType(
      ServiceCalendarDate.class
    )) {
      serviceCalendarDate.getServiceId().setAgencyId(reader.getDefaultAgencyId());
    }
    for (FareAttribute fareAttribute : store.getAllEntitiesForType(FareAttribute.class)) {
      fareAttribute.getId().setAgencyId(reader.getDefaultAgencyId());
    }
    for (Pathway pathway : store.getAllEntitiesForType(Pathway.class)) {
      pathway.getId().setAgencyId(reader.getDefaultAgencyId());
    }

    store.close();
    return store.dao;
  }

  /**
   * Generates routeText colors for routes with routeColor and without routeTextColor
   * <p>
   * If a route doesn't have color or already has routeColor and routeTextColor nothing is done.
   * <p>
   * textColor can be black or white. White for dark colors and black for light colors of
   * routeColor. If color is light or dark is calculated based on luminance formula: sqrt(
   * 0.299*Red^2 + 0.587*Green^2 + 0.114*Blue^2 )
   */
  private void generateRouteColor(Route route) {
    String routeColor = route.getColor();
    //No route color - skipping
    if (routeColor == null) {
      return;
    }
    String textColor = route.getTextColor();
    //Route already has text color skipping
    if (textColor != null) {
      return;
    }

    Color routeColorColor = Color.decode("#" + routeColor);
    //gets float of RED, GREEN, BLUE in range 0...1
    float[] colorComponents = routeColorColor.getRGBColorComponents(null);
    //Calculates luminance based on https://stackoverflow.com/questions/596216/formula-to-determine-brightness-of-rgb-color
    double newRed = 0.299 * Math.pow(colorComponents[0], 2.0);
    double newGreen = 0.587 * Math.pow(colorComponents[1], 2.0);
    double newBlue = 0.114 * Math.pow(colorComponents[2], 2.0);
    double luminance = Math.sqrt(newRed + newGreen + newBlue);

    //For brighter colors use black text color and reverse for darker
    if (luminance > 0.5) {
      textColor = "000000";
    } else {
      textColor = "FFFFFF";
    }
    route.setTextColor(textColor);
  }

  private static class StoreImpl implements GenericMutableDao {

    private final GtfsMutableRelationalDao dao;

    StoreImpl(GtfsMutableRelationalDao dao) {
      this.dao = dao;
    }

    @Override
    public void open() {
      dao.open();
    }

    @Override
    public void saveEntity(Object entity) {
      dao.saveEntity(entity);
    }

    @Override
    public void updateEntity(Object entity) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void saveOrUpdateEntity(Object entity) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <K extends Serializable, T extends IdentityBean<K>> void removeEntity(T entity) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> void clearAllEntitiesForType(Class<T> type) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void flush() {
      dao.flush();
    }

    @Override
    public void close() {
      dao.close();
    }

    @Override
    public <T> Collection<T> getAllEntitiesForType(Class<T> type) {
      return dao.getAllEntitiesForType(type);
    }

    @Override
    public <T> T getEntityForId(Class<T> type, Serializable id) {
      return dao.getEntityForId(type, id);
    }
  }

  private static class EntityCounter implements EntityHandler {

    private final Map<Class<?>, Integer> count = new HashMap<>();

    @Override
    public void handleEntity(Object bean) {
      int count = incrementCount(bean.getClass());
      if (count % 1000000 == 0) if (LOG.isDebugEnabled()) {
        String name = bean.getClass().getName();
        int index = name.lastIndexOf('.');
        if (index != -1) name = name.substring(index + 1);
        LOG.debug("loading " + name + ": " + count);
      }
    }

    private int incrementCount(Class<?> entityType) {
      Integer value = count.get(entityType);
      if (value == null) {
        value = 0;
      }
      value++;
      count.put(entityType, value);
      return value;
    }
  }
}
