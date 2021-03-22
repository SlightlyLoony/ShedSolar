package com.dilatush.shedsolar;

import com.dilatush.mop.Actor;
import com.dilatush.mop.Message;
import com.dilatush.mop.PostOffice;
import com.dilatush.util.info.Info;
import com.dilatush.util.info.InfoSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import static com.dilatush.shedsolar.Events.*;

/**
 * Posts events (via MOP) to the events system on beast.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class EventSender extends Actor {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private static final String eventSenderName = "events";

    private static final float MIN_SAFE_BATTERY_TEMPERATURE = 5.0f;

    private final Map<String,Instant> badReadTracking;

    // a short alias...
    private final ShedSolar ss = ShedSolar.instance;


    /**
     * Create a new instance of this class using the given {@link PostOffice}.
     *
     * @param _po the {@link PostOffice} to use
     */
    public EventSender( final PostOffice _po ) {
        super( _po, eventSenderName );

        // some initialization...
        badReadTracking = new HashMap<>();

        // subscribe to the haps we want to use...
        ss.haps.subscribe( HEATER_NO_START, this::heaterNoStart );
        ss.haps.subscribe( HEATER_ON,       this::heaterOn );
        ss.haps.subscribe( HEATER_OFF,      this::heaterOff );
        ss.haps.subscribe( BAD_TEMP_READ,   this::badRead );

        // set our periodic check schedules...
        ss.scheduledExecutor.scheduleAtFixedRate( this::perHour,     Duration.ofSeconds( 60 * 60 ), Duration.ofSeconds( 60 * 60 ) );
        ss.scheduledExecutor.scheduleAtFixedRate( this::perHalfHour, Duration.ofSeconds( 30 * 60 ), Duration.ofSeconds( 30 * 60 ) );
    }


    /**********************************
     *  P E R I O D I C   C H E C K S
     **********************************/

    private void perHour() {

        // check temperature availabilities...
        if( isTempUnavailable( ss.batteryTemperature ) )
            sendEvent( "batteryTemperature.notAvailable", "batteryTemperature.notAvailable", 5,
                    "Battery temperature is not available", "Battery temperature is not available" );
        if( isTempUnavailable( ss.heaterTemperature ) )
            sendEvent( "heaterTemperature.notAvailable", "heaterTemperature.notAvailable", 5,
                    "Heater temperature is not available", "Heater temperature is not available" );
        if( isTempUnavailable( ss.ambientTemperature ) )
            sendEvent( "ambientTemperature.notAvailable", "ambientTemperature.notAvailable", 5,
                    "Ambient temperature is not available", "Ambient temperature is not available" );
        if( isTempUnavailable( ss.outsideTemperature ) )
            sendEvent( "outsideTemperature.notAvailable", "outsideTemperature.notAvailable", 5,
                    "Outside temperature is not available", "Outside temperature is not available" );

        // check Outback data availability...
        if( !ss.outback.isInfoAvailable() )
            sendEvent( "outback.notAvailable", "outback.notAvailable", 6,
                    "OutbackData data is not available", "OutbackData data is not available" );
    }


    private void perHalfHour() {

        // check for battery dangerously low temperature...
        InfoSource<Float> battery = ss.batteryTemperature.getInfoSource();
        if( battery.isInfoAvailable() ) {
            if( battery.getInfo() <= MIN_SAFE_BATTERY_TEMPERATURE )
                sendEvent( "battery.lowTemp", "battery.lowTemp", 9,
                        String.format( "Battery temperature is dangerously low: %1$.2f°C", battery.getInfo() ),
                        String.format( "Battery temperature is dangerously low: %1$.2f°C", battery.getInfo() ) );
        }
    }


    /**
     * Returns {@code true} if the given {@link Info} (which should represent a measured temperature) is unavailable or has not been refreshed within
     * 5 minutes.
     *
     * @param _temp The temperature to check for availability.
     * @return {@code true} if the given temperature is unavailable or has not been refreshed within 5 minutes
     */
    private boolean isTempUnavailable( final Info<Float> _temp ) {
        InfoSource<Float> source = _temp.getInfoSource();
        if( !source.isInfoAvailable() )
            return true;
        return !source.getInfoTimestamp().isAfter( Instant.now( Clock.systemUTC() ).minusSeconds( 5 * 60 ) );
    }


    /****************************
     *  H A P   H A N D L E R S
     ****************************/

    private void heaterNoStart() {
        sendEvent( "heater.noStart", "heater.noStart", 2, "ShedSolar heater failed to start", "ShedSolar heater failed to start" );
    }

    private void heaterOn() {
        sendEvent( "heater.on", "heater.on", 1, "ShedSolar heater turned on", "ShedSolar heater turned on" );
    }

    private void heaterOff() {
        sendEvent( "heater.off", "heater.off", 1, "ShedSolar heater turned off", "ShedSolar heater turned off" );
    }


    private void badRead( final Object _message ) {

        // if we don't have a string, we've got a problem...
        if( !(_message instanceof String) ) {
            LOGGER.severe( "Got bad temperature read hap with no string message" );
            return;
        }

        /* we go to some trouble to only send an event if we see the same problem repeatedly */

        // get our message and key...
        String msg = (String) _message;
        int first = msg.indexOf( "raw " ) + 4;
        int last = msg.indexOf( ", meaning" );
        String key = msg.substring( 0, first ) + msg.substring( last );

        // have we seen this exact message before?
        if( badReadTracking.containsKey( key ) ) {

            // if we saw it within 5 seconds, report it...
            if( badReadTracking.get( key ).isAfter( Instant.now( Clock.systemUTC() ).minusSeconds( 5 ) ) ) {
                sendEvent( "temperature.badRead", "temperature.badRead", 3, msg, msg );
            }

            // delete it so we don't send too many events...
            badReadTracking.remove( key );
        }

        // no, so add it for tracking purposes...
        else
            badReadTracking.put( key, Instant.now( Clock.systemUTC() ) );
    }


    /**
     * Send an event via MOP, with the given parameters.
     *
     * @param _type The type of event (used to dispatch the event, together with source).
     * @param _tag  The tag for the event (used to control what happens to an event).
     * @param _level The level [0..9] of the event, with higher numbers indicating higher priority.
     * @param _message The message for the event (used in event viewer, texts, and emails).
     * @param _subject The subject line for the event (used in texts and emails).
     */
    private void sendEvent( final String _type, final String _tag, final int _level, final String _message, final String _subject ) {

        long timestamp = System.currentTimeMillis();

        Message msg = mailbox.createDirectMessage( "events.post", "event.post", false );

        msg.putDotted( "tag",           _tag               );
        msg.putDotted( "timestamp",     timestamp          );
        msg.putDotted( "event.source",  "shedsolar.events" );
        msg.putDotted( "event.type",    _type              );
        msg.putDotted( "event.message", _message           );
        msg.putDotted( "event.level",   _level             );
        msg.putDotted( "event.subject", _subject           );

        mailbox.send( msg );

        LOGGER.finest( () -> "Sent event: " + _type );
    }
}
