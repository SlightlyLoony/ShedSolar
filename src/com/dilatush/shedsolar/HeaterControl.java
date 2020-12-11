package com.dilatush.shedsolar;

import com.dilatush.shedsolar.events.*;
import com.dilatush.util.Config;
import com.pi4j.io.gpio.*;

import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.dilatush.shedsolar.App.schedule;
import static com.dilatush.shedsolar.TemperatureMode.PRODUCTION;
import static com.dilatush.util.syncevents.SynchronousEvents.publishEvent;
import static com.dilatush.util.syncevents.SynchronousEvents.subscribeToEvent;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * <p>Controls the solid state relay that turns the heater on and off, and also the indicator LED (on when the heater is turned on).  It also monitors
 * the output temperature of the heater to verify that it is working correctly, and the sense relay that verifies that the SSR is working
 * correctly.</p>
 * <p>The heater we're using (a Brightown 250 watt ceramic heater with a fan) has an interesting characteristic: it includes a "thermal fuse" that
 * shuts down the heater if it overheats.  We've never seen this shutdown while the heater is on, but we <i>have</i> seen it happen after the
 * heater has been shut off, and then turned back on again.  We speculate that the ceramic heating element has enough thermal inertia that when the
 * heater is shut off (with the fan stopping immediately), heat can then "leak" from the heater to the thermal sensor, wherever that is.  This
 * causes the heater to <i>not</i> turn on in this circumstance.  Once this "fuse" has tripped, there's a lengthy cooldown period required before
 * it will work again.  A bit of experimenting suggests that power needs to be cycled off for 2 or 3 minutes to get it to work again.</p>
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class HeaterControl {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private final TemperatureRange     productionRange;
    private final TemperatureRange     dormantRange;
    private final GpioPinDigitalInput  ssrSense;
    private final GpioPinDigitalOutput heaterPowerLED;
    private final GpioPinDigitalOutput heaterSSR;
    private final long                 maxHeaterOnVerifyMS;
    private final long                 heaterCooldownMS;

    private volatile TemperatureMode  mode;
    private volatile float            heaterTemperature;
    private volatile float            heaterTemperatureBefore;
    private volatile boolean          heaterTemperatureGood;
    private volatile float            batteryTemperature;
    private volatile boolean          batteryTemperatureGood;
    private volatile float            ambientTemperature;
    private volatile boolean          ambientTemperatureGood;
    private volatile TemperatureRange range;
    private volatile boolean          cooldownRan;

    // state machine state...
    private volatile HeaterState     currentState;
    private volatile HeaterState     nextState;


    /**
     * Create a new instance of this class with the given configuration.  This class maintains a state machine that controls whether the heater is
     * turned on or off.  This state machine's complexity is mainly due to the all the monitoring it does, and the fallback operating modes that
     * allow it to function even if a sensor dies.  The batteries that the heater controls the temperature of can be destroyed by temperatures that
     * are out of it's operating range, and the batteries are damned expensive - hence the attempt to design in some resilience.
     *
     * @param _config the configuration file
     */
    public HeaterControl( final Config _config ) {

        // extract our configuration...
        float dLo                  = _config.getFloatDotted( "heaterControl.dormantLowTemp"             );
        float dHi                  = _config.getFloatDotted( "heaterControl.dormantHighTemp"            );
        float pLo                  = _config.getFloatDotted( "heaterControl.productionLowTemp"          );
        float pHi                  = _config.getFloatDotted( "heaterControl.productionHighTemp"         );
        maxHeaterOnVerifyMS        = _config.getLongDotted(  "heaterControl.maxHeaterOnVerifyMS"        );
        heaterCooldownMS           = _config.getLongDotted(  "heaterControl.heaterCooldownMS"           );

        productionRange = new TemperatureRange( pLo, pHi );
        dormantRange    = new TemperatureRange( dLo, dHi );
        cooldownRan     = false;

        // initialize the GPIO pins for the SSR, the SSR sense relay, and the heater on indicator LED...
        GpioController       controller     = App.instance.gpio;
        ssrSense       = controller.provisionDigitalInputPin(  RaspiPin.GPIO_00, "SSR Sense",        PinPullResistance.PULL_UP );
        heaterPowerLED = controller.provisionDigitalOutputPin( RaspiPin.GPIO_03, "Heater Power LED", PinState.HIGH             );
        heaterSSR      = controller.provisionDigitalOutputPin( RaspiPin.GPIO_05, "Heater SSR",       PinState.HIGH             );
        heaterPowerLED.setShutdownOptions( true, PinState.HIGH );
        heaterSSR.setShutdownOptions(      true, PinState.HIGH );

        // we start out by assuming that we're in production mode with the heater off...
        mode = PRODUCTION;
        range = productionRange;

        // subscribe to the events we want to monitor...
        subscribeToEvent( event -> handleTempModeEvent(            (TempMode)           event ),  TempMode.class            );
        subscribeToEvent( event -> handleBatteryTemperatureEvent(  (BatteryTemperature) event ),  BatteryTemperature.class  );
        subscribeToEvent( event -> handleHeaterTemperatureEvent(   (HeaterTemperature)  event ),  HeaterTemperature.class   );
        subscribeToEvent( event -> handleAmbientTemperatureEvent(  (AmbientTemperature)  event ), AmbientTemperature.class  );

        // set our initial state...
        currentState = null;
        nextState    = new Idle();

        // schedule our state machine tick...
        schedule( new HeaterStateMachine(), 0, _config.getLongDotted( "heaterControl.tickMS" ), MILLISECONDS );
    }


    /**
     * This is the heart of the state machine.  The {@link #run()} method is called at the tick interval, and the current state's
     * {@link HeaterState#onTick()} method is called.  If the {@link HeaterState#onTick()} returns a new state, that state is switched to.  That's
     * all the logic - everything else is in the state classes.
     */
    private class HeaterStateMachine implements Runnable {

        @Override
        public void run() {
            try {

                // if we have a current state, time to tick...
                if( currentState != null ) nextState = currentState.onTick();

                // if we have a new state, time to switch...
                if( nextState != null ) {

                    // if we have a current state, time to exit it...
                    if( currentState != null ) {
                        LOGGER.finest( "Leaving Heater state " + currentState.description() );
                        currentState.onExit();
                    }

                    // update our state variables...
                    currentState = nextState;
                    nextState = null;

                    // time to enter the new (now current) state...
                    LOGGER.finest( "Entering Heater state " + currentState.description() );
                    currentState.onEntry();
                }
            }
            catch( final RuntimeException _e ) {
                LOGGER.log( Level.SEVERE, "Uncaught exception in HeaterControl.HeaterStateMachine", _e );
            }
        }
    }


    /*
     * The following interfaces and classes each implement one state of the heater state machine.
     */

    private interface HeaterState {

        default void onEntry() {}
        default void onExit() {}
        HeaterState onTick();
        default String description() { return getClass().getSimpleName(); }
    }


    /**
     * The {@link Idle} state means the state machine doesn't have enough information to do anything intelligent.  Generally this occurs only at
     * system startup, but it can also happen if multiple sensors fail.
     */
    private class Idle implements HeaterState {

        @Override
        public HeaterState onTick() {

            // if we have good temperature data from either thermocouple, we'll go to armed state.  Otherwise, we wait...
            return ( heaterTemperatureGood || batteryTemperatureGood ) ? new Armed() : null;
        }
    }


    /**
     * The {@link Armed} state means that we have temperature data, and if the temperature is too low we're going to turn on the heater.
     */
    private class Armed implements HeaterState {

        @Override
        public HeaterState onTick() {

            // if we have good battery temperature, that's our first choice...
            if( batteryTemperatureGood ) {

                // if the battery temperature is below our current range, then turn on the heater...
                return (batteryTemperature < range.lo) ? new TurnHeaterOn() : null;
            }

            // if we don't have a good battery temperature, but we do have a good heater temperature, we'll stumble on with just that...
            else if( heaterTemperatureGood ) {

                // if both the ambient temperature and the heater temperature are below our current range, then turn on the heater...
                return (ambientTemperatureGood && (ambientTemperature < range.lo) && (heaterTemperature < range.lo)) ? new TurnHeaterOn() : null;
            }

            // we get here only if both thermocouples are bad - a fatal situation... abort!  abort!  abort!
            return new HeaterAbort();
         }
    }


    /**
     * The {@link HeaterAbort} state means that we have a fatal condition that can only be fixed by human intervention.  The heater is turned off
     * and we remain in this state unless a miracle occurs and one of our temperature sensors comes back to life.
     */
    private class HeaterAbort implements HeaterState {


        @Override
        public void onEntry() {
            turnHeaterOff();
        }


        @Override
        public HeaterState onTick() {

            // if we have good temperature data from either thermocouple, we'll go to armed state.  Otherwise, we wait...
            return ( heaterTemperatureGood || batteryTemperatureGood ) ? new Armed() : null;
        }
    }


    /**
     * The {@link TurnHeaterOn} state means that we need to turn the heater on.
     */
    private class TurnHeaterOn implements HeaterState {

        @Override
        public void onEntry() {
            turnHeaterOn();
        }


        @Override
        public HeaterState onTick() {
            return new VerifyHeaterOn();
        }
    }


    /**
     * The {@link VerifyHeaterOn} state waits for the SSR sense relay to engage and for rising heater temperatures to be sensed.  If we get both of
     * these within our time window, we'll exit normally - otherwise, things get more complicated.
     */
    private class VerifyHeaterOn implements HeaterState {

        private static final float HEATER_INCREASE_SENSE_THRESHOLD = 10;
        private static final float BATTERY_INCREASE_SENSE_THRESHOLD = 2;

        private Instant started;               // the time we started verifying...
        private Float   startingHeaterTemp;    // the starting heater temperature, or null if it was bad...
        private Float   startingBatteryTemp;   // the starting battery temperature, or null if it was bad...


        @Override
        public void onEntry() {
            started = Instant.now();
            startingHeaterTemp = heaterTemperatureGood ? heaterTemperature : null;
            startingBatteryTemp = batteryTemperatureGood ? batteryTemperature : null;
        }


        @Override
        public HeaterState onTick() {

            // if either sensed temperature was bad, fix it if it miraculously cured itself...
            if( (startingHeaterTemp  == null) && heaterTemperatureGood  ) startingHeaterTemp  = heaterTemperature;
            if( (startingBatteryTemp == null) && batteryTemperatureGood ) startingBatteryTemp = batteryTemperature;

            // update our status...
            boolean sensedSSR = ssrSense.isLow();                                                               // result is true if SSR sense relay sensed that the SSR is on...
            Float currentHeaterTemp = heaterTemperatureGood ? heaterTemperature : null;                         // the current heater temperature, or null if it was bad...
            Float currentBatteryTemp = batteryTemperatureGood ? batteryTemperature : null;                      // the current battery temperature, or null if it was bad...
            boolean heaterTempIncreasing = (startingHeaterTemp != null) && (currentHeaterTemp != null)
                    && (currentHeaterTemp - startingHeaterTemp > HEATER_INCREASE_SENSE_THRESHOLD);              // true if we've sensed the heater temperature increasing...
            boolean batteryTempIncreasing = (startingBatteryTemp != null) && (currentBatteryTemp != null)
                    && (currentBatteryTemp - startingBatteryTemp > BATTERY_INCREASE_SENSE_THRESHOLD);           // true if we've sensed the battery temperature increasing...
            boolean waitOver = (Duration.between( started, Instant.now() ).toMillis() >= maxHeaterOnVerifyMS);  // true if we've run out of time to wait...

            // safety first - if we sense the battery temperature going over range, we shut off the heater...
            if( batteryTemperatureGood && (batteryTemperature > range.hi) )
                return new TurnHeaterOff();

            // if we see the heater temperature increasing, things are working well...
            if( heaterTempIncreasing ) {

                // if we're also sensing the SSR, then we move on to a normal run...
                if( sensedSSR ) {
                    LOGGER.finest( "Heater verification: SSR and increasing heater temperature both sensed" );
                }

                // if we don't sense the SSR working, then it looks like a SSR sense relay failure...
                else {
                    LOGGER.finest( "Heater verification: increasing heater temperature sensed, SSR not sensed" );
                    publishEvent( new SSRSenseFailure() );
                }

                // in any case, move on to a normal run...
                return new HeaterRun();
            }

            // if our waiting period is not over, then we just wait some more...
            if( !waitOver )
                return null;

            // if we sense that the battery temperature has increased, then we'll assume a dual sensor failure and keep on keeping on...
            if( batteryTempIncreasing ) {
                publishEvent( new SSRSenseFailure() );
                publishEvent( new HeaterTemperatureSenseFailure() );
                LOGGER.finest( "Heater verification: SSR and increasing heater temperature both NOT sensed, but battery temperature IS increasing" );
                return new HeaterRun();
            }

            // we get here if we see neither heater nor battery temperatures increasing...

            // if we sense that the SSR is on then it looks like either the heater has failed outright, or its thermal interlock has tripped...
            // in this case, we'll try a cooldown period (to reset the thermal interlock), if we haven't run one already...
            if( sensedSSR ) {
                if( !cooldownRan ) {
                    LOGGER.finest( "Heater verification: SSR sensed, but neither heater nor battery temperatures are increasing; attempting cooldown" );
                    return new Cooldown();
                }

                // otherwise, it looks like our heater has failed...
                else {
                    LOGGER.finest( "Heater verification: SSR sensed, but neither heater nor battery temperatures are increasing; heater failure" );
                    publishEvent( new HeaterFailure() );
                    return new HeaterAbort();
                }
            }

            // otherwise we can't sense any heat increase and we can't sense the SSR is on - wo we probably have an SSR failure...
            LOGGER.finest( "Heater verification: SSR not sensed, and neither heater nor battery temperatures are increasing" );
            publishEvent( new SSRStuckOff() );
            return new HeaterAbort();
        }
    }


    private class Cooldown implements HeaterState {

        private Instant start;

        @Override
        public void onEntry() {
            turnHeaterOff();
            start = Instant.now();
        }


        @Override
        public HeaterState onTick() {

            // if our cooldown period over, time to go back to armed...
            if( Duration.between( start, Instant.now() ).toMillis() >= heaterCooldownMS ) {
                cooldownRan = true;
                return new Armed();
            }

            // otherwise, just leave...
            return null;
        }
    }


    private class HeaterRun implements HeaterState {

        @Override
        public void onEntry() {
        }


        @Override
        public HeaterState onTick() {
            return null;
        }
    }


    private class TurnHeaterOff implements HeaterState {

        @Override
        public void onEntry() {
            cooldownRan = false;  // if we get here, then the cooldown must have worked (if it ran)...
            turnHeaterOff();
        }


        @Override
        public HeaterState onTick() {
            return null;
        }
    }

    /*
     * The following methods implement event handlers.
     */

    /**
     * Handle a mode (production/dormant) event.
     *
     * @param _event the mode event
     */
    private void handleTempModeEvent( TempMode _event ) {

        LOGGER.finest( _event.toString() );
        mode = _event.mode;
        range = (mode == PRODUCTION) ? productionRange : dormantRange;
    }


    private static class TemperatureRange {
        private final float lo;
        private final float hi;


        public TemperatureRange( final float _lo, final float _hi ) {
            lo = _lo;
            hi = _hi;
        }
    }


    /**
     * Handle a battery temperature event.
     *
     * @param _event the battery temperature event
     */
    private void handleBatteryTemperatureEvent( BatteryTemperature _event ) {

        LOGGER.finest( _event.toString() );
        batteryTemperature = _event.degreesC;
        batteryTemperatureGood = _event.goodMeasurement;
    }


    /**
     * Handle a heater temperature event.
     *
     * @param _event the heater temperature event
     */
    private void handleHeaterTemperatureEvent( HeaterTemperature _event ) {

        LOGGER.finest( _event.toString() );
        heaterTemperature = _event.degreesC;
        heaterTemperatureGood = _event.goodMeasurement;
    }


    /**
     * Handle an ambient temperature event.
     *
     * @param _event the heater temperature event
     */
    private void handleAmbientTemperatureEvent( AmbientTemperature _event ) {

        LOGGER.finest( _event.toString() );
        ambientTemperature = _event.degreesC;
        ambientTemperatureGood = true;
    }


//    /**
//     * Handle the results of a temperature event (battery OR heater).
//     */
//    private void handleHeater() {
//
//        // if we don't have any good temperature measurements, turn the heater off, scream, and leave...
//        if( !batteryTemperatureGood && !heaterTemperatureGood ) {
//            turnHeaterOff();
//            publishEvent( new HeaterControlAbort() );
//            return;
//        }
//
//        // if the heater is already on, let's see if it's time to turn it off...
//        if( heaterOn ) {
//            float offTemp = (mode == PRODUCTION) ? productionHighTemp : dormantHighTemp;
//            if( batteryTemperatureGood ? (batteryTemperature >= offTemp) : (heaterTemperature >= (offTemp + heaterTemperatureOffOffset)) ) {
//                turnHeaterOff();
//            }
//        }
//
//        // if the heater is off, let's see if it's time to turn it on...
//        else {
//            float onTemp = (mode == PRODUCTION) ? productionLowTemp : dormantLowTemp;
//            if( batteryTemperatureGood ? (batteryTemperature <= onTemp) : (heaterTemperature <= onTemp) ) {
//                turnHeaterOn();
//            }
//        }
//    }


    /**
     * Handles all the details of turning the heater on.
     */
    private void turnHeaterOn() {

        // record the heater temperature before we turn it on...
        heaterTemperatureBefore = heaterTemperature;

        // turn on the SSR controlling the heater...
        heaterSSR.low();

        // turn on the LED indicator...
        heaterPowerLED.low();

        // tell the rest of the world what we did...
        publishEvent( new HeaterOn() );
    }


    /**
     * Handles all the details of turning the heater off.
     */
    private void turnHeaterOff() {

        // record the heater temperature before we turn it off...
        heaterTemperatureBefore = heaterTemperature;

        // turn off the SSR controlling the heater...
        heaterSSR.high();

        // turn off the LED indicator...
        heaterPowerLED.high();

        // tell the rest of the world what we did...
        publishEvent( new HeaterOff() );
    }
}
