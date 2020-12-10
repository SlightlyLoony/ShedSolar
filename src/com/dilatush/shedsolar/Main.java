package com.dilatush.shedsolar;

import com.dilatush.util.Config;
import com.dilatush.util.syncevents.SynchronousEvents;

import java.io.File;
import java.util.logging.Logger;

import static com.dilatush.util.General.isNotNull;

/**
 * @author Tom Dilatush  tom@dilatush.com
 */
public class Main {

    private final String[]      args;
    private final Logger        LOGGER;

    private Main( final String[] _args ) {

        args = _args;

        // set the configuration file location (must do before any logging actions occur)...
        System.getProperties().setProperty( "java.util.logging.config.file", "logging.properties" );
        LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getSimpleName() );

        // set the maximum number of queued events to 1000...
        System.getProperties().setProperty( "com.dilatush.util.syncevents.max_capacity", "1000" );
    }


    private void run() {

        LOGGER.info( "ShedSolar is starting..." );

        // get our config...
        String configPath = "configuration.json";
        if( isNotNull( (Object) args ) && (args.length > 0) ) configPath = args[0];
        if( !new File( configPath ).exists() ) {
            System.out.println( "ShedSolar configuration file " + configPath + " does not exist!" );
            return;
        }
        Config config = Config.fromJSONFile( configPath );

        // start our events system...
        SynchronousEvents.getInstance();

        // are we running in assembly test mode or for reals?
        if( config.optBoolean( "assemblyTest", false ) ) {
            LOGGER.info( "Running in assembly test mode" );
            AssemblyTest at = new AssemblyTest();
            at.run();
        }

        // this runs if for reals...
        else {
            LOGGER.info( "Running in production mode..." );
            App.setInstance( config );
            App.instance.run();
            // leaving this method doesn't shut down the process because the app starts at least one non-daemon thread...
        }

    }


    public static void main( final String[] _args ) {
        new Main( _args ).run();
    }
}
