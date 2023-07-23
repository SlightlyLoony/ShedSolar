package com.dilatush.shedsolar;

import com.dilatush.util.config.AConfig;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.PinState;
import com.pi4j.io.gpio.RaspiPin;

import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;

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

    private final ShedSolar            shedSolar;
    private final float                minTemp;
    private final float                maxTemp;
    private final long                 normalInterval;
    private final long                 errorInterval;
    private final GpioPinDigitalOutput led;


    /**
     * Creates a new instance of this class, configured by the given {@link Config}.
     *
     * @param _config the configuration file for this app
     */
    public BatteryTempLED( final Config _config ) {

        // get our lord and master...
        shedSolar      = ShedSolar.instance;

        // get our configuration...
        minTemp        = _config.minTemp;
        maxTemp        = _config.maxTemp;
        normalInterval = _config.normalInterval;
        errorInterval  = _config.errorInterval;

        // set up our GPIO pin...
        led = ShedSolar.instance.getGPIO().provisionDigitalOutputPin( RaspiPin.GPIO_02, "Battery Temperature", PinState.HIGH );
        led.setShutdownOptions( true, PinState.HIGH );

        // start things up by directly running the "on" event handler...
        on();
    }


    /**
     * LED "on" event, run by the constructor to start things up, otherwise by schedules set in this method.
     */
    private void on() {

        // turn the LED on (it's connected from the output pin through a load resistor to Vcc)...
        led.setState( PinState.LOW );

        // decide what to do based on whether we have a battery temperature reading available...
        // if we have temperature data, then flash the LED so its duty cycle reflects the temperature
        if( shedSolar.batteryTemperature.isInfoAvailable() ) {

            // read the temperature and figure out how long we want to keep the LED on...
            float batteryTemp = shedSolar.batteryTemperature.getInfo();
            long onMS =  // how many milliseconds to keep the LED on...
                    (batteryTemp <= minTemp )
                    ? 0
                    : (batteryTemp >= maxTemp)
                      ? normalInterval
                      : Math.round( normalInterval * ((batteryTemp - minTemp) / (maxTemp - minTemp)) );

            // schedule our next off and on events...
            shedSolar.scheduledExecutor.schedule( this::off, Duration.ofMillis( onMS           ) );
            shedSolar.scheduledExecutor.schedule( this::on,  Duration.ofMillis( normalInterval ) );
        }

        // otherwise, we're going to fast blink and possibly get someone's attention...
        else {
            shedSolar.scheduledExecutor.schedule( this::off, Duration.ofMillis( errorInterval / 2 ) );
            shedSolar.scheduledExecutor.schedule( this::on,  Duration.ofMillis( errorInterval     ) );
        }
    }


    /**
     * LED "off" event, run on a schedule set in {@link #on()}.
     */
    private void off() {

        // turn off the LED (it's connected from the output pin through a load resistor to Vcc)...
        led.setState( PinState.HIGH );
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
}

