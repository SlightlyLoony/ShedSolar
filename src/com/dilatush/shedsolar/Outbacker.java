package com.dilatush.shedsolar;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Interrogates the Outback system at a given IP address or host name, every 30 seconds.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class Outbacker {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private final String host;
    private final URL url;


    public Outbacker( final String _host ) {
        host = _host;
        url = getURL();
        Main.APP().timer.schedule( new OutbackerTask(), 0, 30000 );
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


    private class OutbackerTask extends TimerTask {

        /**
         * The action to be performed by this timer task.
         */
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

                String jrs = null;
                StringBuilder jsonResponseString = new StringBuilder();
                do {
                    jrs = br.readLine();
                    if( jrs != null )
                        jsonResponseString.append( jrs );
                } while( jrs != null );

                jsonResponse = new JSONObject( jsonResponseString.toString() );

                conn.disconnect();

            } catch( SocketTimeoutException _e ) {
                // TODO: handle this
            } catch( IOException e ) {
                // TODO: handle this
            } catch( JSONException ex ) {
                // TODO: handle this
            }

            // if we got a JSON response, post an object with a summary of the response...
            if( jsonResponse != null ) {
                Main.APP().outbackData = new OutbackData( jsonResponse );
                hashCode();
            }
        }
    }
}
