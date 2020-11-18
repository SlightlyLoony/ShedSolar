package com.dilatush.shedsolar;

import com.dilatush.mop.Actor;
import com.dilatush.mop.Message;
import com.dilatush.mop.PostOffice;
import org.json.JSONException;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Provides an MOP actor that provides the mailbox "shedsolar.main" and listens for once-per-minute weather reports.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class ShedSolarActor extends Actor {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private static final String mailboxName = "main";

    /**
     * Creates a new instance of this class, using the given post office and creating a mailbox with the given name.
     *
     * @param _po the post office to create an actor for
     */
    public ShedSolarActor( final PostOffice _po ) {
        super( _po, mailboxName );

        // register our handlers...
        registerFQPublishMessageHandler( this::handleWeatherReport, "weather.weather", "minute", "report" );

        // subscribe to the messages we want to monitor...
        mailbox.subscribe( "weather.weather", "minute.report" );
    }


    private void handleWeatherReport( final Message _message ) {

        double sia = 0;
        try {
            sia = _message.getDouble( "solarIrradianceAvg" );
            LOGGER.finer( "Received Weather minute report message, solar irradiance is " + sia );
        }
        catch( JSONException _e ) {
            LOGGER.log( Level.SEVERE, "Problem extracting solar irradiance from weather message: " + _e.getMessage(), _e );
        }

        // set the current solar power from our weather report message...
        Main.APP().solarIrradiance.set( sia );
    }
}
