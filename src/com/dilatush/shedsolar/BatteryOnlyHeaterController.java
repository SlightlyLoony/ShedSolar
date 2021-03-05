package com.dilatush.shedsolar;

import com.dilatush.util.AConfig;
import com.dilatush.util.Haps;
import com.dilatush.util.fsm.FSM;
import com.dilatush.util.fsm.FSMSpec;
import com.dilatush.util.fsm.FSMState;
import com.dilatush.util.fsm.FSMTransition;
import com.dilatush.util.fsm.events.FSMEvent;

import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.dilatush.shedsolar.Events.*;
import static com.dilatush.shedsolar.HeaterControl.HeaterControllerContext;

/**
 * Implements a {@link HeaterController} for the case of the heater output temperature sensor malfunctioning, but the battery temperature sensor is
 * working.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class BatteryOnlyHeaterController implements HeaterController {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private final FSM<State, Event> fsm;           // the finite state machine at the heart of this class...
    private final Config            config;        // the configuration loaded from JavaScript...

    private float                   startingTemp;  // temperature going into confirming heater on or off..
    private HeaterControllerContext context;       // the controller context as of the most recent tick...
    private boolean                 senseRelay;    // true if the relay sensed that the heater power is on...
    private int                     turnOnTries;   // number of times we've tried to turn the heater on...

    // some short aliases...
    private final ShedSolar ss;
    private final Haps<Events> haps;


    // the states of our FSM...
    private enum State {
        OFF, CONFIRM_SSR_ON, HEATER_COOLING, CONFIRM_HEATER_ON, ON, CONFIRM_SSR_OFF, CONFIRM_HEATER_OFF, COOLING }


    // the events that drive our FSM...
    private enum Event {
        LO_BATTERY_TEMP, ON_SENSED, NO_TEMP_RISE, COOLED, BATTERY_TEMP_RISE, HI_BATTERY_TEMP,
        OFF_SENSED, BATTERY_TEMP_DROP, NO_TEMP_DROP, RESET }


    /**
     * Creates a new instance of this class with the given configuration.
     *
     * @param _config The {@link Config} configuration.
     */
    public BatteryOnlyHeaterController( final Config _config ) {

        ss = ShedSolar.instance;
        haps = ss.haps;

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

        // issue low and high battery temp events if warranted...
        if( _context.batteryTemp.getInfo() < _context.loTemp )
            fsm.onEvent( Event.LO_BATTERY_TEMP );
        if( _context.batteryTemp.getInfo() > _context.hiTemp )
            fsm.onEvent( Event.HI_BATTERY_TEMP );

        // if we're in confirming heater on state, see if we've risen enough...
        if( fsm.getStateEnum() == State.CONFIRM_HEATER_ON ) {
            if( _context.batteryTemp.getInfo() > (startingTemp + config.confirmOnDelta ) )
                fsm.onEvent( Event.BATTERY_TEMP_RISE );
        }

        // if we're in confirming heater off state, see if we've dropped enough...
        if( fsm.getStateEnum() == State.CONFIRM_HEATER_OFF ) {
            if( _context.batteryTemp.getInfo() < (startingTemp + config.confirmOffDelta ) )
                fsm.onEvent( Event.BATTERY_TEMP_DROP );
        }
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


    // on OFF:LOW_BATTERY_TEMP -> CONFIRM_SSR_ON...
    private void on_Off_LowBatteryTemp( final FSMTransition<State, Event> _transition ) {

        LOGGER.finest( () -> "Battery-only heater controller OFF:LOW_BATTERY_TEMP" );

        // number of times we've tried to zero...
        turnOnTries = 0;
    }


    // on CONFIRM_SSR_ON:ON_SENSED -> CONFIRM_HEATER_ON...
    private void on_ConfirmSSROn_OnSensed( final FSMTransition<State, Event> _transition ) {

        LOGGER.finest( () -> "Battery-only heater controller CONFIRM_SSR_ON:ON_SENSED" );

        // read our sense relay and stuff the result away for later use...
        senseRelay = context.isSSROutputSensed.get();

        // set a timeout for the configured time we'll wait for a temperature rise...
        _transition.setTimeout( Event.NO_TEMP_RISE, Duration.ofMillis( config.confirmOnTimeMS ) );
    }


    // on CONFIRM_HEATER_ON:NO_TEMP_RISE -> HEATER_COOLING...
    private void on_ConfirmHeaterOn_NoTempRise( final FSMTransition<State, Event> _transition ) {

        LOGGER.finest( () -> "Battery-only heater controller CONFIRM_HEATER_ON:NO_TEMP_RISE" );

        // turn off the heater, as we're gonna cool down for a while...
        haps.post( Events.HEATER_NO_START );
        context.heaterOff.run();

        // set a timeout for a cooldown period, more time for more tries...
        turnOnTries = Math.max( 5, turnOnTries + 1 );
        _transition.setTimeout( Event.COOLED, Duration.ofMillis( config.initialCooldownPeriodMS * turnOnTries ) );

        // if we've tried more than 5 times, we may have a dead SSR or a dead heater - send a Hap to that effect...
        if( turnOnTries >= 5 )
            haps.post( senseRelay ? Events.POSSIBLE_HEATER_FAILURE : Events.POSSIBLE_SSR_FAILURE );
    }


    // on CONFIRM_HEATER_ON:BATTERY_TEMP_RISE -> ON...
    private void on_ConfirmHeaterOn_BatteryTempRise( final FSMTransition<State, Event> _transition ) {

        LOGGER.finest( () -> "Battery-only heater controller CONFIRM_HEATER_ON:HEATER_TEMP_RISE" );

        // if sense relay is false, then we probably have a bad sense relay - send a Hap to that effect...
        haps.post( senseRelay ? SENSE_RELAY_WORKING : POSSIBLE_SENSE_RELAY_FAILURE );

        // we got a temperature rise, so the SSR and the heater must be working...
        haps.post( HEATER_WORKING );
        haps.post( SSR_WORKING );
    }


    // on CONFIRM_SSR_OFF:OFF_SENSED -> CONFIRM_HEATER_OFF...
    private void on_ConfirmSSROff_OffSensed( final FSMTransition<State, Event> _transition ) {

        LOGGER.finest( () -> "Battery-only heater controller CONFIRM_SSR_OFF:OFF_SENSED" );

        // read our sense relay and stuff the result away for later use...
        senseRelay = context.isSSROutputSensed.get();

        // set a timeout for the configured time we'll wait for a temperature drop...
        _transition.setTimeout( Event.NO_TEMP_DROP, Duration.ofMillis( config.confirmOffTimeMS ) );
    }


    // on CONFIRM_HEATER_OFF:NO_TEMP_DROP -> COOLING...
    private void on_ConfirmHeaterOff_NoTempDrop( final FSMTransition<State, Event> _transition ) {

        LOGGER.finest( () -> "Battery-only heater controller CONFIRM_HEATER_OFF:NO_TEMP_DROP" );

        // if we are still sensing power, we may have a stuck SSR - send a Hap to that effect...
        haps.post( senseRelay ? POSSIBLE_SSR_FAILURE : SSR_WORKING );
    }


    // on CONFIRM_HEATER_OFF:BATTERY_TEMP_DROP -> COOLING...
    private void on_ConfirmHeaterOff_BatteryTempDrop( final FSMTransition<State, Event> _transition ) {

        LOGGER.finest( () -> "Battery-only heater controller CONFIRM_HEATER_OFF:BATTERY_TEMP_DROP" );

        // if we are still sensing power, we may have a bad sense relay - send a Hap to that effect...
        haps.post( senseRelay ? POSSIBLE_SENSE_RELAY_FAILURE : SENSE_RELAY_WORKING );
    }


    // on entry to CONFIRM_SSR_ON...
    private void onEntry_ConfirmSSROn( final FSMState<State, Event> _state ) {

        LOGGER.finest( () -> "Battery-only heater controller on entry to CONFIRM_SSR_ON" );

        // record our starting temperature (so we can sense the temperature rise)...
        startingTemp = context.batteryTemp.getInfo();

        // turn on the heater and the heater LED...
        context.heaterOn.run();

        // set a timeout for 100 ms to check sense relay...
        _state.fsm.scheduleEvent( Event.ON_SENSED, Duration.ofMillis( 100 ) );
    }


    // on entry to CONFIRM_SSR_OFF...
    private void onEntry_ConfirmSSROff( final FSMState<State, Event> _state ) {

        LOGGER.finest( () -> "Battery-only heater controller on entry to CONFIRM_SSR_OFF" );

        // set a timeout for 100 ms to check sense relay...
        _state.fsm.scheduleEvent( Event.OFF_SENSED, Duration.ofMillis( 100 ) );
    }


    // on entry to COOLING...
    private void onEntry_Cooling( final FSMState<State, Event> _state ) {

        LOGGER.finest( () -> "Battery-only heater controller on entry to COOLING" );

        // set a timeout for our cooling period...
        _state.fsm.scheduleEvent( Event.COOLED, Duration.ofMillis( config.coolingTimeMS ) );
    }


    // on entry to OFF...
    private void onEntry_Off( final FSMState<State, Event> _state ) {

        LOGGER.finest( () -> "Battery-only heater controller on entry to OFF" );

        // turn off the SSR and the heater LED (matters for reset event)...
        context.heaterOff.run();
    }


    // on exit from ON...
    private void onExit_On( final FSMState<State, Event> _state ) {

        LOGGER.finest( () -> "Battery-only heater controller on exit from ON" );

        // record the starting temperature (so we can sense the temperature drop)...
        startingTemp = context.batteryTemp.getInfo();

        // turn off the heater and LED...
        context.heaterOff.run();
    }


    // on state change...
    private void stateChange( final State _state ) {
        LOGGER.finest( "Battery-only heater controller changed state to: " + _state );
    }


    // on event...
    private void event( final FSMEvent<Event> _event ) {
        LOGGER.finest( () -> "Battery-only controller event: " + _event.toString() );
    }


    /**
     * The configuration for this class, normally from JavaScript.
     */
    public static class Config extends AConfig {

        /**
         * The minimum temperature increase (in °C) from the battery output thermocouple to verify that the heater is working.  The default is 5°C,
         * valid values are in the range [0.1..30].
         */
        public float confirmOnDelta = 5;

        /**
         * The maximum time, in milliseconds, to wait for confirmation of the heater working (by sensing the temperature increase on the battery
         * thermocouple).  The default is 180,000 (3 minutes); valid values are in the range [10,000..600,000].
         */
        public long confirmOnTimeMS = 180000;

        /**
         * The initial cooldown period (in milliseconds) to use after the heater fails to start.  If the heater doesn't start, it may be that the
         * thermal "fuse" has tripped and the heater needs to cool down.  The first attempted cooldown period is the length specified here;
         * subsequent cooldown periods are gradually increased to 5x the length specified here.  The default period is 60,000 (60 seconds);
         * valid values are in the range [10,000..600,000].
         */
        public long initialCooldownPeriodMS = 60000;

        /**
         * The minimum temperature decrease (in °C) from the heater output thermocouple to verify that the heater is working.  The default is
         * -5°C, valid values are in the range [-30..-0.1].  Note that the value is negative (indicating a temperature drop).
         */
        public float confirmOffDelta = -5;

        /**
         * The maximum time, in milliseconds, to wait for confirmation of the heater turning off (by sensing the temperature decrease on the
         * battery thermocouple).  The default is 180,000 (3 minutes); valid values are in the range [10,000..600,000].
         */
        public long confirmOffTimeMS = 180000;

        /**
         * The time, in milliseconds, to cool down the heater after turning it off.  The default is 180000 (3 minutes); valid values are
         * in the range [60000..600000].
         */
        public long coolingTimeMS = 180000;


        @Override
        public void verify( final List<String> _messages ) {
            validate( () -> (confirmOnDelta >= 0.1) && (confirmOnDelta <= 30), _messages,
                    "Battery-only heater controller confirm on delta temperature is out of range: " + confirmOnDelta );
            validate( () -> (confirmOnTimeMS >= 10000) && (confirmOnTimeMS <= 600000), _messages,
                    "Battery-only heater controller confirm on time is out of range: " + confirmOnTimeMS );
            validate( () -> (initialCooldownPeriodMS >= 10000) && (initialCooldownPeriodMS <= 600000), _messages,
                    "Battery-only heater controller initial cooldown period is out of range: " + initialCooldownPeriodMS );
            validate( () -> (confirmOffDelta >= -30) && (confirmOffDelta <= -0.1), _messages,
                    "Battery-only heater controller confirm off delta temperature is out of range: " + confirmOffDelta );
            validate( () -> (confirmOffTimeMS >= 10000) && (confirmOffTimeMS <= 600000), _messages,
                    "Battery-only heater controller confirm off time is out of range: " + confirmOffTimeMS );
            validate( () -> (coolingTimeMS >= 60000) && (coolingTimeMS <= 600000), _messages,
                    "Normal heater controller cooling time is out of range: " + coolingTimeMS );
        }
    }


    /**
     * Create the FSM at the heart of this class.
     *
     * @return the FSM created
     */
    private FSM<State, Event> createFSM() {

        FSMSpec<State, Event> spec = new FSMSpec<>( State.OFF, Event.COOLED );

        spec.enableEventScheduling( ss.scheduledExecutor );

        spec.setStateChangeListener( this::stateChange );
        spec.setEventListener( this::event );

        spec.setStateOnEntryAction( State.CONFIRM_SSR_ON,  this::onEntry_ConfirmSSROn  );
        spec.setStateOnEntryAction( State.CONFIRM_SSR_OFF, this::onEntry_ConfirmSSROff );
        spec.setStateOnEntryAction( State.COOLING,         this::onEntry_Cooling       );
        spec.setStateOnEntryAction( State.OFF,             this::onEntry_Off           );

        spec.setStateOnExitAction( State.ON, this::onExit_On );

        spec.addTransition( State.OFF,                Event.LO_BATTERY_TEMP,   this::on_Off_LowBatteryTemp,               State.CONFIRM_SSR_ON     );
        spec.addTransition( State.CONFIRM_SSR_ON,     Event.ON_SENSED,         this::on_ConfirmSSROn_OnSensed,            State.CONFIRM_HEATER_ON  );
        spec.addTransition( State.CONFIRM_HEATER_ON,  Event.NO_TEMP_RISE,      this::on_ConfirmHeaterOn_NoTempRise,       State.HEATER_COOLING     );
        spec.addTransition( State.HEATER_COOLING,     Event.COOLED,            null,                                      State.CONFIRM_SSR_ON     );
        spec.addTransition( State.CONFIRM_HEATER_ON,  Event.BATTERY_TEMP_RISE, this::on_ConfirmHeaterOn_BatteryTempRise,  State.ON                 );
        spec.addTransition( State.ON,                 Event.HI_BATTERY_TEMP,   null,                                      State.CONFIRM_SSR_OFF    );
        spec.addTransition( State.CONFIRM_SSR_OFF,    Event.OFF_SENSED,        this::on_ConfirmSSROff_OffSensed,          State.CONFIRM_HEATER_OFF );
        spec.addTransition( State.CONFIRM_HEATER_OFF, Event.BATTERY_TEMP_DROP, this::on_ConfirmHeaterOff_BatteryTempDrop, State.COOLING            );
        spec.addTransition( State.CONFIRM_HEATER_OFF, Event.NO_TEMP_DROP,      this::on_ConfirmHeaterOff_NoTempDrop,      State.COOLING            );
        spec.addTransition( State.COOLING,            Event.COOLED,            null,                                      State.OFF                );
        spec.addTransition( State.CONFIRM_SSR_ON,     Event.RESET,             null,                                      State.OFF                );
        spec.addTransition( State.CONFIRM_HEATER_ON,  Event.RESET,             null,                                      State.OFF                );
        spec.addTransition( State.HEATER_COOLING,     Event.RESET,             null,                                      State.OFF                );
        spec.addTransition( State.ON,                 Event.RESET,             null,                                      State.OFF                );
        spec.addTransition( State.CONFIRM_HEATER_OFF, Event.RESET,             null,                                      State.OFF                );
        spec.addTransition( State.CONFIRM_SSR_OFF,    Event.RESET,             null,                                      State.OFF                );
        spec.addTransition( State.COOLING,            Event.RESET,             null,                                      State.OFF                );

        if( !spec.isValid() ) {
            LOGGER.log( Level.SEVERE, "Fatal error when constructing battery-only heater controller FSM\n" + spec.getErrorMessage() );
            System.exit( 1 );
        }

        return new FSM<>( spec );
    }
}
