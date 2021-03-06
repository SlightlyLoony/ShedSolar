package com.dilatush.shedsolar;

import com.dilatush.util.AConfig;
import com.dilatush.util.ScheduledExecutor;
import com.dilatush.util.info.InfoSource;
import com.pi4j.io.gpio.GpioPinDigitalOutput;
import com.pi4j.io.gpio.PinState;
import com.pi4j.io.gpio.RaspiPin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.dilatush.shedsolar.Events.*;

/**
 * Controls the status LED on the ShedSolar box.  This LED provides status information via a sort of Morse code, using long and short flashes.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class StatusLED {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    // the messages the status LED can display...
    private static final String OK                      = "S";
    private static final String SOC_LOW                 = "L";
    private static final String BATTERY_LOW_TEMP        = "LS";
    private static final String BATTERY_HIGH_TEMP       = "LL";
    private static final String NO_BATTERY_TEMP         = "SS";
    private static final String NO_HEATER_TEMP          = "SL";
    private static final String NO_OUTBACK_DATA         = "SSS";
    private static final String SSR_FAILURE             = "SSL";
    private static final String SENSE_RELAY_FAILURE     = "SLS";
    private static final String HEATER_FAILURE          = "SLL";
    private static final String NO_WEATHER_DATA         = "LSS";
    private static final String DATABASE_LOG_NOT_POSTED = "LSL";

    private static final float MIN_BATTERY_TEMP = 0;
    private static final float MAX_BATTERY_TEMP = 45;

    private final Config               config;
    private final ShedSolar            ss;
    private final GpioPinDigitalOutput statusLED;
    private final ScheduledExecutor    se;

    private volatile boolean possibleSSRFailure;
    private volatile boolean possibleSenseRelayFailure;
    private volatile boolean possibleHeaterFailure;
    private volatile Instant lastDatabaseLogPosted = Instant.now( Clock.systemUTC() );


    /**
     * Create a new instance of this class with the given configuration.
     *
     * @param _config the configuration
     */
    public StatusLED( final Config _config ) {

        // squirrel away our configuration...
        config = _config;

        // a short alias for our ShedSolar instance...
        ss = ShedSolar.instance;

        // and another short alias for our ScheduledExecutor...
        se = ss.scheduledExecutor;

        // set up our LED control...
        statusLED = ss.getGPIO().provisionDigitalOutputPin( RaspiPin.GPIO_04, "Status LED", PinState.HIGH );
        statusLED.setShutdownOptions( true, PinState.HIGH );

        // subscribe to the haps we need...
        ss.haps.subscribe( POSSIBLE_HEATER_FAILURE,      this::heaterFailure     );
        ss.haps.subscribe( POSSIBLE_SSR_FAILURE,         this::ssrFailure        );
        ss.haps.subscribe( POSSIBLE_SENSE_RELAY_FAILURE, this::senseRelayFailure );
        ss.haps.subscribe( HEATER_WORKING,               this::heaterWorking     );
        ss.haps.subscribe( SSR_WORKING,                  this::ssrWorking        );
        ss.haps.subscribe( SENSE_RELAY_WORKING,          this::senseRelayWorking );
        ss.haps.subscribe( DATABASE_LOG_POSTED,          this::databaseLogPosted );

        // start our first cycle...
        startCycle();
    }


    /****************************
     *  H A P   H A N D L E R S
     ****************************/

    private void databaseLogPosted() {
        lastDatabaseLogPosted = Instant.now( Clock.systemUTC() );
    }


    private void heaterFailure() {
        possibleHeaterFailure = true;
    }


    private void heaterWorking() {
        possibleHeaterFailure = false;
    }


    private void senseRelayFailure() {
        possibleSenseRelayFailure = true;
    }


    private void senseRelayWorking() {
        possibleSenseRelayFailure = false;
    }


    private void ssrFailure() {
        possibleSSRFailure = true;
    }


    private void ssrWorking() {
        possibleSSRFailure = false;
    }


    /**
     * Start a cycle of the status LED, which may include 1 or more messages.
     */
    private void startCycle() {

        // get the string with our cycle's worth of messages...
        String cycle = getCycle();

        // process the messages, scheduling all our LED on or off jobs...
        boolean flashLast = false;
        long ms = config.interCycleDelayMS;
        for( int i = 0; i < cycle.length(); i++ ) {

            char c = cycle.charAt( i );

            switch( c ) {

                case 'L':
                    if( flashLast )
                        ms += config.interFlashDelayMS;
                    se.schedule( this::onLED, Duration.ofMillis( ms ) );
                    ms += config.longFlashMS;
                    se.schedule( this::offLED, Duration.ofMillis( ms ) );
                    flashLast = true;
                    break;

                case 'S':
                    if( flashLast )
                        ms += config.interFlashDelayMS;
                    se.schedule( this::onLED, Duration.ofMillis( ms ) );
                    ms += config.shortFlashMS;
                    se.schedule( this::offLED, Duration.ofMillis( ms ) );
                    flashLast = true;
                    break;

                case ' ':
                    flashLast = false;
                    ms += config.interMessageDelayMS;
                    break;

                default:
                    LOGGER.log( Level.SEVERE, "Illegal character in message string: " + c );
                    break;
            }
        }

        // finally, schedule this cycle starting all over again...
        ms += 1;  // just to get past the last LED off...
        se.schedule( this::startCycle, Duration.ofMillis( ms ) );
    }


    /**
     * <p>Returns a string containing the messages for the entire current cycle.  Each message consists of a string of "S" (for short) and "L" (for long)
     * characters.  When non-space characters are adjacent, there is an implied inter-flash delay between them.  If there are multiple messages, they
     * are separated by a space (which represents an inter-message delay).  For example, the string "L SLLS LLSS" encodes three messages, the first
     * with a single long flash, the second with a short flash, two long flashes, and a short flash, and the third with two long flashes followed by
     * two short flashes.</p>
     * <p>Which messages are included in the result is determined by the read of current status done in this method.</p>
     *
     * @return the string containing the messages for the current cycle
     */
    private String getCycle() {

        // where we build the result...
        StringBuilder result = new StringBuilder( 100 );

        // collect some information sources...
        InfoSource<OutbackData> outback   = ss.outback.getInfoSource();
        InfoSource<Float> battery         = ss.batteryTemperature.getInfoSource();
        InfoSource<Float> heater          = ss.heaterTemperature.getInfoSource();
        InfoSource<Float> solarIrradiance = ss.solarIrradiance.getInfoSource();

        // check for SOC (state of charge) low...
        if( outback.isInfoAvailable() && outback.getInfo().stateOfCharge < 20 )
            append( result, SOC_LOW );

        // check for temperature availability...
        if( !battery.isInfoAvailable() )
            append( result, NO_BATTERY_TEMP );
        if( !heater.isInfoAvailable() )
            append( result, NO_HEATER_TEMP );

        // check our battery temperature...
        if( battery.isInfoAvailable() ) {
            if( battery.getInfo() < MIN_BATTERY_TEMP )
                append( result, BATTERY_LOW_TEMP );
            if( battery.getInfo() > MAX_BATTERY_TEMP )
                append( result, BATTERY_HIGH_TEMP );
        }

        // check our Outback data...
        if( !outback.isInfoAvailable()
                || outback.getInfoTimestamp().isBefore( Instant.now( Clock.systemUTC() ).minusSeconds( 600 ) ) )
            append( result, NO_OUTBACK_DATA );

        // check our weather data (by seeing if we have fresh solar irradiance)...
        if( !solarIrradiance.isInfoAvailable()
                ||  solarIrradiance.getInfoTimestamp().isBefore( Instant.now( Clock.systemUTC() ).minusSeconds( 600 ) ) )
            append( result, NO_WEATHER_DATA );

        // check whether we've posted a database log recently...
        if( lastDatabaseLogPosted.isBefore( Instant.now( Clock.systemUTC() ).minusSeconds( 300 ) ) )
            append( result, DATABASE_LOG_NOT_POSTED );

        // check our failure modes...
        if( possibleHeaterFailure )
            append( result, HEATER_FAILURE );
        if( possibleSSRFailure )
            append( result, SSR_FAILURE );
        if( possibleSenseRelayFailure )
            append( result, SENSE_RELAY_FAILURE );


        // if we have no messages in the result yet, we put in the default "all is well" message...
        if( result.length() == 0 )
            result.append( OK );

        return result.toString();
    }


    private void append( final StringBuilder _builder, final String _string ) {
        if( _builder.length() != 0 )
            _builder.append( ' ' );
        _builder.append( _string );
    }


    private void onLED() {
        statusLED.low();
    }


    private void offLED() {
        statusLED.high();
    }


    public static class Config extends AConfig {


        /**
         * The duration of a short (0) flash, in milliseconds.  The default value is 250; valid values are in the range [50..1000].
         */
        public long shortFlashMS = 250;


        /**
         * The duration of a long (1) flash, in milliseconds.  The default value is 750; valid values are in the range [100..2000].
         */
        public long longFlashMS = 750;


        /**
         * The delay between flashes within a single message, in milliseconds.  The default value is 250; valid values are in the range [100..1000].
         */
        public long interFlashDelayMS = 250;


        /**
         * The delay between messages within a single cycle, in milliseconds.  The default value is 1000; valid values are in the range [500..3000].
         */
        public long interMessageDelayMS = 1000;


        /**
         * The delay between cycles, in milliseconds.  The default value is 2000; valid values are in the range [1000..10000].
         */
        public long interCycleDelayMS = 2000;


        @Override
        public void verify( final List<String> _messages ) {

            validate( () -> (shortFlashMS >= 50) && (shortFlashMS <= 1000), _messages,
                    "Short flash time is out of range: " + shortFlashMS );
            validate( () -> (longFlashMS >= 100) && (longFlashMS <= 2000), _messages,
                    "Short flash time is out of range: " + longFlashMS );
            validate( () -> (longFlashMS >= 2 * shortFlashMS), _messages,
                    "Long flash time is not at least twice the short flash time" );
            validate( () -> (interFlashDelayMS >= 100) && (interFlashDelayMS <= 1000), _messages,
                    "Inter-flash delay is out of range: " + interFlashDelayMS );
            validate( () -> (interFlashDelayMS <= shortFlashMS), _messages,
                    "Inter-flash delay is longer than short flash time" );
            validate( () -> (interMessageDelayMS >= 500) && (interMessageDelayMS <= 3000), _messages,
                    "Inter-message delay is out of range: " + interMessageDelayMS );
            validate( () -> (interMessageDelayMS >= 2 * interFlashDelayMS), _messages,
                    "Inter-message delay is not at least twice the inter-flash delay" );
            validate( () -> (interCycleDelayMS >= 1000) && (interCycleDelayMS <= 10000), _messages,
                    "Inter-cycle delay is out of range: " + interCycleDelayMS );
            validate( () -> (interCycleDelayMS >= 2 * interMessageDelayMS), _messages,
                    "Inter-cycle delay is not at least twice the inter-message delay" );
        }
    }
}
