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
public class NormalHeaterController implements HeaterController {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private final FSM<State,Event>  fsm;           // the finite state machine at the heart of this class...
    private final Config            config;        // the configuration loaded from JavaScript...

    private float                   startingTemp;  // temperature going into confirming heater on or off..
    private HeaterControllerContext context;       // the controller context as of the most recent tick...
    private boolean                 senseRelay;    // true if the relay sensed that the heater power is on...
    private int                     turnOnTries;   // number of times we've tried to turn the heater on...


    // the states of our FSM...
    private enum State {
        OFF, CONFIRM_SSR_ON, HEATER_COOLING, CONFIRM_HEATER_ON, ON, CONFIRM_SSR_OFF, CONFIRM_HEATER_OFF, COOLING }

    // the events that drive our FSM...
    private enum Event {
        LO_BATTERY_TEMP, ON_SENSED, NO_TEMP_RISE, COOLED, HEATER_TEMP_RISE, HI_BATTERY_TEMP,
        HI_HEATER_TEMP, OFF_SENSED, HEATER_TEMP_DROP, NO_TEMP_DROP, RESET }


    /**
     * Creates a new instance of this class with the given configuration.
     *
     * @param _config The {@link Config} configuration.
     */
    public NormalHeaterController( final Config _config ) {

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
        if( _context.batteryTemp < _context.loTemp )
            fsm.onEvent( Event.LO_BATTERY_TEMP );
        if( _context.batteryTemp > _context.hiTemp )
            fsm.onEvent( Event.HI_BATTERY_TEMP );

        // issue high heater temp, if warranted...
        if( _context.batteryTemp > config.heaterTempLimit )
            fsm.onEvent( Event.HI_HEATER_TEMP );

        // if we're in confirming heater on state, see if we've risen enough...
        if( fsm.getStateEnum() == State.CONFIRM_HEATER_ON ) {
            if( _context.batteryTemp > (startingTemp + config.confirmOnDelta ) )
                fsm.onEvent( Event.HEATER_TEMP_RISE );
        }

        // if we're in confirming heater off state, see if we've dropped enough...
        if( fsm.getStateEnum() == State.CONFIRM_HEATER_OFF ) {
            if( _context.batteryTemp < (startingTemp + config.confirmOffDelta ) )
                fsm.onEvent( Event.HEATER_TEMP_DROP );
        }
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

    // on OFF:LOW_BATTERY_TEMP -> CONFIRM_SSR_ON...
    private void on_Off_LowBatteryTemp( final FSMTransition<State,Event> _transition ) {

        // number of times we've tried to zero...
        turnOnTries = 0;
    }


    // on CONFIRM_SSR_ON:ON_SENSED -> CONFIRM_HEATER_ON...
    private void on_ConfirmSSROn_OnSensed( final FSMTransition<State,Event> _transition ) {

        // read our sense relay and stuff the result away for later use...
        senseRelay = context.ssrSense.isLow();

        // set a timeout for the configured time we'll wait for a temperature rise...
        _transition.setTimeout( Event.NO_TEMP_RISE, Duration.ofMillis( config.confirmOnTimeMS ) );
    }


    // on CONFIRM_HEATER_ON:NO_TEMP_RISE -> HEATER_COOLING...
    private void on_ConfirmHeaterOn_NoTempRise( final FSMTransition<State,Event> _transition ) {

        // turn off the heater, as we're gonna cool down for a while...
        context.heaterSSR.high();
        context.heaterPowerLED.high();

        // set a timeout for a cooldown period, more time for more tries...
        turnOnTries = Math.max( 5, turnOnTries + 1 );
        _transition.setTimeout( Event.COOLED, Duration.ofMillis( config.initialCooldownPeriodMS * turnOnTries ) );

        // if we've tried more than 5 times, we may have a dead SSR or a dead heater - send a Hap to that effect...
        ShedSolar.instance.haps.post( senseRelay ? Events.POSSIBLE_HEATER_FAILURE : Events.POSSIBLE_SSR_FAILURE );
    }


    // on CONFIRM_SSR_OFF:OFF_SENSED -> CONFIRM_HEATER_OFF...
    private void on_ConfirmSSROff_OffSensed( final FSMTransition<State,Event> _transition ) {

        // read our sense relay and stuff the result away for later use...
        senseRelay = context.ssrSense.isLow();

        // set a timeout for the configured time we'll wait for a temperature drop...
        _transition.setTimeout( Event.NO_TEMP_DROP, Duration.ofMillis( config.confirmOffTimeMS ) );
    }


    // on CONFIRM_HEATER_OFF:NO_TEMP_DROP -> COOLING...
    private void on_ConfirmHeaterOff_NoTempDrop( final FSMTransition<State,Event> _transition ) {

        // if we are still sensing power, we may have a stuck SSR - send a Hap to that effect...
        if( senseRelay )
            ShedSolar.instance.haps.post( Events.POSSIBLE_SSR_FAILURE );
    }


    // on entry to CONFIRM_SSR_ON...
    private void onEntry_ConfirmSSROn( final FSMState<State,Event> _state ) {

        // record our starting temperature (so we can sense the temperature rise)...
        startingTemp = context.heaterTemp;

        // turn on the heater and the heater LED...
        context.heaterSSR.low();
        context.heaterPowerLED.low();

        // set a timeout for 100 ms to check sense relay...
        _state.fsm.scheduleEvent( Event.ON_SENSED, Duration.ofMillis( 100 ) );
    }


    // on entry to CONFIRM_SSR_OFF...
    private void onEntry_ConfirmSSROff( final FSMState<State,Event> _state ) {

        // set a timeout for 100 ms to check sense relay...
        _state.fsm.scheduleEvent( Event.OFF_SENSED, Duration.ofMillis( 100 ) );
    }


    // on entry to COOLING...
    private void onEntry_Cooling( final FSMState<State,Event> _state ) {

        // set a timeout for our cooling period...
        _state.fsm.scheduleEvent( Event.COOLED, Duration.ofMillis( config.coolingTimeMS ) );
    }



    // on entry to OFF...
    private void onEntry_Off( final FSMState<State,Event> _state ) {

        // turn off the SSR and the heater LED (matters for reset event)...
        // if we didn't have a context, then they're already off...
        if( context != null ) {
            context.heaterSSR.high();
            context.heaterPowerLED.high();
        }
    }


    // on exit from ON...
    private void onExit_On( final FSMState<State,Event> _state ) {

        // record the starting temperature (so we can sense the temperature drop)...
        startingTemp = context.heaterTemp;

        // turn off the heater and LED...
        context.heaterSSR.high();
        context.heaterPowerLED.high();
    }


    /**
     * The configuration for this class, normally from JavaScript.
     */
    public static class Config extends AConfig {

        // positive number
        public float confirmOnDelta;

        public long confirmOnTimeMS;

        public long initialCooldownPeriodMS;

        // negative number
        public float confirmOffDelta;

        public long confirmOffTimeMS;


        public float heaterTempLimit;

        public long coolingTimeMS;

        @Override
        public void verify( final List<String> _list ) {

        }
    }


    /**
     * Create the FSM at the heart of this class.
     *
     * @return the FSM created
     */
    private FSM<State,Event> createFSM() {

        FSMSpec<State,Event> spec = new FSMSpec<>( State.OFF, Event.COOLED );

        spec.enableEventScheduling( ShedSolar.instance.scheduledExecutor );

        spec.setStateOnEntryAction( State.CONFIRM_SSR_ON,  this::onEntry_ConfirmSSROn  );
        spec.setStateOnEntryAction( State.CONFIRM_SSR_OFF, this::onEntry_ConfirmSSROff );
        spec.setStateOnEntryAction( State.COOLING,         this::onEntry_Cooling       );
        spec.setStateOnEntryAction( State.OFF,             this::onEntry_Off           );

        spec.setStateOnExitAction( State.ON, this::onExit_On );

        spec.addTransition( State.OFF,                Event.LO_BATTERY_TEMP,  this::on_Off_LowBatteryTemp,          State.CONFIRM_SSR_ON     );
        spec.addTransition( State.CONFIRM_SSR_ON,     Event.ON_SENSED,        this::on_ConfirmSSROn_OnSensed,       State.CONFIRM_HEATER_ON  );
        spec.addTransition( State.CONFIRM_HEATER_ON,  Event.NO_TEMP_RISE,     this::on_ConfirmHeaterOn_NoTempRise,  State.HEATER_COOLING     );
        spec.addTransition( State.HEATER_COOLING,     Event.COOLED,           null, State.CONFIRM_SSR_ON     );
        spec.addTransition( State.CONFIRM_HEATER_ON,  Event.HEATER_TEMP_RISE, null, State.ON                 );
        spec.addTransition( State.ON,                 Event.HI_BATTERY_TEMP,  null, State.CONFIRM_SSR_OFF    );
        spec.addTransition( State.ON,                 Event.HI_HEATER_TEMP,   null, State.CONFIRM_SSR_OFF    );
        spec.addTransition( State.CONFIRM_SSR_OFF,    Event.OFF_SENSED,       this::on_ConfirmSSROff_OffSensed,     State.CONFIRM_HEATER_OFF );
        spec.addTransition( State.CONFIRM_HEATER_OFF, Event.HEATER_TEMP_DROP, null, State.COOLING            );
        spec.addTransition( State.CONFIRM_HEATER_OFF, Event.NO_TEMP_DROP,     this::on_ConfirmHeaterOff_NoTempDrop, State.COOLING            );
        spec.addTransition( State.COOLING,            Event.COOLED,           null, State.OFF                );
        spec.addTransition( State.CONFIRM_SSR_ON,     Event.RESET,            null, State.OFF                );
        spec.addTransition( State.CONFIRM_HEATER_ON,  Event.RESET,            null, State.OFF                );
        spec.addTransition( State.HEATER_COOLING,     Event.RESET,            null, State.OFF                );
        spec.addTransition( State.ON,                 Event.RESET,            null, State.OFF                );
        spec.addTransition( State.CONFIRM_HEATER_OFF, Event.RESET,            null, State.OFF                );
        spec.addTransition( State.CONFIRM_SSR_OFF,    Event.RESET,            null, State.OFF                );
        spec.addTransition( State.COOLING,            Event.RESET,            null, State.OFF                );

        if( !spec.isValid() ) {
            LOGGER.log( Level.SEVERE, "Fatal error when constructing FSM\n" + spec.getErrorMessage() );
            System.exit( 1 );
        }

        return new FSM<>( spec );
    }
}
