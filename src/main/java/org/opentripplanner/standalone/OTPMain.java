package org.opentripplanner.standalone;

import static org.opentripplanner.model.projectinfo.OtpProjectInfo.projectInfo;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import org.geotools.referencing.factory.DeferredAuthorityFactory;
import org.geotools.util.WeakCollectionCleaner;
import org.opentripplanner.datastore.api.DataSource;
import org.opentripplanner.graph_builder.GraphBuilder;
import org.opentripplanner.routing.graph.SerializedGraphObject;
import org.opentripplanner.standalone.config.CommandLineParameters;
import org.opentripplanner.standalone.configure.OTPAppConstruction;
import org.opentripplanner.standalone.configure.OTPApplicationFactory;
import org.opentripplanner.standalone.server.GrizzlyServer;
import org.opentripplanner.transit.raptor.configure.RaptorConfig;
import org.opentripplanner.transit.service.TransitModel;
import org.opentripplanner.updater.GraphUpdaterConfigurator;
import org.opentripplanner.util.OtpAppException;
import org.opentripplanner.util.ThrowableUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

/**
 * This is the main entry point to OpenTripPlanner. It allows both building graphs and starting up
 * an OTP server depending on command line options. OTPMain is a concrete class making it possible
 * to construct one with custom CommandLineParameters and use its graph builder construction method
 * from web services or scripts, not just from the static main function below.
 */
public class OTPMain {

  private static final Logger LOG = LoggerFactory.getLogger(OTPMain.class);

  static {
    // Disable HSQLDB reconfiguration of Java Unified Logging (j.u.l)
    //noinspection AccessOfSystemProperties
    System.setProperty("hsqldb.reconfig_logging", "false");

    // Remove existing handlers attached to the j.u.l root logger
    SLF4JBridgeHandler.removeHandlersForRootLogger();
    // Bridge j.u.l (used by Jersey) to the SLF4J root logger, so all logging goes through the same API
    SLF4JBridgeHandler.install();
  }

  /**
   * ENTRY POINT: This is the main method that is called when running otp.jar from the command
   * line.
   */
  public static void main(String[] args) {
    try {
      CommandLineParameters params = parseAndValidateCmdLine(args);
      OtpStartupInfo.logInfo();
      startOTPServer(params);
    } catch (OtpAppException ae) {
      LOG.error(ae.getMessage());
      System.exit(100);
    } catch (Exception e) {
      LOG.error("An uncaught error occurred inside OTP: {}", e.getLocalizedMessage(), e);
      System.exit(-1);
    }
  }

  /**
   * Parse and validate command line parameters. If the arguments is invalid the method uses {@code
   * System.exit()} to exit the application.
   */
  private static CommandLineParameters parseAndValidateCmdLine(String[] args) {
    CommandLineParameters params = new CommandLineParameters();
    try {
      // It is tempting to use JCommander's command syntax: http://jcommander.org/#_more_complex_syntaxes_commands
      // But this seems to lead to confusing switch ordering and more difficult subsequent use of the
      // parsed commands, since there will be three separate objects.
      JCommander jc = JCommander.newBuilder().addObject(params).args(args).build();
      if (params.version) {
        System.out.println("OpenTripPlanner " + projectInfo().getVersionString());
        System.exit(0);
      }
      if (params.serializationVersionId) {
        System.out.println(projectInfo().getOtpSerializationVersionId());
        System.exit(0);
      }
      if (params.help) {
        System.out.println("OpenTripPlanner " + projectInfo().getVersionString());
        jc.setProgramName("java -Xmx4G -jar otp.jar");
        jc.usage();
        System.exit(0);
      }
      params.inferAndValidate();
    } catch (ParameterException pex) {
      LOG.error("Parameter error: {}", pex.getMessage());
      System.exit(1);
    }
    return params;
  }

  /**
   * All startup logic is in an instance method instead of the static main method so it is possible
   * to build graphs from web services or scripts, not just from the command line. If options cause
   * an OTP API server to start up, this method will return when the web server shuts down.
   *
   * @throws RuntimeException if an error occurs while loading the graph.
   */
  private static void startOTPServer(CommandLineParameters params) {
    boolean graphAvailable = false;
    LOG.info(
      "Searching for configuration and input files in {}",
      params.getBaseDirectory().getAbsolutePath()
    );

    var app = new OTPAppConstruction(params);
    var factory = app.getFactory();
    var datastore = factory.datastore();
    var configModel = factory.configModel();

    // Validate data sources, command line arguments and config before loading and
    // processing input data to fail early
    app.validateConfigAndDataSources();

    /* Load graph from disk if one is not present from build. */
    if (params.doLoadGraph() || params.doLoadStreetGraph()) {
      DataSource graphFile = params.doLoadGraph()
        ? datastore.getGraph()
        : datastore.getStreetGraph();
      SerializedGraphObject obj = SerializedGraphObject.load(graphFile);
      app.updateModel(obj.graph, obj.transitModel);
      configModel.updateConfigFromSerializedGraph(obj.buildConfig, obj.routerConfig);
      graphAvailable = true;
    }

    /* Start graph builder if requested. */
    if (params.doBuildStreet() || params.doBuildTransit()) {
      // Abort building a graph if the file can not be saved
      SerializedGraphObject.verifyTheOutputGraphIsWritableIfDataSourceExist(
        app.graphOutputDataSource()
      );

      GraphBuilder graphBuilder = app.createGraphBuilder();
      if (graphBuilder != null) {
        graphBuilder.run();
        graphAvailable = true;
      } else {
        throw new IllegalStateException("An error occurred while building the graph.");
      }
      // Store graph and config used to build it, also store router-config for easy deployment
      // with using the embedded router config.
      new SerializedGraphObject(
        app.graph(),
        app.transitModel(),
        configModel.buildConfig(),
        configModel.routerConfig()
      )
        .save(app.graphOutputDataSource());
      // Log size info for the deduplicator
      LOG.info("Memory optimized {}", app.graph().deduplicator.toString());
    }

    if (!graphAvailable) {
      LOG.error("Nothing to do, no graph loaded or build. Exiting.");
      System.exit(101);
    }

    if (params.doServe()) {
      startOtpWebServer(params, app);
    } else {
      LOG.info("Done building graph. Exiting.");
    }
  }

  private static void startOtpWebServer(CommandLineParameters params, OTPAppConstruction app) {
    // Index graph for travel search
    app.transitModel().index();
    app.graph().index();

    // publishing the config version info make it available to the APIs
    setOtpConfigVersionsOnServerInfo(app.getFactory());

    /* Start visualizer if requested. */
    if (params.visualize) {
      app.graphVisualizer().run();
    }

    /* Start web server if requested. */
    // We could start the server first so it can report build/load progress to a load balancer.
    // This would also avoid the awkward call to set the router on the appConstruction after it's constructed.
    // However, currently the server runs in a blocking way and waits for shutdown, so has to run last.
    if (params.doServe()) {
      GrizzlyServer grizzlyServer = app.createGrizzlyServer();

      registerShutdownHookToGracefullyShutDownServer(app.transitModel(), app.raptorConfig());

      // Loop to restart server on uncaught fatal exceptions.
      while (true) {
        try {
          grizzlyServer.run();
          return;
        } catch (Throwable throwable) {
          LOG.error(
            "An uncaught error occurred inside OTP. Restarting server. Error was: {}",
            ThrowableUtils.detailedString(throwable)
          );
        }
        logLocationOfRequestLog(app.getFactory().configModel().routerConfig().requestLogFile());
      }
    }
  }

  /**
   * Shut down this server when evicted or (auto-)reloaded.
   * <ol>
   *   <li>Stop any real-time updater threads.</li>
   *   <li>Cleanup various stuff of some used libraries (org.geotools), which depend on the
   *   external client to call them for cleaning-up.</li>
   * </ol>
   */
  private static void registerShutdownHookToGracefullyShutDownServer(
    TransitModel transitModel,
    RaptorConfig<?> raptorConfig
  ) {
    var hook = new Thread(() -> {
      LOG.info("OTP shutdown started...");
      GraphUpdaterConfigurator.shutdownGraph(transitModel);
      raptorConfig.shutdown();
      WeakCollectionCleaner.DEFAULT.exit();
      DeferredAuthorityFactory.exit();
    });
    Runtime.getRuntime().addShutdownHook(hook);
  }

  private static void logLocationOfRequestLog(String requestLogFile) {
    if (requestLogFile != null) {
      LOG.info("Logging incoming requests at '{}'", requestLogFile);
    } else {
      LOG.info("Incoming requests will not be logged.");
    }
  }

  private static void setOtpConfigVersionsOnServerInfo(OTPApplicationFactory factory) {
    var c = factory.configModel();
    projectInfo().otpConfigVersion = c.otpConfig().configVersion;
    projectInfo().buildConfigVersion = c.buildConfig().configVersion;
    projectInfo().routerConfigVersion = c.routerConfig().getConfigVersion();
  }
}
