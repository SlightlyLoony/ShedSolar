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

    // TODO: add publication of heater on/off, problems...

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
        normalHeaterController      = new NormalHeaterController(      config.normal );
        batteryOnlyHeaterController = new BatteryOnlyHeaterController( config.batteryOnly );
        heaterOnlyHeaterController  = new HeaterOnlyHeaterController(  config.heaterOnly );
        noTempsHeaterController     = new NoTempsHeaterController(     config.noTemps );

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
                LOGGER.info( "Heater control switched to normal heater controller" );
            }
        }
        else if( batteryTemp.isInfoAvailable() ) {
            if( !(heaterController instanceof BatteryOnlyHeaterController) ) {
                if( heaterController != null)
                    heaterController.reset();
                LOGGER.info( "Heater control switched to battery-temperature-only heater controller" );
                heaterController = batteryOnlyHeaterController;
            }
        }
        else if( heaterTemp.isInfoAvailable() ) {
            if( !(heaterController instanceof HeaterOnlyHeaterController) ) {
                if( heaterController != null)
                    heaterController.reset();
                LOGGER.info( "Heater control switched to heater-temperature-only heater controller" );
                heaterController = heaterOnlyHeaterController;
            }
        }
        else {
            if( !(heaterController instanceof NoTempsHeaterController) ) {
                if( heaterController != null)
                    heaterController.reset();
                LOGGER.info( "Heater control switched to no-temperature heater controller" );
                heaterController = noTempsHeaterController;
            }
        }

        // figure out what range we should be using...
        Mode mode = shedSolar.light.getInfo();
        float loTemp = (mode == LIGHT) ? config.lightLowTemp : config.darkLowTemp;
        float hiTemp = (mode == LIGHT) ? config.lightHighTemp : config.darkHighTemp;

        // make our context and call our controller...
        HeaterControllerContext context = new HeaterControllerContext(
                ssrSense, heaterPowerLED, heaterSSR, batteryTemp.getInfo(), heaterTemp.getInfo(), ambientTemp.getInfo(), loTemp, hiTemp );
        heaterController.tick( context );
    }


    /**
     * The context for a tick.
     */
    public static class HeaterControllerContext {
        
        /** The GPIO input pin, low when the mechanical "power sense" relay is energized by the SSR turning on. */
        public final GpioPinDigitalInput  ssrSense;
        
        /** The GPIO output pin that when low turns on the heater power LED. */
        public final GpioPinDigitalOutput heaterPowerLED;
        
        /** The GPIO output pin that when low turns on the heater power solid state relay (SSR). */
        public final GpioPinDigitalOutput heaterSSR;
        
        /** The battery temperature in degrees Celcius. */
        public final float                batteryTemp;

        /** The heater temperature in degrees Celcius. */
        public final float                heaterTemp;

        /** The ambient temperature in degrees Celcius. */
        public final float                ambientTemp;

        /** The lowest target battery temperature in degrees Celcius. */
        public final float                loTemp;

        /** The highest target battery temperature in degrees Celcius. */
        public final float                hiTemp;


        /** Create a new instance of this class. */
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
         * The time (in milliseconds) between "ticks" of the heater control state machine.  This value must be in the range [1,000..15,000]
         * milliseconds, and the default value is 5,000 milliseconds (five seconds).
         */
        public long tickTime;

        /**
         * The lowest battery temperature (in degrees Celcius) allowed when in dark mode.  This value must be in the range [-10..25], and it must
         * be less than {@link #darkHighTemp} and less than {@link #lightLowTemp}.  Its default value is 0C.
         */
        public float darkLowTemp = 0;

        /**
         * The highest battery temperature (in degrees Celcius) allowed when in dark mode.  This value must be in the range [-10..25], and it must
         * be greater than {@link #darkLowTemp} and less than {@link #lightHighTemp}.  Its default value is 5C.
         */
        public float darkHighTemp = 5;

        /**
         * The lowest battery temperature (in degrees Celcius) allowed when in light mode.  This value must be in the range [0..40], and it must
         * be less than {@link #lightHighTemp} and greater than {@link #darkLowTemp}.  Its default value is 25C.
         */
        public float lightLowTemp = 25;

        /**
         * The highest battery temperature (in degrees Celcius) allowed when in light mode.  This value must be in the range [0..40], and it must
         * be greater than {@link #lightLowTemp} and greater than {@link #darkHighTemp}.  It's default value is 30C.
         */
        public float lightHighTemp = 30;

        /** Normal heater controller configuration. */
        public NormalHeaterController.Config      normal;

        /** Battery-temperatures-only heater controller configuration. */
        public BatteryOnlyHeaterController.Config batteryOnly;

        /** H */
        public HeaterOnlyHeaterController.Config  heaterOnly;

        /**  */
        public NoTempsHeaterController.Config     noTemps;

        
        /**
         * Verify the fields of this configuration.
         */
        @Override
        public void verify( final List<String> _messages ) {
            validate( () -> ((darkLowTemp >= -10) && (darkLowTemp <= 25)), _messages,
                    "Heater Control dark low temperature is out of range: " + darkLowTemp );
            validate( () -> darkLowTemp < darkHighTemp, _messages,
                    "Heater Control dark low temperature is not less than dark high temperature: " + darkLowTemp );
            validate( () -> darkLowTemp < lightLowTemp, _messages,
                    "Heater Control dark low temperature is not less than light low temperature: " + darkLowTemp );
            validate( () -> ((darkHighTemp >= -10) && (darkHighTemp <= 25)), _messages,
                    "Heater Control dark high temperature is out of range: " + darkHighTemp );
            validate( () -> darkHighTemp < lightHighTemp, _messages,
                    "Heater Control dark high temperature is not less than light high temperature: " + darkHighTemp );
            validate( () -> ((lightLowTemp >= 0) && (lightLowTemp <= 40)), _messages,
                    "Heater Control light low temperature is out of range: " + lightLowTemp );
            validate( () -> lightLowTemp < lightHighTemp, _messages,
                    "Heater Control light low temperature is not less than light high temperature: " + lightLowTemp );
            validate( () -> ((lightHighTemp >= 0) && (lightHighTemp <= 40)), _messages,
                    "Heater Control light high temperature is out of range: " + lightHighTemp );
            validate( () -> ((tickTime >= 1000) && (tickTime <= 15000)), _messages,
                    "Heater Control tick time is out of range: " + tickTime );

            normal.verify(      _messages );
            batteryOnly.verify( _messages );
            heaterOnly.verify(  _messages );
            noTemps.verify(     _messages );
        }
    }
}
