package com.dilatush.shedsolar;

import com.dilatush.mop.PostOffice;
import com.dilatush.util.Config;

import java.io.File;
import java.util.logging.Logger;

import static com.dilatush.util.General.isNotNull;

/**
 * @author Tom Dilatush  tom@dilatush.com
 */
public class Main {

    private Config      config;
    private String      configPath;
    private String[]    args;
    private Logger      LOGGER;
    private PostOffice po;

    private Main( final String[] _args ) {

        args = _args;

        // get our config...
        configPath = "config.json";
        if( isNotNull( (Object) _args ) && (_args.length > 0) ) configPath = _args[0];
        if( !new File( configPath ).exists() ) {
            System.out.println( "ShedSolar configuration file " + configPath + " does not exist!" );
            return;
        }
        config = Config.fromJSONFile( configPath );

        // set the configuration file location (must do before any logging actions occur)...
        System.getProperties().setProperty( "java.util.logging.config.file", "logging.properties" );
        LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getSimpleName() );

        // start up our post office and our actors...
        po = new PostOffice( config );
    }

    private void run() {
        LOGGER.info( "ShedSolar is starting..." );
    }


    public static void main( final String[] _args ) {

        Main main = new Main( _args );
        main.run();
    }
}
