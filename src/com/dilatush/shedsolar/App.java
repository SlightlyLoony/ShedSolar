package com.dilatush.shedsolar;

import com.dilatush.util.Config;
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

    public static App           instance;
    public Timer          timer;
    public GpioController gpio;

    private BatteryTempLED batteryTempLED;
    private Config config;

    private App( final Config _config ) {
        config = _config;
    }

    public void run() {

        // set up a timer for all to use, in a non-daemon thread...
        timer = new Timer( "Timer", false );

        // get a GPIO controller...
        gpio = GpioFactory.getInstance();

        // set up our battery temperature LED...
        batteryTempLED = new BatteryTempLED();

        try {

            // establish the temperature reader...
            int  maxRetries     = config.optIntDotted(  "temperatureSensor.maxRetries",     10   );
            int  minStableReads = config.optIntDotted(  "temperatureSensor.minStableReads", 3    );
            long intervalMS     = config.optLongDotted( "temperatureSensor.intervalMS",     5000 );
            timer.schedule( new TempReader( maxRetries, minStableReads ), 0, intervalMS );
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
