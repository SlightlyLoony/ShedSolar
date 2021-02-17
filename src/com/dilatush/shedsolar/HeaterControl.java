package com.dilatush.shedsolar;

import com.dilatush.util.AConfig;
import com.dilatush.util.info.Info;
import com.pi4j.io.gpio.*;

import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

import static com.dilatush.shedsolar.LightDetector.Mode;
import static com.dilatush.shedsolar.LightDetector.Mode.LIGHT;

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

    private final Config    config;      // our configuration...
    private final ShedSolar shedSolar;   // our lord and master...

    // our initialized I/O...
    private final GpioPinDigitalInput  ssrSense;
    private final GpioPinDigitalOutput heaterPowerLED;
    private final GpioPinDigitalOutput heaterSSR;

    // startup flag...
    private boolean startedUp;

    // the heater controller we're currently using...
    private HeaterController heaterController;

    // our controllers...
    private final HeaterController normalHeaterController;
    private final HeaterController batteryOnlyHeaterController;
    private final HeaterController heaterOnlyHeaterController;
    private final HeaterController noTempsHeaterController;



    /**
     * Create a new instance of this class with the given configuration.  This class maintains a state machine that controls whether the heater is
     * turned on or off.  This state machine's complexity is mainly due to the all the monitoring it does, and the fallback operating modes that
     * allow it to function even if a sensor dies.  The batteries that the heater controls the temperature of can be destroyed by temperatures that
     * are out of it's operating range, and the batteries are damned expensive - hence the attempt to design in some resilience.
     *
     * @param _config the configuration file
     */
    public HeaterControl( final Config _config ) {

        // save our configuration...
        config = _config;

        // a shortcut to our lord and master...
        shedSolar = ShedSolar.instance;

        // initialize the GPIO pins for the SSR, the SSR sense relay, and the heater on indicator LED...
        GpioController       controller     = ShedSolar.instance.getGPIO();
        ssrSense       = controller.provisionDigitalInputPin(  RaspiPin.GPIO_00, "SSR Sense",        PinPullResistance.PULL_UP );
        heaterPowerLED = controller.provisionDigitalOutputPin( RaspiPin.GPIO_03, "Heater Power LED", PinState.HIGH             );
        heaterSSR      = controller.provisionDigitalOutputPin( RaspiPin.GPIO_05, "Heater SSR",       PinState.HIGH             );
        heaterPowerLED.setShutdownOptions( true, PinState.HIGH );
        heaterSSR.setShutdownOptions(      true, PinState.HIGH );

        // this flag is false until we have valid temperature for heater or battery...
        startedUp = false;

        // instantiate our controllers...
        normalHeaterController      = new NormalHeaterController(      config.normalConfig      );
        batteryOnlyHeaterController = new BatteryOnlyHeaterController( config.batteryOnlyConfig );
        heaterOnlyHeaterController  = new HeaterOnlyHeaterController(  config.heaterOnlyConfig  );
        noTempsHeaterController     = new NoTempsHeaterController(     config.noTempsConfig     );

        // start up our period tick...
        ShedSolar.instance.scheduledExecutor.scheduleWithFixedDelay( this::tick, Duration.ZERO, Duration.ofMillis( config.tickTime ) );

    }


    /**
     * Called periodically to update the heater controller that's active...
     */
    private void tick() {

        // read the current temperatures...
        Info<Float> batteryTemp = shedSolar.batteryTemperature.getInfoSource();
        Info<Float> heaterTemp  = shedSolar.heaterTemperature.getInfoSource();
        Info<Float> ambientTemp = shedSolar.ambientTemperature.getInfoSource();

        // if we haven't started up yet, see if we got battery or heater temperatures; just leave if not...
        if( !startedUp ) {
            startedUp = batteryTemp.isInfoAvailable() || heaterTemp.isInfoAvailable();
            if( !startedUp )
                return;
        }

        // figure out which heater controller we should be using, and switch if necessary...
        if( batteryTemp.isInfoAvailable() && heaterTemp.isInfoAvailable() ) { // the normal case...
            if( !(heaterController instanceof NormalHeaterController) ) {
                if( heaterController != null)
                    heaterController.reset();
                heaterController = normalHeaterController;
            }
        }
        else if( batteryTemp.isInfoAvailable() ) {
            if( !(heaterController instanceof BatteryOnlyHeaterController) ) {
                if( heaterController != null)
                    heaterController.reset();
                heaterController = batteryOnlyHeaterController;
            }
        }
        else if( heaterTemp.isInfoAvailable() ) {
            if( !(heaterController instanceof HeaterOnlyHeaterController) ) {
                if( heaterController != null)
                    heaterController.reset();
                heaterController = heaterOnlyHeaterController;
            }
        }
        else {
            if( !(heaterController instanceof NoTempsHeaterController) ) {
                if( heaterController != null)
                    heaterController.reset();
                heaterController = noTempsHeaterController;
            }
        }

        // figure out what range we should be using...
        Mode mode = shedSolar.light.getInfo();
        float loTemp = (mode == LIGHT) ? config.productionLowTemp  : config.dormantLowTemp;
        float hiTemp = (mode == LIGHT) ? config.productionHighTemp : config.dormantHighTemp;

        // make our context and call our controller...
        HeaterControllerContext context = new HeaterControllerContext(
                ssrSense, heaterPowerLED, heaterSSR, batteryTemp.getInfo(), heaterTemp.getInfo(), ambientTemp.getInfo(), loTemp, hiTemp );
        heaterController.tick( context );
    }


    public static class HeaterControllerContext {

        public final GpioPinDigitalInput  ssrSense;
        public final GpioPinDigitalOutput heaterPowerLED;
        public final GpioPinDigitalOutput heaterSSR;
        public final float batteryTemp;
        public final float heaterTemp;
        public final float ambientTemp;
        public final float loTemp;
        public final float hiTemp;


        private HeaterControllerContext( final GpioPinDigitalInput _ssrSense, final GpioPinDigitalOutput _heaterPowerLED,
                                        final GpioPinDigitalOutput _heaterSSR, final float _batteryTemp, final float _heaterTemp,
                                        final float _ambientTemp, final float _loTemp, final float _hiTemp ) {
            ssrSense = _ssrSense;
            heaterPowerLED = _heaterPowerLED;
            heaterSSR = _heaterSSR;
            batteryTemp = _batteryTemp;
            heaterTemp = _heaterTemp;
            ambientTemp = _ambientTemp;
            loTemp = _loTemp;
            hiTemp = _hiTemp;
        }
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

        public NormalHeaterController.Config normalConfig;
        public BatteryOnlyHeaterController.Config batteryOnlyConfig;
        public HeaterOnlyHeaterController.Config heaterOnlyConfig;
        public NoTempsHeaterController.Config noTempsConfig;

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
}
