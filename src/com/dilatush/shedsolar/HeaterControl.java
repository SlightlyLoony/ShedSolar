package com.dilatush.shedsolar;

import com.dilatush.shedsolar.events.*;
import com.dilatush.util.Config;
import com.dilatush.util.syncevents.SubscribeEvent;
import com.dilatush.util.syncevents.SubscriptionDefinition;
import com.dilatush.util.syncevents.SynchronousEvents;
import com.pi4j.io.gpio.*;

import java.util.TimerTask;
import java.util.logging.Logger;

/**
 * <p>Controls the solid state relay that turns the heater on and off, and also the indicator LED (on when the heater is turned on).  It also monitors
 * the output temperature of the heater to verify that it is working correctly, and the sense relay that verifies that the SSR is working
 * correctly.</p>
 * <p>The actions of this class are driven entirely by events.</p>
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class HeaterControl {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private final float                dormantLowTemp;
    private final float                dormantHighTemp;
    private final float                productionLowTemp;
    private final float                productionHighTemp;
    private final long                 delayToSSRSense;
    private final GpioPinDigitalInput  ssrSense;
    private final GpioPinDigitalOutput heaterPowerLED;
    private final GpioPinDigitalOutput heaterSSR;

    private TemperatureMode mode;
    private boolean         heaterOn;


    public HeaterControl( final Config _config ) {

        dormantLowTemp     = _config.getFloatDotted( "heaterControl.dormantLowTemp"     );
        dormantHighTemp    = _config.getFloatDotted( "heaterControl.dormantHighTemp"    );
        productionLowTemp  = _config.getFloatDotted( "heaterControl.productionLowTemp"  );
        productionHighTemp = _config.getFloatDotted( "heaterControl.productionHighTemp" );
        delayToSSRSense    = _config.getLongDotted(  "heaterControl.delayToSSRSense"    );

        // initialize the GPIO pins for the SSR, the SSR sense relay, and the heater on indicator LED...
        GpioController       controller     = App.instance.gpio;
        ssrSense       = controller.provisionDigitalInputPin(  RaspiPin.GPIO_00, "SSR Sense",        PinPullResistance.PULL_UP );
        heaterPowerLED = controller.provisionDigitalOutputPin( RaspiPin.GPIO_03, "Heater Power LED", PinState.HIGH             );
        heaterSSR      = controller.provisionDigitalOutputPin( RaspiPin.GPIO_05, "Heater SSR",       PinState.HIGH             );
        heaterPowerLED.setShutdownOptions( true, PinState.HIGH );
        heaterSSR.setShutdownOptions(      true, PinState.HIGH );

        // we start out by assuming that we're in production mode with the heater off...
        mode = TemperatureMode.PRODUCTION;
        heaterOn = false;

        // subscribe to the events we want to monitor...
        SynchronousEvents.getInstance().publish(
                new SubscribeEvent(
                        new SubscriptionDefinition( event -> handleTempModeEvent( (TempMode) event ), TempMode.class ) )
        );
        SynchronousEvents.getInstance().publish(
                new SubscribeEvent(
                        new SubscriptionDefinition( event -> handleBatteryTemperatureEvent( (BatteryTemperature) event ), BatteryTemperature.class ) )
        );
        SynchronousEvents.getInstance().publish(
                new SubscribeEvent(
                        new SubscriptionDefinition( event -> handleHeaterTemperatureEvent( (HeaterTemperature) event ), HeaterTemperature.class ) )
        );


    }


    private void handleTempModeEvent( TempMode _event ) {

        LOGGER.finest( _event.toString() );
    }


    private void handleBatteryTemperatureEvent( BatteryTemperature _event ) {

        LOGGER.finest( _event.toString() );
    }


    private void handleHeaterTemperatureEvent( HeaterTemperature _event ) {

        LOGGER.finest( _event.toString() );

        // TODO: remove this test code...
        if( _event.degreesC < 40f )
            turnHeaterOn();
        if( _event.degreesC > 80f )
            turnHeaterOff();
    }


    private void turnHeaterOn() {

        // if we've already turned it on, just leave...
        if( heaterOn )
            return;

        // turn on the SSR controlling the heater...
        heaterSSR.low();

        // turn on the LED indicator...
        heaterPowerLED.low();

        // schedule a check that the SSR actually turned on...
        App.instance.timer.schedule( new CheckSSROn(), delayToSSRSense );

        // mark that we've turned it on...
        heaterOn = true;
    }


    private void turnHeaterOff() {

        // if we've already turned it off, just leave...
        if( !heaterOn )
            return;

        // turn off the SSR controlling the heater...
        heaterSSR.high();

        // turn off the LED indicator...
        heaterPowerLED.high();

        // schedule a check that the SSR actually turned off...
        App.instance.timer.schedule( new CheckSSROff(), delayToSSRSense );

        // mark that we've turned it off...
        heaterOn = false;
    }


    /**
     * Verify that the SSR is actually working.
     */
    private class CheckSSROn extends TimerTask {

        /**
         * The action to be performed by this timer task.
         */
        @Override
        public void run() {

            LOGGER.finest( "SSR sense: " + (ssrSense.isLow() ? "on" : "off" ) );

            if( ssrSense.isHigh() ) {
                SynchronousEvents.getInstance().publish( new SSRStuckOff() );
            }
        }
    }


    private class CheckSSROff extends TimerTask {

        /**
         * The action to be performed by this timer task.
         */
        @Override
        public void run() {

            LOGGER.finest( "SSR sense: " + (ssrSense.isLow() ? "on" : "off" ) );

            if( ssrSense.isLow() ) {
                SynchronousEvents.getInstance().publish( new SSRStuckOn() );
            }
        }
    }
}
