package com.dilatush.shedsolar;

import com.dilatush.shedsolar.events.BatteryTemperature;
import com.dilatush.util.Config;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.PinState;
import com.pi4j.io.gpio.RaspiPin;

import static com.dilatush.shedsolar.App.schedule;
import static com.dilatush.util.syncevents.SynchronousEvents.subscribeToEvent;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * <p>Controls the battery temperature LED.</p>
 * <p>This LED normally flashes at a fixed interval, but the duty cycle of the flash is proportional to the battery temperature.  The duty cycle is 0%
 * (LED completely off) when the battery is at or below the lowest operable temperture, and is 100% (LED completely on) when the battery is at or
 * above its highest operable temperature.</p>
 * <p>If the battery temperature cannot be read for any reason, the LED flashes very quickly at a constant rate.</p>
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class BatteryTempLED {

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
     * Creates a new instance of this class, configured by the given configuration file.
     *
     * @param _config the configuration file for this app
     */
    public BatteryTempLED( final Config _config ) {

        // get our configuration...
        minTemp        = _config.optFloatDotted( "batteryTemperatureLED.minTemp",         0.0f );
        maxTemp        = _config.optFloatDotted( "batteryTemperatureLED.maxTemp",        45.0f );
        normalInterval = _config.optLongDotted(  "batteryTemperatureLED.normalInterval", 2000  );
        errorInterval  = _config.optLongDotted(  "batteryTemperatureLED.errorInterval",   400  );

        batteryTemp = 0;
        started = false;

        // set up our GPIO pin...
        led = App.instance.gpio.provisionDigitalOutputPin( RaspiPin.GPIO_02, "Battery Temperature", PinState.HIGH );
        led.setShutdownOptions( true, PinState.HIGH );

        // subscribe to battery temperature readings...
        subscribeToEvent( event -> handleBatteryTempEvent( (BatteryTemperature) event ), BatteryTemperature.class );
    }


    /**
     * Handle the battery temperature event that the constructor subscribed us to.
     *
     * @param _event the battery temperature event
     */
    public void handleBatteryTempEvent( final BatteryTemperature _event ) {

        // record our latest battery temperature information...
        goodBatteryTemp = _event.goodMeasurement;
        batteryTemp = _event.degreesC;

        // if we haven't yet started the LED flashing, do so now...
        if( !started ) {
            started = true;
            new On().run();
        }
    }


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
                schedule( new Off(), onMS( batteryTemp ), MILLISECONDS );
                schedule( new On(), normalInterval, MILLISECONDS );
            }
            else {
                schedule( new Off(), errorInterval / 2, MILLISECONDS );
                schedule( new On(), errorInterval, MILLISECONDS );
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

