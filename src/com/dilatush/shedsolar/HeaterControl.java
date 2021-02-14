package com.dilatush.shedsolar;

import com.dilatush.util.AConfig;
import com.pi4j.io.gpio.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

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
    private final long                 maxHeaterOnVerifyTime;
    private final long                 maxHeaterOffVerifyTime;
    private final long                 heaterCooldownTime;
    private final int                  maxHeaterStartAttempts;
    private final float                heaterTempChangeSenseThreshold;
    private final float                batteryTempChangeSenseThreshold;
    private final float                maxHeaterTemperature;
    private final long                 maxOpenLoopHeaterRunTime;

    // the following mutable variables are accessed both from the events thread and the scheduled thread...
    private volatile float            heaterTemperature;
    private volatile boolean          heaterTemperatureGood;
    private volatile float            batteryTemperature;
    private volatile boolean          batteryTemperatureGood;
    private volatile float            ambientTemperature;
    private volatile boolean          ambientTemperatureGood;
    private volatile TemperatureRange range;

    // our state machine...
    private final HeaterStateMachine  stateMachine;


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
        productionRange                 = new TemperatureRange( _config.productionLowTemp, _config.productionHighTemp );
        dormantRange                    = new TemperatureRange( _config.dormantLowTemp,    _config.dormantHighTemp    );
        maxHeaterOnVerifyTime           = _config.maxHeaterOnVerifyTime;
        maxHeaterOffVerifyTime          = _config.maxHeaterOffVerifyTime;
        heaterCooldownTime              = _config.heaterCooldownTime;
        maxHeaterStartAttempts          = _config.maxHeaterStartAttempts;
        heaterTempChangeSenseThreshold  = _config.heaterTempChangeSenseThreshold;
        batteryTempChangeSenseThreshold = _config.batteryTempChangeSenseThreshold;
        maxHeaterTemperature            = _config.maxHeaterTemperature;
        maxOpenLoopHeaterRunTime        = _config.maxOpenLoopHeaterRunTime;
        long tickTime                   = _config.tickTime;

        // initialize the GPIO pins for the SSR, the SSR sense relay, and the heater on indicator LED...
        GpioController       controller     = ShedSolar.instance.getGPIO();
        ssrSense       = controller.provisionDigitalInputPin(  RaspiPin.GPIO_00, "SSR Sense",        PinPullResistance.PULL_UP );
        heaterPowerLED = controller.provisionDigitalOutputPin( RaspiPin.GPIO_03, "Heater Power LED", PinState.HIGH             );
        heaterSSR      = controller.provisionDigitalOutputPin( RaspiPin.GPIO_05, "Heater SSR",       PinState.HIGH             );
        heaterPowerLED.setShutdownOptions( true, PinState.HIGH );
        heaterSSR.setShutdownOptions(      true, PinState.HIGH );

        // we start out our state machine by assuming that we're in production mode with the heater off...
        range = productionRange;

        // subscribe to the events we want to monitor...
//        subscribeToEvent( event -> handleTempModeEvent(            (TempMode)           event ),  TempMode.class            );
//        subscribeToEvent( event -> handleBatteryTemperatureEvent(  (BatteryTemperature) event ),  BatteryTemperature.class  );
//        subscribeToEvent( event -> handleHeaterTemperatureEvent(   (HeaterTemperature)  event ),  HeaterTemperature.class   );
//        subscribeToEvent( event -> handleAmbientTemperatureEvent(  (AmbientTemperature)  event ), AmbientTemperature.class  );

        // schedule our state machine tick...
        stateMachine = new HeaterStateMachine();
        ShedSolar.instance.scheduledExecutor.scheduleAtFixedRate( stateMachine, 0, tickTime, MILLISECONDS );
    }


    public static class Config extends AConfig {

        /**
         * The lowest battery temperature (in degrees Celcius) allowed when in dormant mode.  This value must be in the range [-10..25], and it must
         * be less than {@link #dormantHighTemp} and less than {@link #productionLowTemp}.  Its default value is 0C.
         */
        public float dormantLowTemp;

        /**
         * The highest battery temperature (in degrees Celcius) allowed when in dormant mode.  This value must be in the range [-10..25], and it must
         * be greater than {@link #dormantLowTemp} and less than {@link #productionHighTemp}.  Its default value is 5C.
         */
        public float dormantHighTemp;

        /**
         * The lowest battery temperature (in degrees Celcius) allowed when in production mode.  This value must be in the range [0..40], and it must
         * be less than {@link #productionHighTemp} and greater than {@link #dormantLowTemp}.  Its default value is 25C.
         */
        public float productionLowTemp;

        /**
         * The highest battery temperature (in degrees Celcius) allowed when in production mode.  This value must be in the range [0..40], and it must
         * be greater than {@link #productionLowTemp} and greater than {@link #dormantHighTemp}.  It's default value is 30C.
         */
        public float productionHighTemp;

        /**
         * The heater thermocouple measures the temperature of the air blowing out of the heater.  When turning the heater on, the temperature is
         * measured just before turning it on, and then the heater's operation is verified when the temperature increases by at least
         * {@link #heaterTempChangeSenseThreshold} degrees C.  This value determines the maximum time (in milliseconds) to wait for that
         * verification.  If the time is exceeded, the heater has failed to start.  Note that the heater failing to start isn't necessarily fatal,
         * as it may simply be too hot and in need of a cooldown cycle.  This value must be in the range [0..600,000], and the default value is
         * 150,000 (or two and a half minutes).
         */
        public long  maxHeaterOnVerifyTime;

        /**
         * The heater thermocouple measures the temperature of the air blowing out of the heater.  When turning the heater off, the temperature is
         * measured just before turning it off, and then the heater's operation is verified when the temperature decreases by at least
         * {@link #heaterTempChangeSenseThreshold} degrees C.  This value determines the maximum time (in milliseconds) to wait for that
         * verification.  If the time is exceeded, the heater has failed to shut off.  This value must be in the range [0..600,000], and the default
         * value is 180,000 (or three minutes).
         */
        public long  maxHeaterOffVerifyTime;

        /**
         * When the heater fails to turn on, a heater cooldown cycle is initiated for up to {@link #maxHeaterStartAttempts} times.  This value is
         * multiplied by the retry attempt number to determine how long to wait (in milliseconds) for cooling down (with the heater off).  For
         * example, if this value was set to 180,000 (for 3 minutes), then the cooldown period would be 3 minutes on the first heater start retry, 6
         * minutes on the second retry, 9 minutes on the third retry, and so on.  This value must be in the range [60,000..600,000] milliseconds.
         * The default value is 180,000.
         */
        public long  heaterCooldownTime;

        /**
         * This value determines how many times to attempt starting the heater before assuming it has actually failed.  The heater has an
         * overtemperature "breaker" that can prevent it from starting if the internal temperature of the heater is too high.  To handle this, if
         * we try and fail to start the heater, then we wait for a while (see {@link #heaterCooldownTime}) to let the heater cool down and try again.
         * This value must be in the range [1..10], and its default value is 4.
         */
        public int   maxHeaterStartAttempts;

        /**
         * This value defines the amount of change in the temperature (in degrees Celcius) measured by the thermocouple in the heater's air output
         * must be seen to verify that the heater has successfully turned on or off.  This value must be in the range [1..40] degrees Celcius, and
         * its default value is 10 degrees Celcius.
         */
        public float heaterTempChangeSenseThreshold;

        /**
         * This value defines the amount of change in the temperature (in degrees Celcius) measured by the thermocouple under the batteries
         * must be seen to verify that the batteries are being heated or cooled.  This value must be in the range [0.25..10] degrees Celcius, and
         * its default value is 2.5 degrees Celcius.
         */
        public float batteryTempChangeSenseThreshold;

        /**
         * This value defines the maximum temperature (in degrees Celcius) allowed in the heater's air output.  If this temperature is exceeded, the
         * heater will be shut down even if the batteries' temperature is too low.  This is a safety feature in case the heater's internal
         * overtemperature "breaker" fails.  The heater will be restarted after a cooldown period.  This value must be in the range [30..75] degrees
         * Celcius, and its default value is 50C.
         */
        public float maxHeaterTemperature;

        /**
         * If the battery temperature thermocouple fails, but we sense that the heater temperature is below the current low battery temperature
         * threshold, then we assume that the batteries need to be heated and we turn the heater on.  However, because we can't sense the actual
         * battery temperature we just run "open loop", leaving the heater on for a fixed amount of time.  This value determines that time, in
         * milliseconds.  Its value must be in the range [60,000..600,000] milliseconds (one minute to ten minutes); the default value is 300,000
         * milliseconds (five minutes).
         */
        public long  maxOpenLoopHeaterRunTime;

        /**
         * The time (in milliseconds) between "ticks" of the heater control state machine.  This value must be in the range [1,000..15,000]
         * milliseconds, and the default value is 5,000 milliseconds (five seconds).
         */
        public long tickTime;


        public Config() {
            tickTime                        = 5000;    // 5 seconds...
            maxHeaterOnVerifyTime           = 150000;  // 2.5 minutes...
            maxHeaterOffVerifyTime          = 180000;  // 3 minutes...
            heaterCooldownTime              = 180000;  // 3 minutes...
            maxOpenLoopHeaterRunTime        = 300000;  // 5 minutes...
            dormantLowTemp                  = 0;
            dormantHighTemp                 = 5;
            productionLowTemp               = 25;
            productionHighTemp              = 30;
            heaterTempChangeSenseThreshold  = 10.0f;
            batteryTempChangeSenseThreshold = 2.5f;
            maxHeaterTemperature            = 50;
            maxHeaterStartAttempts          = 4;
        }

        /**
         * Verify the fields of this configuration.
         */
        @Override
        public void verify( final List<String> _messages ) {
            validate( () -> ((dormantLowTemp >= -10) && (dormantLowTemp <= 25)), _messages,
                    "Heater Control dormant low temperature is out of range: " + dormantLowTemp );
            validate( () -> dormantLowTemp < dormantHighTemp, _messages,
                    "Heater Control dormant low temperature is not less than dormant high temperature: " + dormantLowTemp );
            validate( () -> dormantLowTemp < productionLowTemp, _messages,
                    "Heater Control dormant low temperature is not less than production low temperature: " + dormantLowTemp );
            validate( () -> ((dormantHighTemp >= -10) && (dormantHighTemp <= 25)), _messages,
                    "Heater Control dormant high temperature is out of range: " + dormantHighTemp );
            validate( () -> dormantHighTemp < productionHighTemp, _messages,
                    "Heater Control dormant high temperature is not less than production high temperature: " + dormantHighTemp );
            validate( () -> ((productionLowTemp >= 0) && (productionLowTemp <= 40)), _messages,
                    "Heater Control production low temperature is out of range: " + productionLowTemp );
            validate( () -> productionLowTemp < productionHighTemp, _messages,
                    "Heater Control production low temperature is not less than production high temperature: " + productionLowTemp );
            validate( () -> ((productionHighTemp >= 0) && (productionHighTemp <= 40)), _messages,
                    "Heater Control production high temperature is out of range: " + productionHighTemp );
            validate( () -> ((maxHeaterOnVerifyTime >= 0) && (maxHeaterOnVerifyTime <= 600000)), _messages,
                    "Heater Control max heater on verify type is out of range: " + maxHeaterOnVerifyTime);
            validate( () -> ((maxHeaterOffVerifyTime >= 0) && (maxHeaterOffVerifyTime <= 600000)), _messages,
                    "Heater Control max heater off verify type is out of range: " + maxHeaterOffVerifyTime);
            validate( () -> ((heaterCooldownTime >= 60000) && (heaterCooldownTime <=600000)), _messages,
                    "Heater Control heater cooldown time out of range: " + heaterCooldownTime );
            validate( () -> ((maxHeaterStartAttempts >= 1) && (maxHeaterStartAttempts <= 10)), _messages,
                    "Heater Control max heater start attempts is out of range: " + maxHeaterStartAttempts );
            validate( () -> ((heaterTempChangeSenseThreshold >= 1) && (heaterTempChangeSenseThreshold <= 40)), _messages,
                    "Heater Control heater temperature change sense threshold is out of range: " + heaterTempChangeSenseThreshold );
            validate( () -> ((batteryTempChangeSenseThreshold >= 0.25f) && (batteryTempChangeSenseThreshold <= 10)), _messages,
                    "Heater Control battery temperature change sense threshold is out of range: " + batteryTempChangeSenseThreshold );
            validate( () -> ((maxOpenLoopHeaterRunTime >= 60000) && (maxOpenLoopHeaterRunTime <= 600000)), _messages,
                    "Heater Control max open loop heater run time is out of range: " + maxOpenLoopHeaterRunTime );
            validate( () -> ((tickTime >= 1000) && (tickTime <= 15000)), _messages,
                    "Heater Control tick time is out of range: " + tickTime );
        }
    }

    // TODO: make maxHeaterOffVerifyTime actually work (currently unused!?!?!)...
    // TODO: make maxHeaterTemperature actually work (currently unused!)...
    // TODO: sense heater failure during a heating cycle...

    /**
     * This is the heart of the state machine.  The {@link #run()} method is called at the tick interval, and the current state's
     * {@link HeaterState#onTick()} method is called.  If the {@link HeaterState#onTick()} returns a new state, that state is switched to.  That's
     * all the logic - everything else is in the state classes.
     */
    private class HeaterStateMachine implements Runnable {

        private final AtomicInteger      turnOnAttempts;         // the number of attempts made to turn on the heater...
        private volatile HeaterState     currentState;
        private volatile HeaterState     nextState;


        public HeaterStateMachine() {
            turnOnAttempts = new AtomicInteger();
            currentState   = null;
            nextState      = new Idle();
        }


        @Override
        public void run() {
            try {

                // TODO: add safety checks (battery over/under temp)...

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
        public void onEntry() {

            // zero our heater start attempt counter...
            stateMachine.turnOnAttempts.set( 0 );
        }

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

            // we get here only if both thermocouples are bad - all we can do is wait some more...
            // the bad temperature data will already be known to the status code...
            return null;
         }
    }



    /**
     * The {@link TurnHeaterOn} state waits for the SSR sense relay to engage and for rising heater temperatures to be sensed.  If we get both of
     * these within our time window, we'll exit normally - otherwise, things get more complicated.
     */
    private class TurnHeaterOn implements HeaterState {

        private Instant heaterStarted;               // the time we turned on the heater...
        boolean startingHeaterTempGood;
        float   startingHeaterTemp;
        boolean startingBatteryTempGood;
        float   startingBatteryTemp;


        @Override
        public void onEntry() {

            // some setup...
            heaterStarted = Instant.now();
            startingHeaterTempGood  = heaterTemperatureGood;
            startingHeaterTemp      = heaterTemperature;
            startingBatteryTempGood = batteryTemperatureGood;
            startingBatteryTemp     = batteryTemperature;

            // turn on the SSR controlling the heater...
            heaterSSR.low();

            // turn on the LED indicator...
            heaterPowerLED.low();

            // tell the rest of the world what we did...
//            publishEvent( new HeaterOn() );
        }


        @Override
        public HeaterState onTick() {

            // if either sensed temperature was bad, fix it if it miraculously cured itself (we're optimists, what can we say?)...
            if( !startingHeaterTempGood && heaterTemperatureGood  ) {
                startingHeaterTemp = heaterTemperature;
                startingHeaterTempGood = true;
            }
            if( !startingBatteryTempGood && batteryTemperatureGood ) {
                startingBatteryTemp = batteryTemperature;
                startingBatteryTempGood = true;
            }

            /*
             * The following code is the (hopefully!) normal case, when we have good temperature data for the heater...
             */
            if( heaterTemperatureGood ) {

                // if we see the temperature increase, then it's time to move right along...
                if( heaterTemperature - startingHeaterTemp >= heaterTempChangeSenseThreshold ) {

                    // squawk if the SSR sense relay isn't on, as it may be broken...
                    checkSSRSenseOn();

                    // then go on to a normal heater run...
                    LOGGER.finest( "Heater verification: increasing heater temperature sensed" );
                    return new HeaterRun( heaterStarted );
                }
            }

            /*
             * The following code is a degraded case, when we have good temperature for the battery, but not the heater...
             */
            else if( batteryTemperatureGood ) {

                // if we see the temperature increase, then it's time to move right along...
                if( batteryTemperature - startingBatteryTemp > batteryTempChangeSenseThreshold ) {

                    // squawk if the SSR sense relay isn't on, as it may be broken...
                    checkSSRSenseOn();

                    // then go on to a normal heater run...
                    LOGGER.finest( "Heater verification: increasing battery temperature sensed" );
                    return new HeaterRun( heaterStarted );
                }
            }

            /*
             * The following code is a fatally degraded case, wherein we have no temperature data at all.  This can only happen if the sensors
             * fail between the Armed event onTick() and this onTick() - possible, but not very likely...
             */
            else {

                // the only safe thing to do now is to turn off the heater, scream bloody murder, and wait for good temperature data...
                return new TurnHeaterOff( new Armed() );
            }

            /*
             * We get here if we have NOT verified that the heater is working (by seeing increasing temperatures).
             */

            // if we've have more time to wait for verification, then we just leave...
            if( Duration.between( heaterStarted, Instant.now() ).toMillis() <= maxHeaterOnVerifyTime ) {
                return null;
            }

            // if we sense that the SSR is on then it looks like either the heater has failed outright, or its thermal interlock has tripped...
            // if we've already tried the max number of times, then we abort as it looks like a heater failure...
            if( ssrSense.isLow() ) {

                // if we still have more attempts to make at a heater start, do it...
                if( stateMachine.turnOnAttempts.get() < maxHeaterStartAttempts ) {
                    LOGGER.finest( "Heater verification: SSR sensed, but no temperature increases; attempting restart after cooldown" );
                    stateMachine.turnOnAttempts.incrementAndGet();  // keep track of our feeble attempts to start the heater...
                    return new TurnHeaterOff( new TurnHeaterOn() );
                }

                // otherwise, it looks like our heater has failed, so we're just gonna abort...
                else {
                    LOGGER.finest( "Heater verification: SSR sensed, but no temperature increases; possible heater failure" );
//                    publishEvent( new HeaterFailure() );
//                    publishEvent( new HeaterControlAbort() );
                    return new Abort();
                }
            }

            // otherwise if we're sensing that the SSR is off, it looks like we have an SSR failure, so all we can do is scream and abort...
            LOGGER.finest( "Heater verification: SSR not sensed, and temperature increases; possible SSR failure" );
//            publishEvent( new SSRStuckOff() );
            return new Abort();
        }


        private void checkSSRSenseOn() {
            if( ssrSense.isHigh() ) {
                LOGGER.finest( "Heater verification: SSR sense not on" );
//                publishEvent( new SSRSenseFailure( "stuck off" ) );
            }
        }
    }


    private static class Abort implements HeaterState {

        @Override
        public HeaterState onTick() {

            // we just return null here, as we're not going to leave this state without human intervention...
            return null;
        }
    }


    private class HeaterRun implements HeaterState {

        private final Instant heaterStarted;  // when heater was turned on...


        private HeaterRun( final Instant _heaterStarted ) {
            heaterStarted = _heaterStarted;
        }


        @Override
        public HeaterState onTick() {

            // if our battery temperature can be read, then it's our operative test...
            if( batteryTemperatureGood ) {

                // if the battery temperature is above the high range, its time to turn off the heater...
                if( batteryTemperature > range.hi ) {
                    LOGGER.finest( "Heater run: battery temperature exceeded high range" );
                    return new TurnHeaterOff( new Armed() );
                }

                // otherwise, keep on trucking...
                else
                    return null;
            }

            // if we can't read the battery temperature, but we CAN read the heater temperature, then it's our operative test...
            else if( heaterTemperatureGood ) {

                // if the heater temperature exceeds our max, or if the heater run has exceeded our max, then it's time to turn off the heater...
                if( heaterTemperature > maxHeaterTemperature ) {
                    LOGGER.finest( "Heater run: heater temperature exceeded high range" );
                    return new TurnHeaterOff( new Armed() );
                }

                // if the heater has run longer than our maximum run time, then it's time to turn off the heater...
                if( Duration.between( heaterStarted, Instant.now() ).toMillis() > maxOpenLoopHeaterRunTime ) {
                    LOGGER.finest( "Heater run: heater run time exceeded" );
                    return new TurnHeaterOff( new Armed() );
                }

                // otherwise, keep on trucking...
                else
                    return null;

            }

            // if we get here, then our temperature sensing has died since we started the heater - time to abort...
            return new TurnHeaterOff( new Abort() );
        }
    }


    /**
     * The {@link TurnHeaterOff} state turns off the heater, verifies that it's off, and waits for a cooldown period (to prevent the heater
     * from trying to turn back on while it's still hot and the thermal interlock is tripped).
     */
    private class TurnHeaterOff implements HeaterState {

        private Instant heaterTurnedOff;
        private final HeaterState afterHeaterOff;


        public TurnHeaterOff( final HeaterState _afterHeaterOff ) {
            afterHeaterOff = _afterHeaterOff;
        }


        @Override
        public void onEntry() {

            // mark our time...
            heaterTurnedOff = Instant.now();

            // turn off the SSR controlling the heater...
            heaterSSR.high();

            // turn off the LED indicator...
            heaterPowerLED.high();

            // tell the rest of the world what we did...
//            publishEvent( new HeaterOff() );
        }


        @Override
        public HeaterState onTick() {

            // if our cooldown period is over, time to move on...
            if( Duration.between( heaterTurnedOff, Instant.now() ).toMillis() >= heaterCooldownTime * stateMachine.turnOnAttempts.get() ) {
                LOGGER.finest( "Turn heater off: cooldown period finished" );
                return afterHeaterOff;
            }

            // otherwise, just leave...
            return null;
        }
    }


    /*
     * The following methods implement event handlers.
     */

    /*
     * Handle a mode (production/dormant) event.
     *
     * @param _event the mode event
     */
//    private void handleTempModeEvent( TempMode _event ) {
//
//        LOGGER.finest( _event.toString() );
//        mode = _event.mode;
//        range = (mode == PRODUCTION) ? productionRange : dormantRange;
//    }


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
//    private void handleBatteryTemperatureEvent( BatteryTemperature _event ) {
//
//        LOGGER.finest( _event.toString() );
//        batteryTemperature = _event.degreesC;
//        batteryTemperatureGood = _event.goodMeasurement;
//    }


    /**
     * Handle a heater temperature event.
     *
     * @param _event the heater temperature event
     */
//    private void handleHeaterTemperatureEvent( HeaterTemperature _event ) {
//
//        LOGGER.finest( _event.toString() );
//        heaterTemperature = _event.degreesC;
//        heaterTemperatureGood = _event.goodMeasurement;
//    }


    /**
     * Handle an ambient temperature event.
     *
     * @param _event the heater temperature event
     */
//    private void handleAmbientTemperatureEvent( AmbientTemperature _event ) {
//
//        LOGGER.finest( _event.toString() );
//        ambientTemperature = _event.degreesC;
//        ambientTemperatureGood = true;
//    }
}
