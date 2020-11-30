package com.dilatush.shedsolar;

import com.dilatush.shedsolar.events.BatteryTemperatureEvent;
import com.dilatush.util.syncevents.SubscribeEvent;
import com.dilatush.util.syncevents.SubscriptionDefinition;
import com.dilatush.util.syncevents.SynchronousEvent;
import com.dilatush.util.syncevents.SynchronousEvents;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.PinState;
import com.pi4j.io.gpio.RaspiPin;

import java.util.TimerTask;

/**
 * @author Tom Dilatush  tom@dilatush.com
 */
public class BatteryTempLED {

    private final static float LO = 0;
    private final static float HI = 45.0f;
    private final static long DURATION = 1000;

    private final GpioPinDigitalOutput led;
    private float batteryTemp;
    private boolean badBatteryTemp;
    private boolean started;

    public BatteryTempLED() {
        batteryTemp = 0;
        started = false;
        led = App.instance.gpio.provisionDigitalOutputPin( RaspiPin.GPIO_02, "Battery Temperature", PinState.HIGH );
        SynchronousEvents.getInstance().publish(
                new SubscribeEvent( new SubscriptionDefinition( this::handleBatteryTempEvent, BatteryTemperatureEvent.class ) )
        );
    }


    public void handleBatteryTempEvent( final SynchronousEvent _event ) {
        BatteryTemperatureEvent be = (BatteryTemperatureEvent) _event;
        badBatteryTemp = !be.goodMeasurement;
        if( !badBatteryTemp ) {
            batteryTemp = be.degreesC;
            if( !started ) {
                started = true;
                new On().run();
            }
        }
        else {
            // TODO handle fast flash here...
        }
    }


    private long onMS( final float _temp ) {
        if( _temp <= LO ) return 0;
        if( _temp >= HI ) return 1000;
        return (long) (DURATION * (_temp - LO) / (HI - LO));
    }


    private class On extends TimerTask {

        /**
         * The action to be performed by this timer task.
         */
        @Override
        public void run() {
            led.setState( PinState.LOW );

            if( !badBatteryTemp ) {
                App.instance.timer.schedule( new Off(), onMS( batteryTemp ) );
                App.instance.timer.schedule( new On(), DURATION );
            }
            else {
                // TODO: handle fast flash here...
            }
        }
    }


    private class Off extends TimerTask {

        /**
         * The action to be performed by this timer task.
         */
        @Override
        public void run() {
            led.setState( PinState.HIGH );
        }
    }
}

