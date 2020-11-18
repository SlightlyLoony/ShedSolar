package com.dilatush.shedsolar;

import com.dilatush.mop.PostOffice;
import com.dilatush.util.Config;

import java.io.File;
import java.util.Timer;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static com.dilatush.util.General.isNotNull;

/**
 * @author Tom Dilatush  tom@dilatush.com
 */
public class Main {

    private static Main APP;

    public  PostOffice     po;
    public  ShedSolarActor actor;
    public  Timer          timer;

    public AtomicReference<Double> solarIrradiance;               // the most recent solar irradiance value, set from ShedSolarActor...
    public OutbackData             outbackData;                   // the most recent data from the Outback solar system, set from Outbacker...

    private Config      config;
    private String      configPath;
    private String[]    args;
    private Logger      LOGGER;
    private Outbacker   outbacker;
    private boolean     devMode;
    TempReader          batteryTempReader;
    TempReader          ambientTempReader;
    HeaterActuator      heaterActuator;

    private Main( final String[] _args ) {

        args = _args;

        // set the configuration file location (must do before any logging actions occur)...
        System.getProperties().setProperty( "java.util.logging.config.file", "logging.properties" );
        LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getSimpleName() );
    }


    private void run() {
        LOGGER.info( "ShedSolar is starting..." );

        // get our config...
        configPath = "configuration.json";
        if( isNotNull( (Object) args ) && (args.length > 0) ) configPath = args[0];
        if( !new File( configPath ).exists() ) {
            System.out.println( "ShedSolar configuration file " + configPath + " does not exist!" );
            return;
        }
        config = Config.fromJSONFile( configPath );

        // set up a timer for everyone to use...
        timer = new Timer( "Timer", false );

        // get our app set up...
        solarIrradiance = new AtomicReference<Double>();
        outbacker = new Outbacker( config.getString( "outback" ) );

        // start up our post office and our actors...
        po = new PostOffice( config );
        actor = new ShedSolarActor( po );

        // set up our sensors and actuators...
        // if in devmode, we use fake sensors and actuators to test the logic
        // otherwise, we use the real deal
        batteryTempReader = null;
        ambientTempReader = null;
        heaterActuator = null;
        devMode = config.optBoolean( "devmode", false );
        if( devMode ) {

        }
        else {

        }

        // leaving this method doesn't shut down the process because the Timer started above is NOT on a daemon thread...
    }


    public static void main( final String[] _args ) {
        APP = new Main( _args );
        APP.run();
    }


    public static Main APP() {
        return APP;
    }
}
