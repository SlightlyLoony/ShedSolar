package com.dilatush.shedsolar;

import com.dilatush.util.config.AConfig;
import com.dilatush.util.fsm.FSM;
import com.dilatush.util.fsm.FSMSpec;
import com.dilatush.util.fsm.FSMState;
import com.dilatush.util.fsm.FSMTransition;
import com.dilatush.util.fsm.events.FSMEvent;

import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.dilatush.shedsolar.HeaterControl.HeaterControllerContext;

/**
 * Implements a {@link HeaterController} for the worst-case circumstance: we can read neither the battery temperature nor the heater output
 * temperature.  This controller estimates both the heater on time and the heater off time by using calculations based on a thermal model of the
 * battery box.  The parameters of this model must be measured (and configured) while the system is operational.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class NoTempsHeaterController implements HeaterController {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private final FSM<State,Event>  fsm;           // the finite state machine at the heart of this class...
    private final Config            config;        // the configuration loaded from JavaScript...

    private HeaterControllerContext context;       // the controller context as of the most recent tick or reset...
    private double                  outsideTemp;   // in °C...


    // the states of our FSM...
    private enum State {
        OFF, CONFIRM_SSR_ON, ON, CONFIRM_SSR_OFF, WAIT_FOR_TRIGGER }


    // the events that drive our FSM...
    private enum Event {
        LOW_AMBIENT, TRIGGER, TURN_OFF, ON_SENSED, OFF_SENSED, RESET }


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

        // save the context...
        context = _context;

        // get the outside temperature...
        double outsideTheBoxTemp;
        if( context.ambientTemp.isInfoAvailable() )
            outsideTheBoxTemp = context.ambientTemp.getInfo();
        else if( context.outsideTemp.isInfoAvailable() )
            outsideTheBoxTemp = context.outsideTemp.getInfo();
        else {

            /*
             *  If we get here, we have a serious problem - we don't know the battery temperature, the heater output temperature, the ambient
             *  temperature at the ShedSolar box, or the weather station's outside temperature.  All we can do, really, is scream for help.
             *  Turning on the heater risks running the batteries too hot.
             */
            context.heaterOff.run();                                                   // make sure the heater is off; it's the safe thing to do...
            ShedSolar.instance.haps.post( Events.NO_TEMPERATURE_OUTSIDE_THE_BOX );     // let the world know we're screwed...
            return;                                                                    // skedaddle...
        }

        // if we have an ambient temperature lower than the low threshold, we've got an event...
        outsideTemp = outsideTheBoxTemp;
        if( outsideTheBoxTemp < context.loTemp )
            fsm.onEvent( Event.LOW_AMBIENT );
    }


    /**
     * Called by {@link HeaterControl} to tell this heater controller to turn off the heater and heater LED, and return to initial state, ready for
     * reuse.
     *
     * @param _context The heater controller context.
     */
    @Override
    public void reset( final HeaterControllerContext _context ) {

        // save the context...
        context = _context;

        // issue the reset...
        fsm.onEvent( Event.RESET );
    }


    /*---------------------------*/
    /*   F S M   A c t i o n s   */
    /*---------------------------*/


    // on CONFIRM_SSR_ON:ON_SENSED -> ON...
    private void on_ConfirmSSROn_OnSensed( final FSMTransition<State,Event> _transition, final FSMEvent<Event> _event ) {

        LOGGER.finest( () -> "No-temps heater controller CONFIRM_SSR_ON:ON_SENSED" );

        // if our sense relay shows no power, send a Hap...
        if( !context.isSSROutputSensed.get() )
            ShedSolar.instance.haps.post( Events.POSSIBLE_SSR_OR_SENSE_RELAY_FAILURE );
    }


    // on CONFIRM_SSR_OFF:OFF_SENSED -> WAIT_FOR_TRIGGER...
    private void on_ConfirmSSROff_OffSensed( final FSMTransition<State,Event> _transition, final FSMEvent<Event> _event ) {

        LOGGER.finest( () -> "No-temps heater controller CONFIRM_SSR_OFF:OFF_SENSED" );

        // if our sense relay shows power, send a Hap...
        if( context.isSSROutputSensed.get() )
            ShedSolar.instance.haps.post( Events.POSSIBLE_SSR_OR_SENSE_RELAY_FAILURE );
    }


    // on entry to CONFIRM_SSR_ON...
    private void onEntry_ConfirmSSROn( final FSMState<State,Event> _state ) {

        LOGGER.finest( () -> "No-temps heater controller on entry to CONFIRM_SSR_ON" );

        // turn on the heater and the heater LED...
        context.heaterOn.run();

        // schedule a 100 ms check for sense relay...
        _state.fsm.scheduleEvent( Event.ON_SENSED, Duration.ofMillis( 100 ) );

        /*
         * These are the tricky bits for this heater controller.  We get here because the temperature outside the box is less than the low
         * temperature threshold.  Here we estimate two things:
         * 1.  How long we should run the heater to take the battery temperature to the high threshold (from the low threshold).
         * 2.  How long we should wait, with the heater off, for the battery temperature to drop back down to the low threshold.
         */

        // estimate the time we need to keep the heater on, and schedule an event for it...
        // note the "safety tweak", because we're taking the position that it's better for the batteries to be a bit warm than a bit cold...
        double onTimeSeconds =  ((context.hiTemp - context.loTemp) / config.degreesPerSecond) * config.safetyTweak;
        _state.fsm.scheduleEvent( Event.TURN_OFF, Duration.ofMillis( Math.round( 1000 * onTimeSeconds ) ) );

        // estimate how long we should wait (heater off) for the battery temperature to drop back to the low threshold,
        // and schedule an event for that...
        double offTimeSeconds = ThermalCalcs.t( context.loTemp, context.hiTemp, outsideTemp - context.hiTemp, config.k );
        _state.fsm.scheduleEvent( Event.TRIGGER, Duration.ofMillis( Math.round( (onTimeSeconds + offTimeSeconds) * 1000 ) ) );

        LOGGER.log( Level.FINEST, () -> "On time: " + onTimeSeconds + ", off time: " + offTimeSeconds );
    }


    // on entry to CONFIRM_SSR_OFF...
    private void onEntry_ConfirmSSROff( final FSMState<State,Event> _state ) {

        LOGGER.finest( () -> "No-temps heater controller on entry to CONFIRM_SSR_OFF" );

        // turn the heater off...
        context.heaterOff.run();

        // set a timeout for 100 ms to check sense relay...
        _state.fsm.scheduleEvent( Event.OFF_SENSED, Duration.ofMillis( 100 ) );
    }


    // on entry to OFF...
    private void onEntry_Off( final FSMState<State,Event> _state ) {

        LOGGER.finest( () -> "No-temps heater controller on entry to OFF" );

        // turn off the SSR and the heater LED (matters for reset event)...
        context.heaterOff.run();
    }


    // on exit from ON...
    @SuppressWarnings( "unused" )
    private void onExit_On( final FSMState<State,Event> _state ) {

        LOGGER.finest( () -> "No-temps heater controller on exit from ON" );

        // turn off the heater and LED...
        context.heaterOff.run();
    }


    // on state change...
    private void stateChange( final State _state ) {
        LOGGER.finest( "No-temps heater controller changed state to: " + _state );
    }


    // on event...
    private void event( final FSMEvent<Event> _event ) {
        LOGGER.finest( () -> "No-temps heater controller event: " + _event.toString() );
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
            validate( () -> (k > 0) && (k <= 1), _messages,
                    "No-temps heater controller k is out of range: " + k );
            validate( () -> (degreesPerSecond > 0) && (degreesPerSecond < 1), _messages,
                    "No-temps heater controller degrees per second is out of range: " + degreesPerSecond );
            validate( () -> (safetyTweak >= 1) && (safetyTweak <= 1.25), _messages,
                    "No-temps safety tweak is out of range: " + safetyTweak );
        }
    }


    /**
     * Create the FSM at the heart of this class.
     *
     * @return the FSM created
     */
    private FSM<State,Event> createFSM() {

        FSMSpec<State,Event> spec = new FSMSpec<>( State.OFF, Event.TRIGGER );

        spec.enableEventScheduling( ShedSolar.instance.scheduledExecutor );

        spec.setStateChangeListener( this::stateChange );
        spec.setEventListener( this::event );

        spec.setStateOnEntryAction( State.CONFIRM_SSR_ON,  this::onEntry_ConfirmSSROn  );
        spec.setStateOnEntryAction( State.CONFIRM_SSR_OFF, this::onEntry_ConfirmSSROff );
        spec.setStateOnEntryAction( State.OFF,             this::onEntry_Off           );

        spec.addTransition( State.OFF,                Event.LOW_AMBIENT, null,                              State.CONFIRM_SSR_ON     );
        spec.addTransition( State.CONFIRM_SSR_ON,     Event.ON_SENSED,   this::on_ConfirmSSROn_OnSensed,    State.ON                 );
        spec.addTransition( State.CONFIRM_SSR_ON,     Event.TURN_OFF,    null,                              State.CONFIRM_SSR_OFF    );
        spec.addTransition( State.ON,                 Event.TURN_OFF,    null,                              State.CONFIRM_SSR_OFF    );
        spec.addTransition( State.CONFIRM_SSR_OFF,    Event.OFF_SENSED,  this::on_ConfirmSSROff_OffSensed,  State.WAIT_FOR_TRIGGER   );
        spec.addTransition( State.WAIT_FOR_TRIGGER,   Event.TRIGGER,     null,                              State.OFF                );
        spec.addTransition( State.CONFIRM_SSR_ON,     Event.RESET,       null,                              State.OFF                );
        spec.addTransition( State.ON,                 Event.RESET,       null,                              State.OFF                );
        spec.addTransition( State.CONFIRM_SSR_OFF,    Event.RESET,       null,                              State.OFF                );
        spec.addTransition( State.WAIT_FOR_TRIGGER,   Event.RESET,       null,                              State.OFF                );

        if( !spec.isValid() ) {
            LOGGER.log( Level.SEVERE, "Fatal error when constructing no-temps heater controller FSM\n" + spec.getErrorMessage() );
            System.exit( 1 );
        }

        return new FSM<>( spec );
    }
}
