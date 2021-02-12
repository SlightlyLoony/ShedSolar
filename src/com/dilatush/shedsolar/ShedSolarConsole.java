package com.dilatush.shedsolar;

import com.dilatush.util.Conversions;
import com.dilatush.util.console.CommandProcessor;
import com.dilatush.util.console.CommandProcessorConsoleProvider;
import com.dilatush.util.info.Info;

import java.text.DecimalFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Implements the console for ShedSolar.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
@SuppressWarnings( "unused" )
public class ShedSolarConsole extends CommandProcessorConsoleProvider {

    private final ShedSolar shedSolar = ShedSolar.instance;

    private final DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern( "LLL dd, uuuu" );
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern( "hh:mm:ss.SSS" );
    private final DecimalFormat tempFormatter = new DecimalFormat( "#0.00" );

    public ShedSolarConsole() {
        super( "Console for ShedSolar" );
    }


    @Override
    public void init() {
        writeLine( "Welcome to the ShedSolar console..." );
        addCommandProcessor( new StatusCommandProcessor() );
        finish();
    }

    private String getDate( final Instant _instant ) {
        ZonedDateTime zonedDateTime = ZonedDateTime.ofInstant( _instant, ZoneId.systemDefault() );
        return zonedDateTime.format( dateFormatter );
    }

    private String getTime( final Instant _instant ) {
        ZonedDateTime zonedDateTime = ZonedDateTime.ofInstant( _instant, ZoneId.systemDefault() );
        return zonedDateTime.format( timeFormatter );
    }


    private String getTemp( final Info<Float> _temp ) {
        if( _temp.isAvailable() ) {
            return tempFormatter.format( Conversions.fromCtoF( _temp.info ) ) + "°F (" + getTime( _temp.timestamp ) + ")";
        }
        else
            return "?";
    }


    private class StatusCommandProcessor extends CommandProcessor {

        protected StatusCommandProcessor() {
            super( "status", "show current status", "Show current status of ShedSolar: temperatures, heater status, etc." );
        }


        @Override
        protected void onCommandLine( final String _s, final List<String> _list ) {

            Instant now = Instant.now( Clock.systemUTC() );
            writeLine( "On " + getDate( now ) + " " + getTime( now ) );
            writeLine( "Battery Temperature: " + getTemp( shedSolar.batteryTemperature.get() ) );
            writeLine( "Heater Temperature:  " + getTemp( shedSolar.heaterTemperature.get()  ) );
            writeLine( "Ambient Temperature: " + getTemp( shedSolar.ambientTemperature.get() ) );
        }
    }
}
