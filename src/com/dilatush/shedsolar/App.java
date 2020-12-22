package com.dilatush.shedsolar;

import com.dilatush.mop.PostOffice;
import com.pi4j.io.gpio.GpioController;
import com.pi4j.io.gpio.GpioFactory;

import java.util.concurrent.*;
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

    public ScheduledExecutorService scheduledExecutor;  // a single-threaded scheduled executor for all to use...
    public ExecutorService          executor;           // a single-threaded executor to do all blocking I/O on...
    public GpioController           gpio;
    public PostOffice               po;
    public ShedSolarActor           actor;

    // these are public ONLY to suppress some bogus warnings that occur because the compiler doesn't understand singletons...
    public BatteryTempLED     batteryTempLED;
    public Outbacker          outbacker;
    public ProductionDetector productionDetector;
    public HeaterControl      heaterControl;
    public TempReader         tempReader;

    private App( final Config _config ) {

        // set up a scheduled executor service for all to use, in a non-daemon thread...
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor( _runnable -> {
            Thread thread = Executors.defaultThreadFactory().newThread( _runnable );
            thread.setDaemon( false );
            thread.setName( "ScheduledExecutor" );
            return thread;
        } );

        // set up an executor service for all to use, in a non-daemon thread...
        executor = Executors.newSingleThreadExecutor( _runnable -> {
            Thread thread = Executors.defaultThreadFactory().newThread( _runnable );
            thread.setDaemon( false );
            thread.setName( "Executor" );
            return thread;
        });

        config = _config;
    }

    public void run() {

        try {

            // get a GPIO controller...
            gpio = GpioFactory.getInstance();

            // set up our battery temperature LED...
            batteryTempLED = new BatteryTempLED( config.batteryTempLED );

            // set up our Outback interrogator...
            outbacker = new Outbacker( config.outbacker );

            // set up our production/dormancy discriminator...
            productionDetector = new ProductionDetector( config.productionDetector );

            // set up our heater control...
            heaterControl = new HeaterControl( config.heaterControl );

            // set up the temperature reader...
            tempReader = new TempReader( config.tempReader );

            // start up our post office and our actors...
            establishCPO();
        }

        // if we get ANY exception during the app startup, we consider it to be fatal...
        catch( Exception _e ) {
            LOGGER.log( Level.SEVERE, "Exception during App startup", _e );
            System.exit( 1 );
        }
    }


    private void establishCPO() {

        // create our post office, which initiates the connection to the CPO...
        po = new PostOffice( config.cpo );

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


    /* package protected */ static void setInstance( final Config _config ) {
        instance = new App( _config );
    }


    /**
     * Schedules the given {@link Runnable} to execute once, after the given delay (in the given time units).
     *
     * @param _runnable the {@link Runnable} to execute
     * @param _delay the delay before the {@link Runnable} should execute
     * @param _timeUnit the units of the delay
     * @return the scheduled future
     */
    public static ScheduledFuture<?> schedule( final Runnable _runnable, final long _delay, final TimeUnit _timeUnit ) {
        return instance.scheduledExecutor.schedule( _runnable, _delay, _timeUnit );
    }


    /**
     * Schedules the given {@link Runnable} to execute repeatedly, first after the given delay (in the given time units) and then at the
     * given interval.
     *
     * @param _runnable the {@link Runnable} to execute
     * @param _delay the delay before the {@link Runnable} should execute the first time
     * @param _interval the interval between repeated executions
     * @param _timeUnit the units of the delay and interval
     * @return the scheduled future
     */
    public static ScheduledFuture<?> schedule( final Runnable _runnable, final long _delay, final long _interval, final TimeUnit _timeUnit ) {
        return instance.scheduledExecutor.scheduleAtFixedRate( _runnable, _delay, _interval, _timeUnit );
    }


    /**
     * Execute the given {@link Runnable} when the blocking I/O thread becomes available.
     *
     * @param _runnable The {@link Runnable} to execute
     */
    public static void execute( final Runnable _runnable ) {
        instance.executor.execute( _runnable );
    }
}
