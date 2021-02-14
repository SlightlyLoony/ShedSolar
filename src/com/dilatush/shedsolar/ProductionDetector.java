package com.dilatush.shedsolar;

import com.dilatush.util.AConfig;
import com.dilatush.util.fsm.FSM;
import com.dilatush.util.fsm.FSMSpec;
import com.dilatush.util.fsm.FSMState;
import com.dilatush.util.fsm.FSMTransition;
import com.dilatush.util.info.Info;
import com.dilatush.util.info.InfoView;
import org.shredzone.commons.suncalc.SunTimes;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;


/**
 * Keeps track of transitions between periods of possible solar production and periods of solar system dormancy, using information about solar panel
 * output voltage, weather station pyrometer (solar power), and computed sunrise and sunset times.  Note that these transitions can occur because of
 * daytime and nighttime, and also from cloudy daytime conditions.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class ProductionDetector {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    public  final Info<Mode> productionMode;
    private Consumer<Mode>   productionModeSetter;

    private final Config           config;
    private final ShedSolar        shedSolar;
    private final FSM<State,Event> fsm;


    /**
     * Creates a new instance of this class.  Also subscribes to the events we need to do the job.
     *
     * @param _config the product detector's {@link Config}.
     */
    public ProductionDetector( final Config _config ) {

        config = _config;
        shedSolar = ShedSolar.instance;

        // set up our published information and default to production just to be safe...
        productionMode = new InfoView<>( (setter) -> productionModeSetter = setter, false );
        productionModeSetter.accept( Mode.PRODUCTION );

        // create and initialize our FSM...
        fsm = createFSM();

        // schedule our analyzer...
        shedSolar.scheduledExecutor.scheduleAtFixedRate( this::analyze, Duration.ofMillis( config.interval ), Duration.ofMillis( config.interval ) );
    }


    /**
     * Periodically scheduled to analyze the current environment and generate appropriate events for our state machine.
     */
    private void analyze() {

        // get some calculated sunrise/sunset times...
        ZonedDateTime now     = ZonedDateTime.now();
        Instant todaySunrise  = getSunrise( now );
        Instant todaySunset   = getSunset( now );

        // we've got either computed day or computed night...
        boolean day = (now.toInstant().isAfter( todaySunrise ) && now.toInstant().isBefore( todaySunset ));
        fsm.onEvent( day ? Event.COMPUTED_DAY : Event.COMPUTED_NIGHT );

        // we've either got sunlight or we don't (or we have no data)...
        if( shedSolar.solarIrradiance.isInfoAvailable() )
            fsm.onEvent( (shedSolar.solarIrradiance.getInfo() >= config.pyrometerThreshold) ? Event.SOLAR_ADEQUATE : Event.SOLAR_INADEQUATE );

        // if the battery state of charge isn't nearly full, then the panels are either producing or not (or we have no data)...
        if( shedSolar.outback.isInfoAvailable() && (shedSolar.outback.getInfo().stateOfCharge <= 0.98) )
            fsm.onEvent( (shedSolar.outback.getInfo().panelPower >= config.panelThreshold) ? Event.PANELS_PRODUCING : Event.PANELS_DORMANT );
    }


    private FSM<State,Event> createFSM() {

        FSMSpec<State,Event> spec = new FSMSpec<>( State.PRODUCTION, Event.COMPUTED_DAY );

        spec.setStateChangeListener( this::stateChange );

        // enable scheduled events, using the global scheduler...
        spec.enableEventScheduling( shedSolar.scheduledExecutor );

        spec.setStateOnEntryAction( State.PRODUCTION,    this::productionOnEntry );
        spec.setStateOnEntryAction( State.DORMANT_DAY,   this::dormantOnEntry    );
        spec.setStateOnEntryAction( State.DORMANT_NIGHT, this::dormantOnEntry    );

        spec.addTransition( State.PRODUCTION,       Event.COMPUTED_NIGHT,      null,                              State.DORMANT_NIGHT     );
        spec.addTransition( State.LOW_PANEL_POWER,  Event.COMPUTED_NIGHT,      null,                              State.DORMANT_NIGHT     );
        spec.addTransition( State.LOW_SOLAR,        Event.COMPUTED_NIGHT,      null,                              State.DORMANT_NIGHT     );
        spec.addTransition( State.DORMANT_DAY,      Event.COMPUTED_NIGHT,      null,                              State.DORMANT_NIGHT     );
        spec.addTransition( State.HIGH_PANEL_POWER, Event.COMPUTED_NIGHT,      null,                              State.DORMANT_NIGHT     );
        spec.addTransition( State.HIGH_SOLAR,       Event.COMPUTED_NIGHT,      null,                              State.DORMANT_NIGHT     );
        spec.addTransition( State.PRODUCTION,       Event.SOLAR_INADEQUATE,    this::onProductionSolarInadequate, State.LOW_SOLAR         );
        spec.addTransition( State.PRODUCTION,       Event.PANELS_DORMANT,      this::onProductionPanelsDormant,   State.LOW_PANEL_POWER   );
        spec.addTransition( State.LOW_SOLAR,        Event.SOLAR_ADEQUATE,      null,                              State.PRODUCTION        );
        spec.addTransition( State.LOW_SOLAR,        Event.LOW_SOLAR_TIMEOUT,   null,                              State.DORMANT_DAY       );
        spec.addTransition( State.LOW_PANEL_POWER,  Event.PANELS_PRODUCING,    null,                              State.PRODUCTION        );
        spec.addTransition( State.LOW_PANEL_POWER,  Event.LOW_PANELS_TIMEOUT,  null,                              State.DORMANT_DAY       );
        spec.addTransition( State.DORMANT_DAY,      Event.SOLAR_ADEQUATE,      this::onDormantDaySolarAdequate,   State.HIGH_SOLAR        );
        spec.addTransition( State.DORMANT_DAY,      Event.PANELS_PRODUCING,    this::onDormantDayPanelsProducing, State.HIGH_PANEL_POWER  );
        spec.addTransition( State.HIGH_SOLAR,       Event.SOLAR_INADEQUATE,    null,                              State.DORMANT_DAY       );
        spec.addTransition( State.HIGH_SOLAR,       Event.HIGH_SOLAR_TIMEOUT,  null,                              State.PRODUCTION        );
        spec.addTransition( State.HIGH_PANEL_POWER, Event.PANELS_DORMANT,      null,                              State.DORMANT_DAY       );
        spec.addTransition( State.HIGH_PANEL_POWER, Event.HIGH_PANELS_TIMEOUT, null,                              State.PRODUCTION        );
        spec.addTransition( State.DORMANT_NIGHT,    Event.COMPUTED_DAY,        null,                              State.DORMANT_DAY       );

        FSM<State,Event> trial = null;
        try {
            trial = new FSM<>( spec );
        }
        catch( IllegalArgumentException _e ) {
            LOGGER.log( Level.SEVERE, "Fatal error when constructing FSM for Production Detector\n" + spec.getErrorMessage() );
            System.exit( 1 );
        }
        return trial;
    }


    // state change listener...
    private void stateChange( final State _state ) {
        LOGGER.finer( "Production Detector state changed to " + _state );
    }


    // PRODUCTION on entry...
    private void productionOnEntry( final FSMState<State,Event> _state ) {
        productionModeSetter.accept( Mode.PRODUCTION );
    }


    // DORMANT_DAY or DORMANT_NIGHT on entry...
    private void dormantOnEntry( final FSMState<State,Event> _state ) {
        productionModeSetter.accept( Mode.DORMANT );
    }


    // on PRODUCTION:SOLAR_INADEQUATE to LOW_SOLAR...
    private void onProductionSolarInadequate( final FSMTransition<State,Event> _transition ) {
        _transition.setTimeout( Event.LOW_SOLAR_TIMEOUT, Duration.ofMillis( config.interval * config.toDormantDelay ) );
    }


    // on PRODUCTION:PANELS_DORMANT to LOW_PANEL_POWER...
    private void onProductionPanelsDormant( final FSMTransition<State,Event> _transition ) {
        _transition.setTimeout( Event.LOW_PANELS_TIMEOUT, Duration.ofMillis( config.interval * config.toDormantDelay ) );
    }


    // on DORMANT_DAY:SOLAR_ADEQUATE to HIGH_SOLAR...
    private void onDormantDaySolarAdequate( final FSMTransition<State,Event> _transition ) {
        _transition.setTimeout( Event.HIGH_SOLAR_TIMEOUT, Duration.ofMillis( config.interval * config.toProductionDelay ) );
    }


    // on DORMANT_DAY:PANELS_PRODUCING to HIGH_PANEL_POWER
    private void onDormantDayPanelsProducing( final FSMTransition<State,Event> _transition ) {
        _transition.setTimeout( Event.HIGH_PANELS_TIMEOUT, Duration.ofMillis( config.interval * config.toProductionDelay ) );
    }


    // the states of our state machine...
    private enum State { DORMANT_DAY, DORMANT_NIGHT, HIGH_SOLAR, HIGH_PANEL_POWER, LOW_SOLAR, LOW_PANEL_POWER, PRODUCTION }


    // the events that drive the state machine...
    private enum Event { COMPUTED_NIGHT, COMPUTED_DAY, SOLAR_ADEQUATE, SOLAR_INADEQUATE, PANELS_PRODUCING, PANELS_DORMANT,
                                LOW_SOLAR_TIMEOUT, LOW_PANELS_TIMEOUT, HIGH_SOLAR_TIMEOUT, HIGH_PANELS_TIMEOUT }



    /**
     * Returns the computed sunrise time at this instance's location on the given date.
     *
     * @param _date the date to compute sunrise time for
     * @return the computed sunrise time
     */
    private Instant getSunrise( ZonedDateTime _date ) {
        SunTimes times = SunTimes.compute()
                .on( _date )
                .at( config.lat, config.lon )
                .execute();
        return Objects.requireNonNull( times.getRise() ).toInstant();
    }


    /**
     * Returns the computed sunset time at this instance's location on the given date.
     *
     * @param _date the date to compute sunset time for
     * @return the computed sunset time
     */
    private Instant getSunset( ZonedDateTime _date ) {
        SunTimes times = SunTimes.compute()
                .on( _date )
                .at( config.lat, config.lon )
                .execute();
        return Objects.requireNonNull( times.getSet() ).toInstant();
    }


    public enum Mode { PRODUCTION, DORMANT }


    public static class Config extends AConfig {

        /**
         * The latitude, in degrees, of the solar system's location.  This is used to calculate the sunrise and sunset times.  The value must be in
         * the range [-90..90].  There is no default value.
         */
        public double lat;

        /**
         * The longitude, in degrees, of the solar system's location.  This is used to calculate the sunrise and sunset times.  The value must be in
         * the range [-180..180].  There is no default value.
         */
        public double lon;

        /**
         * The pyrometer reading (in watts/square meter) threshold.  Values above the specified value indicate enough light for solar production.
         * The value must be in the range [0..1200]; the default value is 80.
         */
        public float pyrometerThreshold = 80;

        /**
         * The solar panel power threshold.  Values above the specified value indicate enough light for solar production.  The value must be in
         * the range [0..10000]; the default value is 225.
         */
        public float panelThreshold = 225;

        /**
         * The interval (in milliseconds) that the production detector operates on; the "ticks" of its clock.  The value must be in the
         * range [10,000..600,000]; the default value is 60,000 (one minute).
         */
        public long interval = 60000;

        /**
         * The delay before switching from dormant to production mode, when adequate brightness has been detected, in "ticks" (see interval).  The
         * idea behind this delay is to avoid jumping to production mode if there's only a brief burst of light, like a hole in the clouds.  The
         * value must be in the range [0..120]; the default value is 5.
         */
        public int toProductionDelay = 5;

        /**
         * The delay before switching from production to dormant mode, when inadequate brightness has been detected, in "ticks" (see interval).  The
         * idea behind this delay is to avoid jumping to dormant mode if there's oly a brief interruption of light, like a cloud blocking the sun.
         * The value must be in the range [0..240]; the default value is 60.
         */
        public int toDormantDelay = 60;


        /**
         * Verify the fields of this configuration.
         */
        @Override
        public void verify( final List<String> _messages ) {
            validate( () -> ((lat >= -90) && (lat <= 90)), _messages,
                    "Production Detector latitude out of range: " + lat );
            validate( () -> ((lon >= -180) && (lon <= 180)), _messages,
                    "Production Detector longitude out of range: " + lat );
            validate( () -> ((pyrometerThreshold >= 0) && (pyrometerThreshold <= 1200)), _messages,
                    "Production Detector pyrometer watts/square meter threshold is out of range: " + pyrometerThreshold );
            validate( () -> ((panelThreshold >= 0) && (panelThreshold <= 10000)), _messages,
                    "Production Detector solar panel power threshold is out of range: " + panelThreshold );
            validate( () -> ((interval >= 10000) && (interval <= 600000)), _messages,
                    "Production Detector 'tick' interval (in milliseconds) is out of range: " + interval);
            validate( () -> ((toProductionDelay >= 0) && (toProductionDelay <= 120)), _messages,
                    "Production Detector to production delay (in 'ticks') is out of range: " + toProductionDelay );
            validate( () -> ((toDormantDelay >= 0) && (toDormantDelay <= 240)), _messages,
                    "Production Detector to dormant delay (in 'ticks') is out of range: " + toDormantDelay );
        }
    }
}
