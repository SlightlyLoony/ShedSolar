package com.dilatush.shedsolar;

import com.dilatush.util.AConfig;
import com.dilatush.util.fsm.FSM;
import com.dilatush.util.fsm.FSMSpec;
import com.dilatush.util.fsm.FSMState;
import com.dilatush.util.fsm.FSMTransition;

import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.dilatush.shedsolar.HeaterControl.HeaterControllerContext;

/**
 * Implements a {@link HeaterController} for the normal equipment circumstance: when both the heater temperature sensor and the battery temperature
 * sensor are working properly.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class NoTempsHeaterController implements HeaterController {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private final FSM<State,Event>  fsm;           // the finite state machine at the heart of this class...
    private final Config            config;        // the configuration loaded from JavaScript...

    private HeaterControllerContext context;       // the controller context as of the most recent tick...


    // the states of our FSM...
    private enum State {
        OFF, CONFIRM_SSR_ON, ON, CONFIRM_SSR_OFF, INIT }


    // the events that drive our FSM...
    private enum Event {
        TURN_ON, TURN_OFF, ON_SENSED, OFF_SENSED, RESET, START }


    /**
     * Creates a new instance of this class with the given configuration.
     *
     * @param _config The {@link Config} configuration.
     */
    public NoTempsHeaterController( final Config _config ) {

        config = _config;
        fsm = createFSM();
    }


    /**
     * Called periodically by {@link HeaterControl} with the given {@link HeaterControllerContext} to update this heater controller.
     *
     * @param _context The heater controller context.
     */
    @Override
    public void tick( final HeaterControllerContext _context ) {

        // save the context for use in events not triggered by tick()...
        context = _context;

        // tell this thing to start up...
        fsm.onEvent( Event.START );
    }


    /**
     * Called by {@link HeaterControl} to tell this heater controller to turn off the heater and heater LED, and return to initial state, ready for
     * reuse.
     */
    @Override
    public void reset() {
        fsm.onEvent( Event.RESET );
    }


    /*---------------------------*/
    /*   F S M   A c t i o n s   */
    /*---------------------------*/


    // on CONFIRM_SSR_ON:ON_SENSED -> CONFIRM_HEATER_ON...
    private void on_ConfirmSSROn_OnSensed( final FSMTransition<State,Event> _transition ) {

        LOGGER.finest( () -> "No-temps heater controller CONFIRM_SSR_ON:ON_SENSED" );

        // if our sense relay shows no power, send a Hap...
        if( context.ssrSense.isHigh() )
            ShedSolar.instance.haps.post( Events.POSSIBLE_SSR_OR_SENSE_RELAY_FAILURE );
    }


    // on CONFIRM_SSR_OFF:OFF_SENSED -> CONFIRM_HEATER_OFF...
    private void on_ConfirmSSROff_OffSensed( final FSMTransition<State,Event> _transition ) {

        LOGGER.finest( () -> "No-temps heater controller CONFIRM_SSR_OFF:OFF_SENSED" );

        // if our sense relay shows power, send a Hap...
        if( context.ssrSense.isHigh() )
            ShedSolar.instance.haps.post( Events.POSSIBLE_SSR_OR_SENSE_RELAY_FAILURE );
    }


    // on entry to CONFIRM_SSR_ON...
    private void onEntry_ConfirmSSROn( final FSMState<State,Event> _state ) {

        LOGGER.finest( () -> "No-temps heater controller on entry to CONFIRM_SSR_ON" );

        // turn on the heater and the heater LED...
        context.heaterSSR.low();
        context.heaterPowerLED.low();

        // set a timeout for 100 ms to check sense relay...
        _state.fsm.scheduleEvent( Event.ON_SENSED, Duration.ofMillis( 100 ) );

        // compute the time we need to stay on, and set a timeout for it...
        double onTimeSeconds = config.degreesPerSecond * (context.hiTemp - context.loTemp) * config.safetyTweak;
        _state.fsm.scheduleEvent( Event.TURN_OFF, Duration.ofMillis( Math.round( 1000 * onTimeSeconds ) ) );
    }


    // on entry to CONFIRM_SSR_OFF...
    private void onEntry_ConfirmSSROff( final FSMState<State,Event> _state ) {

        LOGGER.finest( () -> "No-temps heater controller on entry to CONFIRM_SSR_OFF" );

        // set a timeout for 100 ms to check sense relay...
        _state.fsm.scheduleEvent( Event.OFF_SENSED, Duration.ofMillis( 100 ) );
    }


    // on entry to OFF...
    private void onEntry_Off( final FSMState<State,Event> _state ) {

        LOGGER.finest( () -> "No-temps heater controller on entry to OFF" );

        // turn off the SSR and the heater LED (matters for reset event)...
        // if we didn't have a context, then they're already off...
        if( context != null ) {
            context.heaterSSR.high();
            context.heaterPowerLED.high();
        }

        // get the outside temperature...
        assert context != null;
        double outsideTemp;
        if( context.ambientTemp.isInfoAvailable() )
            outsideTemp = context.ambientTemp.getInfo();
        else if( context.outsideTemp.isInfoAvailable() )
            outsideTemp = context.outsideTemp.getInfo();
        else {

        }

        // calculate how long to keep the heater off...
        double offTimeSeconds = ThermalCalcs.t( context.loTemp, context.hiTemp, outsideTemp - context.hiTemp, config.k );

    }


    // on exit from ON...
    private void onExit_On( final FSMState<State,Event> _state ) {

        LOGGER.finest( () -> "No-temps heater controller on exit from ON" );

        // turn off the heater and LED...
        context.heaterSSR.high();
        context.heaterPowerLED.high();
    }


    // on state change...
    private void stateChange( final State _state ) {
        LOGGER.finest( "No-temps heater controller changed state to: " + _state );
    }


    /**
     * The configuration for this class, normally from JavaScript.
     */
    public static class Config extends AConfig {

        /**
         * The constant K for the thermal calculations in {@link ThermalCalcs}, as determined by direct observation.  There is no default value;
         * valid values are in the range (0..1].
         */
        public double k;


        /**
         * The number of degrees per second of operation that the heater will raise the temperature of the batteries, as determined by direct
         * observation.  There is no default value; valid values are in the range (0..1].
         */
        public double degreesPerSecond;


        /**
         * The number to multiply the computed length of heater on time by, to provide a margin of safety on the high temperature side, as it is
         * better for the battery to be slightly warmer than the target temperatures than it is for it to be cooler.  The default value is 1.0;
         * valid values are in the range [1..1.25].
         */
        public double safetyTweak = 1.0;


        @Override
        public void verify( final List<String> _messages ) {
            validate( () -> (confirmOnDelta >= 5) && (confirmOnDelta <= 30), _messages,
                    "No-temps heater controller confirm on delta temperature is out of range: " + confirmOnDelta );
            validate( () -> (confirmOnTimeMS >= 10000) && (confirmOnTimeMS <= 600000), _messages,
                    "No-temps heater controller confirm on time is out of range: " + confirmOnTimeMS );
            validate( () -> (initialCooldownPeriodMS >= 10000) && (initialCooldownPeriodMS <= 600000), _messages,
                    "No-temps heater controller initial cooldown period is out of range: " + initialCooldownPeriodMS );
            validate( () -> (confirmOffDelta >= -30) && (confirmOffDelta <= -5), _messages,
                    "No-temps heater controller confirm off delta temperature is out of range: " + confirmOffDelta );
            validate( () -> (confirmOffTimeMS >= 10000) && (confirmOffTimeMS <= 600000), _messages,
                    "No-temps heater controller confirm off time is out of range: " + confirmOffTimeMS );
            validate( () -> (heaterTempLimit >= 30) && (heaterTempLimit <= 60), _messages,
                    "No-temps heater controller heater temperature limit is out of range: " + heaterTempLimit );
            validate( () -> (coolingTimeMS >= 60000) && (coolingTimeMS <= 600000), _messages,
                    "No-temps heater controller cooling time is out of range: " + coolingTimeMS );
        }
    }


    /**
     * Create the FSM at the heart of this class.
     *
     * @return the FSM created
     */
    private FSM<State,Event> createFSM() {

        FSMSpec<State,Event> spec = new FSMSpec<>( State.INIT, Event.OFF_SENSED );

        spec.enableEventScheduling( ShedSolar.instance.scheduledExecutor );

        spec.setStateChangeListener( this::stateChange );

        spec.setStateOnEntryAction( State.CONFIRM_SSR_ON,  this::onEntry_ConfirmSSROn  );
        spec.setStateOnEntryAction( State.CONFIRM_SSR_OFF, this::onEntry_ConfirmSSROff );
        spec.setStateOnEntryAction( State.OFF,             this::onEntry_Off           );

        spec.setStateOnExitAction( State.ON, this::onExit_On );

        spec.addTransition( State.OFF,                Event.TURN_ON,     null,                              State.CONFIRM_SSR_ON     );
        spec.addTransition( State.CONFIRM_SSR_ON,     Event.ON_SENSED,   this::on_ConfirmSSROn_OnSensed,    State.ON                 );
        spec.addTransition( State.ON,                 Event.TURN_OFF,    null,                              State.CONFIRM_SSR_OFF    );
        spec.addTransition( State.CONFIRM_SSR_OFF,    Event.OFF_SENSED,  this::on_ConfirmSSROff_OffSensed,  State.OFF                );
        spec.addTransition( State.CONFIRM_SSR_ON,     Event.RESET,       null,                              State.OFF                );
        spec.addTransition( State.ON,                 Event.RESET,       null,                              State.OFF                );
        spec.addTransition( State.CONFIRM_SSR_OFF,    Event.RESET,       null,                              State.OFF                );

        if( !spec.isValid() ) {
            LOGGER.log( Level.SEVERE, "Fatal error when constructing no-temps heater controller FSM\n" + spec.getErrorMessage() );
            System.exit( 1 );
        }

        return new FSM<>( spec );
    }
}
