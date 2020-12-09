package com.dilatush.shedsolar;

import com.dilatush.shedsolar.events.*;
import com.dilatush.util.Config;
import com.dilatush.util.syncevents.SubscribeEvent;
import com.dilatush.util.syncevents.SubscriptionDefinition;
import com.dilatush.util.syncevents.SynchronousEvents;
import com.pi4j.io.gpio.*;

import java.util.TimerTask;
import java.util.logging.Logger;

import static com.dilatush.shedsolar.TemperatureMode.PRODUCTION;

/**
 * <p>Controls the solid state relay that turns the heater on and off, and also the indicator LED (on when the heater is turned on).  It also monitors
 * the output temperature of the heater to verify that it is working correctly, and the sense relay that verifies that the SSR is working
 * correctly.</p>
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
    private final long                 heaterTempDelay;
    private final float                heaterTempDelta;
    private final float                heaterTemperatureOffOffset;
    private final GpioPinDigitalInput  ssrSense;
    private final GpioPinDigitalOutput heaterPowerLED;
    private final GpioPinDigitalOutput heaterSSR;

    private volatile TemperatureMode mode;
    private volatile boolean         heaterOn;
    private volatile float           heaterTemperature;
    private volatile float           heaterTemperatureBefore;
    private volatile boolean         heaterTemperatureGood;
    private volatile float           batteryTemperature;
    private volatile boolean         batteryTemperatureGood;


    /**
     * Create a new instance of this class with the given configuration.
     *
     * @param _config the configuration file
     */
    public HeaterControl( final Config _config ) {

        dormantLowTemp             = _config.getFloatDotted( "heaterControl.dormantLowTemp"             );
        dormantHighTemp            = _config.getFloatDotted( "heaterControl.dormantHighTemp"            );
        productionLowTemp          = _config.getFloatDotted( "heaterControl.productionLowTemp"          );
        productionHighTemp         = _config.getFloatDotted( "heaterControl.productionHighTemp"         );
        delayToSSRSense            = _config.getLongDotted(  "heaterControl.delayToSSRSense"            );
        heaterTempDelay            = _config.getLongDotted(  "heaterControl.heaterTempDelay"            );
        heaterTempDelta            = _config.getFloatDotted( "heaterControl.heaterTempDelta"            );
        heaterTemperatureOffOffset = _config.getFloatDotted( "heaterControl.heaterTemperatureOffOffset" );

        // initialize the GPIO pins for the SSR, the SSR sense relay, and the heater on indicator LED...
        GpioController       controller     = App.instance.gpio;
        ssrSense       = controller.provisionDigitalInputPin(  RaspiPin.GPIO_00, "SSR Sense",        PinPullResistance.PULL_UP );
        heaterPowerLED = controller.provisionDigitalOutputPin( RaspiPin.GPIO_03, "Heater Power LED", PinState.HIGH             );
        heaterSSR      = controller.provisionDigitalOutputPin( RaspiPin.GPIO_05, "Heater SSR",       PinState.HIGH             );
        heaterPowerLED.setShutdownOptions( true, PinState.HIGH );
        heaterSSR.setShutdownOptions(      true, PinState.HIGH );

        // we start out by assuming that we're in production mode with the heater off...
        mode = PRODUCTION;
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


    /**
     * Handle a mode (production/dormant) event.
     *
     * @param _event the mode event
     */
    private void handleTempModeEvent( TempMode _event ) {

        LOGGER.finest( _event.toString() );
        mode = _event.mode;
        handleHeater();
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
        handleHeater();
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
        handleHeater();
    }


    /**
     * Handle the results of a temperature event (battery OR heater).
     */
    private void handleHeater() {

        // if we don't have any good temperature measurements, turn the heater off, scream, and leave...
        if( !batteryTemperatureGood && !heaterTemperatureGood ) {
            turnHeaterOff();
            SynchronousEvents.getInstance().publish( new HeaterControlAbort() );
            return;
        }

        // if the heater is already on, let's see if it's time to turn it off...
        if( heaterOn ) {
            float offTemp = (mode == PRODUCTION) ? productionHighTemp : dormantHighTemp;
            if( batteryTemperatureGood ? (batteryTemperature >= offTemp) : (heaterTemperature >= (offTemp + heaterTemperatureOffOffset)) ) {
                turnHeaterOff();
            }
        }

        // if the heater is off, let's see if it's time to turn it on...
        else {
            float onTemp = (mode == PRODUCTION) ? productionLowTemp : dormantLowTemp;
            if( batteryTemperatureGood ? (batteryTemperature <= onTemp) : (heaterTemperature <= onTemp) ) {
                turnHeaterOn();
            }
        }
    }


    /**
     * Handles all the details of turning the heater on.
     */
    private void turnHeaterOn() {

        // if we've already turned it on, just leave...
        if( heaterOn )
            return;

        LOGGER.fine( "Turning heater on" );

        // record the heater temperature before we turn it on...
        heaterTemperatureBefore = heaterTemperature;

        // turn on the SSR controlling the heater...
        heaterSSR.low();

        // turn on the LED indicator...
        heaterPowerLED.low();

        // schedule a check to verify the SSR actually turned on...
        App.instance.timer.schedule( new CheckSSROn(), delayToSSRSense );

        // schedule a check to verify the heater actually turned on...
        App.instance.timer.schedule( new CheckHeaterOn(), heaterTempDelay );

        // mark that we've turned it on...
        heaterOn = true;

        // tell the rest of the world what we did...
        SynchronousEvents.getInstance().publish( new HeaterOn() );
    }


    /**
     * Handles all the details of turning the heater off.
     */
    private void turnHeaterOff() {

        // if we've already turned it off, just leave...
        if( !heaterOn )
            return;

        LOGGER.fine( "Turning heater off" );

        // record the heater temperature before we turn it off...
        heaterTemperatureBefore = heaterTemperature;

        // turn off the SSR controlling the heater...
        heaterSSR.high();

        // turn off the LED indicator...
        heaterPowerLED.high();

        // schedule a check to verify the SSR actually turned off...
        App.instance.timer.schedule( new CheckSSROff(), delayToSSRSense );

        // schedule a check to verify the heater actually turned off...
        App.instance.timer.schedule( new CheckHeaterOff(), heaterTempDelay );

        // mark that we've turned it off...
        heaterOn = false;

        // tell the rest of the world what we did...
        SynchronousEvents.getInstance().publish( new HeaterOff() );
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

            // if the heater is off, just leave...
            if( !heaterOn )
                return;

            LOGGER.finest( "SSR sense: " + (ssrSense.isLow() ? "on" : "off" ) );

            // if the SSR is NOT on, scream bloody murder...
            if( ssrSense.isHigh() ) {
                SynchronousEvents.getInstance().publish( new SSRStuckOff() );
            }

            // otherwise, schedule a new check to make sure it STAYS on...
            else {
                App.instance.timer.schedule( new CheckSSROn(), delayToSSRSense );
            }
        }
    }


    /**
     * Verify that the SSR is actually off.
     */
    private class CheckSSROff extends TimerTask {

        /**
         * The action to be performed by this timer task.
         */
        @Override
        public void run() {

            LOGGER.finest( "SSR sense: " + (ssrSense.isLow() ? "on" : "off" ) );

            if( ssrSense.isLow() && !heaterOn ) {
                SynchronousEvents.getInstance().publish( new SSRStuckOn() );
            }
        }
    }


    /**
     * Verify that the heater is actually working.
     */
    private class CheckHeaterOn extends TimerTask {

        /**
         * The action to be performed by this timer task.
         */
        @Override
        public void run() {

            // if we don't have a good heater temperature, just leave
            if( !heaterTemperatureGood )
                return;

            // if the heater isn't even on, just leave...
            if( !heaterOn )
                return;

            // see if we got the expected temperature increase...
            boolean heaterWorking = (heaterTemperature - heaterTemperatureBefore) >= heaterTempDelta;
            LOGGER.finest( heaterWorking ? "Heater is on" : "Heater is on, but not producing heat"  );

            // if the heater is NOT working, scream bloody murder...
            if( !heaterWorking ) {
                SynchronousEvents.getInstance().publish( new HeaterStuckOff() );
            }

            // otherwise, schedule a check to make sure the heater STAYS on...
            else {
                App.instance.timer.schedule( new CheckHeaterOn(), heaterTempDelay );
            }
        }
    }

    /**
     * Verify that the heater has actually turned off.
     */
    private class CheckHeaterOff extends TimerTask {

        /**
         * The action to be performed by this timer task.
         */
        @Override
        public void run() {

            // if we don't have a good heater temperature, just leave
            if( !heaterTemperatureGood )
                return;

            // see if we got the expected temperature decrease...
            boolean heaterWorking = (heaterTemperatureBefore - heaterTemperature) >= heaterTempDelta;
            LOGGER.finest( heaterWorking ? "Heater is off" : "Heater is off, but still producing heat" );

            if( !heaterWorking && !heaterOn ) {
                SynchronousEvents.getInstance().publish( new HeaterStuckOn() );
            }
        }
    }
}
