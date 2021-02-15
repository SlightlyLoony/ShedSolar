package com.dilatush.shedsolar;

import com.dilatush.util.AConfig;
import com.dilatush.util.fsm.FSM;
import com.dilatush.util.fsm.FSMSpec;
import com.dilatush.util.fsm.FSMState;
import com.dilatush.util.fsm.FSMTransition;
import com.dilatush.util.info.Info;
import com.dilatush.util.info.InfoView;
import org.shredzone.commons.suncalc.SunTimes;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
public class LightDetector {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    public  final Info<Mode> light;

    private final Config           config;
    private final ShedSolar        shedSolar;
    private final FSM<State,Event> fsm;

    private Consumer<Mode>   lightSetter;


    /**
     * Creates a new instance of this class.  Also subscribes to the events we need to do the job.
     *
     * @param _config the product detector's {@link Config}.
     */
    public LightDetector( final Config _config ) {

        config = _config;
        shedSolar = ShedSolar.instance;

        // set up our published information and default to production just to be safe...
        light = new InfoView<>( ( setter) -> lightSetter = setter, false );
        lightSetter.accept( Mode.LIGHT );

        // create and initialize our FSM...
        fsm = createFSM();

        // schedule our analyzer...
        shedSolar.scheduledExecutor.scheduleAtFixedRate( this::analyze, Duration.ofMillis( config.interval ), Duration.ofMillis( config.interval ) );
    }


    /**
     * Periodically scheduled to analyze the current environment and generate appropriate events for our state machine.
     */
    private void analyze() {

        // we're going to figure out whether we have light...
        boolean light;

        // grab the information we're gonna need...
        Info<OutbackData> outback    = shedSolar.outback.getInfoSource();
        Info<Float>       irradiance = shedSolar.solarIrradiance.getInfoSource();

        // if we've got data from the Outback MATE3S, and it shows < 98% SOC, then the panel power production is our best source of data...
        // it's best because it handles snow-covered panels AND clouds AND daylight...
        if( outback.isInfoAvailable() && (outback.getInfo().stateOfCharge <= 98.0 ) ) {
            light = (outback.getInfo().panelPower > config.panelThreshold);
        }

        // if we can't use the panel data, our next bet is the pyrometer on the weather station...
        // it's next best because it handles clouds AND daylight (though not snow-covered panels)...
        else if( irradiance.isInfoAvailable() ) {
            light = (irradiance.getInfo() > config.pyrometerThreshold);
        }

        // if we can't use the pyrometer data, then we fall back on computed daylight hours...
        // it's our last resort because it handles daylight (but not snow-covered panels or clouds)...
        else {
            light = isDay( config.lat, config.lon );
        }

        // now send the appropriate event to our FSM...
        fsm.onEvent( light ? Event.GOOD_LIGHT : Event.LOW_LIGHT );
    }


    // Create our FSM...
    private FSM<State,Event> createFSM() {

        FSMSpec<State,Event> spec = new FSMSpec<>( State.LIGHT, Event.LIGHT );

        spec.setStateChangeListener( this::stateChange );

        // enable scheduled events, using the global scheduler...
        spec.enableEventScheduling( shedSolar.scheduledExecutor );

        spec.setStateOnEntryAction( State.LIGHT,    this::lightOnEntry );
        spec.setStateOnEntryAction( State.DARK,     this::darkOnEntry );

        spec.addTransition( State.LIGHT,       Event.LOW_LIGHT,    this::onLightLowLight, State.SHAKY_LIGHT );
        spec.addTransition( State.SHAKY_LIGHT, Event.GOOD_LIGHT,   null,                  State.LIGHT       );
        spec.addTransition( State.SHAKY_LIGHT, Event.DARK,         null,                  State.DARK        );
        spec.addTransition( State.DARK,        Event.GOOD_LIGHT,   this::onDarkGoodLight, State.SHAKY_DARK  );
        spec.addTransition( State.SHAKY_DARK,  Event.LOW_LIGHT,    null,                  State.DARK        );
        spec.addTransition( State.SHAKY_DARK,  Event.LIGHT,        null,                  State.LIGHT       );

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


    // LIGHT on entry...
    private void lightOnEntry( final FSMState<State,Event> _state ) {
        lightSetter.accept( Mode.LIGHT );
    }


    // DARK on entry...
    private void darkOnEntry( final FSMState<State,Event> _state ) {
        lightSetter.accept( Mode.DARK );
    }


    // on LIGHT:LOW_LIGHT to SHAKY_LIGHT...
    private void onLightLowLight( final FSMTransition<State,Event> _transition ) {
        _transition.setTimeout( Event.DARK, Duration.ofMillis( config.interval * config.toDormantDelay ) );
    }


    // on DARK:GOOD_LIGHT to SHAKY_LIGHT...
    private void onDarkGoodLight( final FSMTransition<State,Event> _transition ) {
        _transition.setTimeout( Event.LIGHT, Duration.ofMillis( config.interval * config.toProductionDelay ) );
    }


    // the states of our state machine...
    private enum State { LIGHT, DARK, SHAKY_LIGHT, SHAKY_DARK }


    // the events that drive the state machine...
    private enum Event { LOW_LIGHT, GOOD_LIGHT, DARK, LIGHT }


    /**
     * Returns {@code true} if (by computation) the current time is between sunrise and sunset.  Uses the
     * <a href="https://github.com/shred/commons-suncalc" target="_top">commons-suncalc library</a>.
     *
     * @return {@code true} if the current time is between sunrise and sunset
     */
    private boolean isDay( final double _lat, final double _lon ) {

        // get the current time in the Mountain timezone...
        ZonedDateTime now = ZonedDateTime.now( Clock.system( ZoneId.of( "America/Denver" ) ) );
        ZonedDateTime base = now.truncatedTo( ChronoUnit.DAYS );  // gets the past midnight...

        // get the sun times...
        SunTimes sunTimes = SunTimes.compute().on( base ).at(  _lat, _lon  ).oneDay().fullCycle().execute();

        // get the sunrise and sunset times...
        ZonedDateTime sunrise = sunTimes.getRise();
        ZonedDateTime sunset  = sunTimes.getSet();

        // now we can figure it out...
        boolean isDay = now.isAfter( sunrise ) && now.isBefore( sunset );

        assert( sunrise != null && sunset != null );
        LOGGER.finest( () -> "Sunrise: " + sunrise.toString() + "; sunset: " + sunset.toString() + "; is " + (isDay ? "daytime" : "nighttime" ) );
        return isDay;
    }


    public enum Mode {LIGHT, DARK}


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
