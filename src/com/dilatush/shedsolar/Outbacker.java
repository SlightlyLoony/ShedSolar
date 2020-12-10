package com.dilatush.shedsolar;

import com.dilatush.shedsolar.events.OutbackFailure;
import com.dilatush.shedsolar.events.OutbackReading;
import com.dilatush.util.Config;
import com.dilatush.util.syncevents.SynchronousEvent;
import com.dilatush.util.test.ATestInjector;
import com.dilatush.util.test.TestInjector;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.dilatush.shedsolar.App.execute;
import static com.dilatush.shedsolar.App.schedule;
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
    private final TestException testException;


    /**
     * Creates a new instance of this class with the given configuration.
     *
     * @param _config the configuration to use when creating this instance
     */
    public Outbacker( final Config _config ) {

        // set this thing up...
        host = _config.getStringDotted( "outback.host" );
        long interval = _config.optLongDotted( "outback.interval", 60000 );
        url = getURL();

        // schedule the execution of the query...
        schedule( () -> execute( new OutbackerTask() ), 0, interval, TimeUnit.MILLISECONDS );

        // set up our tests...
        testException = new TestException();
        App.instance.orchestrator.registerTestInjector( testException, "Outbacker.readError" );
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

                // test injection...
                if( testException.inject( null ) )
                    throw new SocketTimeoutException( "Test" );

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
            }
        }
    }


    private static class TestException extends ATestInjector<Boolean> implements TestInjector<Boolean> {

        @Override
        public Boolean inject( final Boolean _o ) {
            return enabled;
        }
    }
}
