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

    public static App       instance;
    public Timer            timer;
    public GpioController   gpio;
    public TestOrchestrator orchestrator;
    public PostOffice po;
    public  ShedSolarActor actor;

    private BatteryTempLED     batteryTempLED;
    private Outbacker          outbacker;
    private ProductionDetector productionDetector;
    private final Config       config;

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

            // start up our post office and our actors...
            po = new PostOffice( config );
            actor = new ShedSolarActor( po );

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


    public static void setInstance( final Config _config ) {
        instance = new App( _config );
    }
}
