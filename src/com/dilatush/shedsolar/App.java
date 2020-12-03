package com.dilatush.shedsolar;

import com.dilatush.mop.PostOffice;
import com.dilatush.util.Config;
import com.dilatush.util.test.TestOrchestrator;
import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;

import java.util.Timer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The ShedSolar application.  A single instance of this class is constructed and run when the shed solar system is started up.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class App {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );
    private static final long   MAX_CPO_WAIT = 10000;

    public static App       instance;

    public final Config       config;

    public Timer            timer;
    public GpioController   gpio;
    public TestOrchestrator orchestrator;
    public PostOffice       po;
    public ShedSolarActor   actor;

    // these are public ONLY to suppress some bogus warnings that occur because the compiler doesn't understand singletons...
    public BatteryTempLED     batteryTempLED;
    public Outbacker          outbacker;
    public ProductionDetector productionDetector;
    public HeaterControl      heaterControl;

    private App( final Config _config ) {

        // set up a timer for all to use, in a non-daemon thread...
        timer = new Timer( "Timer", false );

        config = _config;
        orchestrator = new TestOrchestrator( timer );
    }

    public void run() {

        try {

            // get a GPIO controller...
            gpio = GpioFactory.getInstance();

            // set up our battery temperature LED...
            batteryTempLED = new BatteryTempLED( config );

            // set up our Outback interrogator...
            outbacker = new Outbacker( config );

            // set up our production/dormancy discriminator...
            productionDetector = new ProductionDetector( config );

            // set up our heater control...
            heaterControl = new HeaterControl( config );

            // start up our post office and our actors...
            establishCPO();

            // establish the temperature reader...
            int  maxRetries     = config.optIntDotted(  "temperatureSensor.maxRetries",     10   );
            int  minStableReads = config.optIntDotted(  "temperatureSensor.minStableReads", 3    );
            long intervalMS     = config.optLongDotted( "temperatureSensor.intervalMS",     5000 );
            timer.schedule( new TempReader( maxRetries, minStableReads ), 0, intervalMS );

            // start up our test orchestration...
            orchestrator.schedule( config );
        }

        // if we get ANY exception during the app startup, we consider it to be fatal...
        catch( Exception _e ) {
            LOGGER.log( Level.SEVERE, "Exception during App startup", _e );
            System.exit( 1 );
        }
    }


    private void establishCPO() {

        // create our post office, which initiates the connection to the CPO...
        po = new PostOffice( config );

        // wait a bit for the CPO to connect...
        long start = System.currentTimeMillis();
        while( (System.currentTimeMillis() - start) < MAX_CPO_WAIT ) {

            // have we connected to the CPO yet?
            if( po.isConnected() ) {

                // yup, so start our actor and get out of here...
                actor = new ShedSolarActor( po );
                LOGGER.info( "Connected to CPO" );
                return;
            }
        }

        // if we get here, we've failed to connect to the CPO at startup - time to leave...
        LOGGER.info( "Failed to connect to CPO" );
        throw new IllegalStateException( "Failed to connect to CPO" );
    }


    public static void setInstance( final Config _config ) {
        instance = new App( _config );
    }
}
