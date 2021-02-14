package com.dilatush.shedsolar;

import com.dilatush.mop.PostOffice;
import com.dilatush.util.AConfig;
import com.dilatush.util.AConfig.InitResult;
import com.dilatush.util.ExecutorService;
import com.dilatush.util.Haps;
import com.dilatush.util.ScheduledExecutor;
import com.dilatush.util.cli.CommandLine;
import com.dilatush.util.cli.InteractiveMode;
import com.dilatush.util.cli.ParameterMode;
import com.dilatush.util.cli.ParsedCommandLine;
import com.dilatush.util.cli.argdefs.OptArgDef;
import com.dilatush.util.cli.argdefs.OptArgNames;
import com.dilatush.util.cli.parsers.EnumerationParser;
import com.dilatush.util.console.ConsoleServer;
import com.dilatush.util.info.Info;
import com.dilatush.util.info.InfoView;
import com.dilatush.util.test.TestManager;
import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;

import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.Thread.sleep;

/**
 * @author Tom Dilatush  tom@dilatush.com
 */
public class ShedSolar {

    public  static final ShedSolar instance = new ShedSolar();

    private static final long MAX_CPO_WAIT_MS = 2000;
    private static final int  MAX_CPO_TRIES   = 3;
    private static final int  HAPS_QUEUE_SIZE = 100;

    // our public info...
    public final Info<Float>                   batteryTemperature;
    public final Info<Float>                   heaterTemperature;
    public final Info<Float>                   ambientTemperature;
    public final Info<OutbackData>             outback;
    public final Info<Float>                   outsideTemperature;
    public final Info<Float>                   solarIrradiance;
    public final Info<ProductionDetector.Mode> productionMode;

    // and our setters...
    private Consumer<Info<Float>>                   batteryTemperatureSetter;
    private Consumer<Info<Float>>                   heaterTemperatureSetter;
    private Consumer<Info<Float>>                   ambientTemperatureSetter;
    private Consumer<Info<OutbackData>>             outbackSetter;
    private Consumer<Info<Float>>                   outsideTemperatureSetter;
    private Consumer<Info<Float>>                   solarIrradianceSetter;
    private Consumer<Info<ProductionDetector.Mode>> productionModeSetter;


    public final ScheduledExecutor   scheduledExecutor;  // a single-threaded scheduled executor for all to use...
    public final ExecutorService     executor;           // a double-threaded executor to do all blocking I/O on...
    public final Haps<Events>        haps;               // events...

    private final Logger             LOGGER;
    private ShedSolarMode            mode;
    private PostOffice               po;
    private ShedSolarActor           actor;
    private Config                   config;

    private BatteryTempLED           batteryTempLED;
    private Outbacker                outbacker;
    private ProductionDetector       productionDetector;
    private HeaterControl            heaterControl;
    private TempReader               tempReader;
    private ConsoleServer            consoleServer;
    private GpioController           gpio;


    /**
     * Create a new instance of this class, with the given command line arguments.
     */
    private ShedSolar() {

        // set the configuration file location (must do before any logging actions occur)...
        System.getProperties().setProperty( "java.util.logging.config.file", "logging.properties" );
        LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getSimpleName() );

        // set up a scheduled executor service for all to use, in a non-daemon thread...
        scheduledExecutor = new ScheduledExecutor( false );

        // set up an executor service for all to use, in a non-daemon thread...
        executor = new ExecutorService( 2, 10 );

        // set up our info publishers...
        batteryTemperature   = new InfoView<>( (setter) -> batteryTemperatureSetter = setter );
        heaterTemperature    = new InfoView<>( (setter) -> heaterTemperatureSetter  = setter );
        ambientTemperature   = new InfoView<>( (setter) -> ambientTemperatureSetter = setter );
        outback              = new InfoView<>( (setter) -> outbackSetter            = setter );
        solarIrradiance      = new InfoView<>( (setter) -> solarIrradianceSetter    = setter );
        outsideTemperature   = new InfoView<>( (setter) -> outsideTemperatureSetter = setter );
        productionMode       = new InfoView<>( (setter) -> productionModeSetter     = setter );

        // start up our haps (events), using our "global" scheduled executor...
        haps = new Haps<>( HAPS_QUEUE_SIZE, scheduledExecutor, Events.INTERNET_DOWN );
    }


    /**
     * Runs the ShedSolar app...
     */
    private void run() {

        LOGGER.info( "ShedSolar is starting..." );

        // get our configuration...
        InitResult ir = AConfig.init( Config.class, "configuration.js" );

        // if our configuration is not valid, just get out of here...
        if( !ir.valid ) {
            LOGGER.severe( "Aborting; configuration is invalid" );
            System.exit( 1 );
        }

        // get our actual configuration...
        config = (Config) ir.config;

        // start up our console server...
        consoleServer = new ConsoleServer( config.consoleServer );

        // initialize our test manager...
        TestManager.configure( config.testManager );

        // start up our post office and our actors...
        establishCPO();

        // get a GPIO controller...
        gpio = GpioFactory.getInstance();

        // are we running in assembly test mode or for reals?
        switch( mode ) {

            case ASSEMBLY_TEST:
                LOGGER.info( "Running in assembly test mode" );
                AssemblyTest at = new AssemblyTest();
                at.run();
                break;

            case NORMAL:
                LOGGER.info( "Running in production mode..." );
                runNormal();
                break;

            case TEMPERATURE_TEST:
                LOGGER.info( "Running in temperature test mode..." );
                TempTest tt = new TempTest( config );
                tt.run();
                break;

            default:
                LOGGER.severe( "Somehow we got an impossible mode..." );
                break;
        }
        // leaving this method doesn't shut down the process because the app starts at least one non-daemon thread...
    }


    /**
     * Return {@code true} if the central post office is connected.
     *
     * @return {@code true} if the central post office is connected
     */
    public boolean isPostOfficeConnected() {
        return po.isConnected();
    }


    /**
     * Return the one GPIO controller instance.
     *
     * @return the one GPIO controller instance
     */
    public GpioController getGPIO() {
        return gpio;
    }


    /**
     * The entry point for the ShedSolar daemon.
     *
     * @param _args The command line arguments.
     */
    public static void main( final String[] _args ) {
        instance.init( _args );
        instance.run();
    }


    private void runNormal() {

        try {

            // set up our battery temperature LED...
            batteryTempLED = new BatteryTempLED( config.batteryTempLED );

            // set up our Outback interrogator...
            outbacker = new Outbacker( config.outbacker );
            outbackSetter.accept( outbacker.outback );

            // set up our production/dormancy discriminator...
            productionDetector = new ProductionDetector( config.productionDetector );
            productionModeSetter.accept( productionDetector.productionMode );
//
//            // set up our heater control...
//            heaterControl = new HeaterControl( config.heaterControl );

            // set up the temperature reader...
            tempReader = new TempReader( config.tempReader );
            batteryTemperatureSetter.accept( tempReader.batteryTemperature );
            heaterTemperatureSetter.accept( tempReader.heaterTemperature );
            ambientTemperatureSetter.accept( tempReader.ambientTemperature );

        }

        // if we get ANY exception during the app startup, we consider it to be fatal...
        catch( Exception _e ) {
            LOGGER.log( Level.SEVERE, "Exception during App startup", _e );
            System.exit( 1 );
        }
    }


    private void init( final String[] _args ) {

        // analyze our command line...
        CommandLine commandLine = getCommandLine();
        ParsedCommandLine parsed = commandLine.parse( _args );
        if( !parsed.isValid() ) {
            LOGGER.severe( "Invalid command line: " + parsed.getErrorMsg() );
            System.exit( 1 );
        }

        // set our mode...
        mode = (ShedSolarMode) parsed.getValue( "mode" );
    }


    /**
     * Connect to the central post office, or die trying.
     */
    @SuppressWarnings("BusyWait")
    private void establishCPO() {

        int tries = 0;
        while( tries++ < MAX_CPO_TRIES ) {

            // create our post office, which initiates the connection to the CPO...
            po = new PostOffice( config.cpo );

            // wait a bit for the CPO to connect...
            long start = System.currentTimeMillis();
            while( (System.currentTimeMillis() - start) < MAX_CPO_WAIT_MS ) {

                // let some other things run...
                try {
                    sleep( 100 );
                }
                // if this happens somehow, it's fatal...
                catch( InterruptedException _e ) {
                    System.exit( 1 );
                }

                // have we connected to the CPO yet?
                if( po.isConnected() ) {

                    // yup, so start our actor and get out of here...
                    actor = new ShedSolarActor( po );
                    solarIrradianceSetter.accept( actor.solarIrradiance );
                    outsideTemperatureSetter.accept( actor.outsideTemperature );
                    LOGGER.info( "Connected to CPO" );
                    return;
                }
            }
        }

        // if we get here, we've failed to connect to the CPO at startup - time to leave...
        LOGGER.info( "Failed to connect to CPO" );
        throw new IllegalStateException( "Failed to connect to CPO" );
    }


    // get our command line parser...
    private CommandLine getCommandLine() {

        CommandLine commandLine = new CommandLine( "ShedSolar", "Runs the heater for my shed's solar batteries",
                "Runs the heater for my shed's solar batteries", 80, 4 );

        commandLine.add( new OptArgDef(
                "mode",
                "set the running mode",
                "Set the running mode.",
                1,
                "mode",
                ShedSolarMode.class,
                ParameterMode.OPTIONAL,
                "NORMAL",
                new EnumerationParser( ShedSolarMode.class ),
                null,
                new OptArgNames( "m;mode" ),
                "NORMAL",
                InteractiveMode.DISALLOWED,
                null
        ) );

        return commandLine;
    }


    // all the modes that ShedSolar can run in...
    private enum ShedSolarMode {
        NORMAL, TEMPERATURE_TEST, ASSEMBLY_TEST
    }
}
