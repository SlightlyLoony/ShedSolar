package com.dilatush.shedsolar;

import com.dilatush.util.AConfig;
import com.dilatush.util.AConfig.InitResult;
import com.dilatush.util.syncevents.SynchronousEvents;

import java.util.logging.Logger;

/**
 * @author Tom Dilatush  tom@dilatush.com
 */
public class Main {

    private final Logger        LOGGER;

    private Main( final String[] _args ) {

        // set the configuration file location (must do before any logging actions occur)...
        System.getProperties().setProperty( "java.util.logging.config.file", "logging.properties" );
        LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getSimpleName() );

        // set the maximum number of queued events to 1000...
        System.getProperties().setProperty( "com.dilatush.util.syncevents.max_capacity", "1000" );
    }


    private void run() {

        LOGGER.info( "ShedSolar is starting..." );

        // get our configuration...
        InitResult ir = AConfig.init( Config.class, "configuration.js" );

        // if our configuration is not valid, just get out of here...
        if( !ir.valid ) {
            LOGGER.severe( "Aborting; configuration is invalid" );
            System.exit( 1 );
        }

        // get our actual configuration...
        Config config = (Config) ir.config;

        // start our events system...
        SynchronousEvents.getInstance();

        // are we running in assembly test mode or for reals?
        switch( config.mode ) {

            case "assemblyTest":
                LOGGER.info( "Running in assembly test mode" );
                AssemblyTest at = new AssemblyTest();
                at.run();
                break;

            case "normal":
                LOGGER.info( "Running in production mode..." );
                App.setInstance( config );
                App.instance.run();
                break;

            case "tempTest":
                LOGGER.info( "Running in temperature test mode..." );
                TempTest tt = new TempTest( config );
                tt.run();
                break;

            default:
                break;
        }
        // leaving this method doesn't shut down the process because the app starts at least one non-daemon thread...
    }


    public static void main( final String[] _args ) {
        new Main( _args ).run();
    }
}
