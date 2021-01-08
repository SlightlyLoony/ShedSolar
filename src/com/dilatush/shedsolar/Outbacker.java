package com.dilatush.shedsolar;

import com.dilatush.shedsolar.events.OutbackFailure;
import com.dilatush.shedsolar.events.OutbackReading;
import com.dilatush.util.AConfig;
import com.dilatush.util.syncevents.SynchronousEvent;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.dilatush.shedsolar.App.execute;
import static com.dilatush.shedsolar.App.schedule;
import static com.dilatush.util.Internet.isValidHost;
import static com.dilatush.util.syncevents.SynchronousEvents.publishEvent;

/**
 * Interrogates the Outback system at a configured IP address or host name, at the configured interval.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class Outbacker {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private final String host;
    private final URL url;


    /**
     * Creates a new instance of this class with the given configuration.
     *
     * @param _config the configuration to use when creating this instance
     */
    public Outbacker( final Config _config ) {

        // set this thing up...
        host          = _config.host;
        long interval = _config.interval;
        url           = getURL();

        // schedule the execution of the query...
        schedule( () -> execute( new OutbackerTask() ), 0, interval, TimeUnit.MILLISECONDS );
    }


    public static class Config extends AConfig {

        /**
         * The host or dotted-form IP address for the Outback Mate3S supervisor on the charger/inverter.
         */
        public String host;

        /**
         * The interval between interrogations of the Outback Mate3S, in milliseconds.  This value must be greater than 30,000;
         * it defaults to 60,000.
         */
        public long   interval = 60000;


        /**
         * Verify the fields of this configuration.
         */
        @Override
        public void verify( final List<String> _messages ) {
            validate( () -> isValidHost( host ), _messages,
                    "Outback Interrogator host not found: " + host );
            validate( () -> interval > 30000, _messages,
                    "Outback Interrogator interval is too short: " + interval );
        }
    }


    private URL getURL() {
        try {
            return new URL( "http://" + host + "/Dev_status.cgi?&Port=0" );
        }
        catch( MalformedURLException _e ) {
            LOGGER.log( Level.SEVERE, "Malformed URL; exiting", _e );
            System.exit( 1 );
            return null;  // can't get here, but makes compiler happy...
        }
    }


    /**
     * This class does all the real work.  It executes in the blocking I/O thread provided by the app's executor service.
     */
    private class OutbackerTask  implements Runnable {

        @Override
        public void run() {

            // get the JSON response from the Outback system...
            JSONObject jsonResponse = null;
            try {
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setDoOutput( true );
                conn.setRequestMethod( "GET" );
                conn.setRequestProperty( "Content-Type", "application/json" );

                // if we have to wait more than a second, something's wrong...
                conn.setConnectTimeout( 1000 );
                conn.setReadTimeout( 1000 );

                if( conn.getResponseCode() != 200 ) {
                    throw new RuntimeException( "Failed : HTTP error code : " + conn.getResponseCode() );
                }

                BufferedReader br = new BufferedReader( new InputStreamReader(
                        (conn.getInputStream()) ) );

                String jrs;
                StringBuilder jsonResponseString = new StringBuilder();
                do {
                    jrs = br.readLine();
                    if( jrs != null )
                        jsonResponseString.append( jrs );
                } while( jrs != null );

                jsonResponse = new JSONObject( jsonResponseString.toString() );

                conn.disconnect();

            } catch( SocketTimeoutException _e ) {
                LOGGER.log( Level.WARNING, "Socket timed out when reading Outback data", _e );
                jsonResponse = null;
            } catch( IOException _e ) {
                LOGGER.log( Level.SEVERE, "Error when reading Outback data", _e );
                jsonResponse = null;
            } catch( JSONException _e ) {
                LOGGER.log( Level.SEVERE, "Error parsing Outback data", _e );
            } catch( RuntimeException _e ) {
                LOGGER.log( Level.SEVERE, "Unhandled exception in OutbackerTask", _e );
            }

            // publish an event to tell the world what happened...
            try {
                SynchronousEvent event = (jsonResponse != null) ? new OutbackReading( new OutbackData( jsonResponse ) ) : new OutbackFailure();
                publishEvent( event );
                LOGGER.finest( event.toString() );
            }
            catch( JSONException _e ) {
                LOGGER.log( Level.WARNING, "Problem decoding Outback JSON response", _e );
                publishEvent( new OutbackFailure() );
            } catch( RuntimeException _e ) {
                LOGGER.log( Level.SEVERE, "Unhandled exception in OutbackerTask", _e );
            }
        }
    }
}
