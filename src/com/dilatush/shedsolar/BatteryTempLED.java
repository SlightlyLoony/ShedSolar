package com.dilatush.shedsolar;

import com.dilatush.util.AConfig;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.PinState;
import com.pi4j.io.gpio.RaspiPin;

import java.util.List;
import java.util.logging.Logger;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * <p>Controls the battery temperature LED.</p>
 * <p>This LED normally flashes at a fixed interval, but the duty cycle of the flash is proportional to the battery temperature.  The duty cycle is 0%
 * (LED completely off) when the battery is at or below the lowest operable temperature, and is 100% (LED completely on) when the battery is at or
 * above its highest operable temperature.</p>
 * <p>If the battery temperature cannot be read for any reason, the LED flashes very quickly at a constant rate.</p>
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class BatteryTempLED {

    @SuppressWarnings( "unused" )
    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private final float                minTemp;
    private final float                maxTemp;
    private final long                 normalInterval;
    private final long                 errorInterval;
    private final GpioPinDigitalOutput led;

    private boolean                    started;

    // these are set on the events thread, read on the timer thread...
    private volatile float   batteryTemp;
    private volatile boolean goodBatteryTemp;


    /**
     * Creates a new instance of this class, configured by the given {@link Config}.
     *
     * @param _config the configuration file for this app
     */
    public BatteryTempLED( final Config _config ) {

        // get our configuration...
        minTemp        = _config.minTemp;
        maxTemp        = _config.maxTemp;
        normalInterval = _config.normalInterval;
        errorInterval  = _config.errorInterval;

        batteryTemp = 0;
        started = false;

        // set up our GPIO pin...
        led = ShedSolar.instance.getGPIO().provisionDigitalOutputPin( RaspiPin.GPIO_02, "Battery Temperature", PinState.HIGH );
        led.setShutdownOptions( true, PinState.HIGH );

        // subscribe to battery temperature readings...
// TODO        subscribeToEvent( event -> handleBatteryTempEvent( (BatteryTemperature) event ), BatteryTemperature.class );
    }


    /**
     * Validatable POJO for configuring {@link BatteryTempLED} (see {@link BatteryTempLED#BatteryTempLED(Config)}).
     */
    public static class Config extends AConfig {

        /**
         * The minimum battery temperature (in degrees Celcius) that can be shown by the battery temperature LED (when the LED is solid off).  This
         * value must be less than the value of {@link #maxTemp}, and must be in the range [-5.0..60.0].
         */
        public float minTemp        = 0.0f;

        /**
         * The maximum battery temperature (in degrees Celcius) that can be shown by the battery temperature LED (when the LED is solidly on).  This
         * value must be greater than the value of {@link #minTemp}, and must be in the range [-5.0..60.0].
         */
        public float maxTemp        = 45.0f;

        /**
         * The interval, in milliseconds, between the start of each normal flash of the battery temperature LED, indicating that the battery
         * temperature can be read.
         */
        public long  normalInterval = 2000;

        /**
         * The interval, in milliseconds, between the start of each error flash of the battery temperature LED, indicating that the battery
         * temperature cannot be read.
         */
        public long  errorInterval  = 400;


        /**
         * Verify the fields of this configuration.
         */
        @Override
        public void verify( final List<String> _messages ) {
            validate( () -> minTemp < maxTemp, _messages,
                    "Battery Temperature LED minimum temperature is not less than the maximum temperature: " + minTemp );
            validate( () -> ((minTemp >= -5) && (minTemp <= 60)), _messages,
                    "Battery Temperature LED minimum temperature is out of range: " + minTemp );
            validate( () -> ((maxTemp >= -5) && (maxTemp <= 60)), _messages,
                    "Battery Temperature LED maximum temperature is out of range: " + maxTemp );
            validate( () -> ((normalInterval >= 500) && (normalInterval <= 5000)), _messages,
                    "Battery Temperature LED normal interval is out of range: " + normalInterval );
            validate( () -> ((errorInterval >= 250) && (errorInterval <= 2500)), _messages,
                    "Battery Temperature LED error interval is out of range: " + errorInterval );
            validate( () -> errorInterval * 2 <  normalInterval, _messages,
                    "Battery Temperature LED error interval more than half the normal interval: " + errorInterval );
        }
    }


//    /**
//     * Handle the battery temperature event that the constructor subscribed us to.
//     *
//     * @param _event the battery temperature event
//     */
//    public void handleBatteryTempEvent( final BatteryTemperature _event ) {
//
//        // record our latest battery temperature information...
//        goodBatteryTemp = _event.goodMeasurement;
//        batteryTemp = _event.degreesC;
//
//        // if we haven't yet started the LED flashing, do so now...
//        if( !started ) {
//            started = true;
//            new On().run();
//        }
//    }


    /**
     * Computes the duration (in milliseconds) for the "on" portion of the battery temperature LED when there is a valid temperature reading.
     *
     * @param _temp the battery temperature in degrees Celcius
     * @return the "on" duration in milliseconds
     */
    private long onMS( final float _temp ) {
        if( _temp <= minTemp ) return 0;
        if( _temp >= maxTemp ) return normalInterval;
        return Math.round( normalInterval * ((_temp - minTemp) / (maxTemp - minTemp)) );
    }


    private class On implements Runnable {

        @Override
        public void run() {
            led.setState( PinState.LOW );

            if( goodBatteryTemp ) {
                ShedSolar.instance.scheduledExecutor.schedule( new Off(), onMS( batteryTemp ), MILLISECONDS );
                ShedSolar.instance.scheduledExecutor.schedule( new On(), normalInterval, MILLISECONDS );
            }
            else {
                ShedSolar.instance.scheduledExecutor.schedule( new Off(), errorInterval / 2, MILLISECONDS );
                ShedSolar.instance.scheduledExecutor.schedule( new On(), errorInterval, MILLISECONDS );
            }
        }
    }


    private class Off  implements Runnable {

        @Override
        public void run() {
            led.setState( PinState.HIGH );
        }
    }
}

