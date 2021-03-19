package com.dilatush.shedsolar;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.server.session.DefaultSessionIdManager;
import org.eclipse.jetty.server.session.SessionHandler;

import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

/**
 * Provides a very simple, one-page web site that provides current information about the ShedSolar system.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class WebServer {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private final static int PORT = 8217;


    /**
     * Create a new instance of this class.
     */
    public WebServer() {
        getServer();
    }

    /**
     * Initialize the Jetty web server for our private instance.
     *
     */
    private void getServer() {

        // start the Jetty server...
        Server server = new Server( PORT );

        ResourceHandler rh1 = new ResourceHandler();
        rh1.setDirectoriesListed( false );
        rh1.setWelcomeFiles( new String[] { "index.html" } );
        rh1.setResourceBase("./web");

        server.setSessionIdManager( new DefaultSessionIdManager( server ) );

        Handler sessionHandler = new SessionHandler();

        HandlerList handlers = new HandlerList();
        handlers.setHandlers( new Handler[] { sessionHandler, rh1, new WebPageHandler(), new DefaultHandler() });
        server.setHandler(handlers);

        try {
            server.start();
        }
        catch( Exception _e ) {

            // this is catastrophic - just log and leave with a null...
            LOGGER.log( SEVERE, "Problem prevents web server startup", _e );
        }
    }

}
