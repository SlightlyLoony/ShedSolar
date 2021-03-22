package com.dilatush.shedsolar;

import com.dilatush.util.info.InfoSource;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.DecimalFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.dilatush.shedsolar.Events.HEATER_OFF;
import static com.dilatush.shedsolar.Events.HEATER_ON;

/**
 * Provides a simple handler for the one web page provided by this server.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class WebPageHandler extends AbstractHandler implements Handler {

    private final ShedSolar ss;   // a shortcut alias...

    private Instant lastHeaterOn;
    private Instant lastHeaterOff;
    private boolean heaterOn;
    private final List<Instant> heaterOns;  // heater on times for past week...

    public WebPageHandler() {

        // initialize some things...
        ss = ShedSolar.instance;
        heaterOns = new ArrayList<>();

        // subscribe to things we need to know about...
        ss.haps.subscribe( HEATER_ON,       this::onHeaterOn      );
        ss.haps.subscribe( HEATER_OFF,      this::onHeaterOff     );

        // schedule things we need to do periodically...
        ss.scheduledExecutor.scheduleAtFixedRate( this::cleanup, Duration.ofMinutes( 1 ), Duration.ofMinutes( 1 ) );
    }


    private void cleanup() {

        // the oldest heater on time we want to keep...
        Instant oldest = Instant.now( Clock.systemUTC() ).minus( Duration.ofDays( 7 ) );

        // delete any heater on times that are too old...
        while( (heaterOns.size() > 0) && (heaterOns.get( 0 ).isBefore( oldest )) ) {
            heaterOns.remove( 0 );
        }
    }


    private void onHeaterOn() {
        lastHeaterOn = Instant.now( Clock.systemUTC() );
        heaterOns.add( lastHeaterOn );
        heaterOn = true;
    }


    private void onHeaterOff() {
        lastHeaterOff = Instant.now( Clock.systemUTC() );
        heaterOn = false;
    }


    @Override
    public void handle( final String _s, final Request _request, final HttpServletRequest _httpServletRequest,
                        final HttpServletResponse _httpServletResponse ) throws IOException {

        // if this isn't a request for our one-and-only page, then just leave...
        if( !"/index.html".equalsIgnoreCase(  _request.getOriginalURI() ) && !"/".equals( _request.getOriginalURI() ) )
            return;

        // get our data...
        InfoSource<Float> bat = ss.batteryTemperature.getInfoSource();
        InfoSource<Float> htr = ss.heaterTemperature.getInfoSource();
        InfoSource<Float> amb = ss.ambientTemperature.getInfoSource();
        InfoSource<Float> out = ss.outsideTemperature.getInfoSource();

        // fill in the data on our page...
        AtomicReference<String> page = new AtomicReference<>( PAGE );
        fillDateTime(     page, "##now##",              Instant.now( Clock.systemUTC() ) );
        fillHeaterState(  page, "##heater_state##",     heaterOn                         );
        fillHeaterCycle(  page, "##heater_cycle##",     lastHeaterOn, lastHeaterOff      );
        fillHeaterCycles( page, "##heater_cycles##",    heaterOns.size()                 );
        fillTemperature(  page, "##bat_temp##",         bat                              );
        fillAge(          page, "##bat_temp_time##",    bat                              );
        fillTemperature(  page, "##heater_temp##",      htr                              );
        fillAge(          page, "##heater_temp_time##", htr                              );
        fillTemperature(  page, "##amb_temp##",         amb                              );
        fillAge(          page, "##amb_temp_time##",    amb                              );
        fillTemperature(  page, "##out_temp##",         out                              );
        fillAge(          page, "##out_temp_time##",    out                              );

        // TODO: SOC
        // TODO: panel output power (really, CC output power/battery input power)
        // TODO: inverter output power
        // TODO: battery output power
        // TODO: battery voltage
        // TODO: irradiance
        // TODO: light detector mode
        // TODO: heater controller (normal, battery only, etc.)
        // TODO: heater controller state

        // now send our page...
        _httpServletResponse.setContentType( "text/html" );
        _httpServletResponse.setStatus( HttpServletResponse.SC_OK );
        _httpServletResponse.getWriter().println( page.get() );
        _request.setHandled( true );
    }


    private void fillHeaterCycle( final AtomicReference<String> _page, final String _pattern,
                                  final Instant _start, final Instant _stop ) {

        // if we have no start time, then we have a default answer...
        String result = "(not yet)";
        if( _start != null ) {

            // if the heater is currently on, ending time is right now...
            Instant stop = heaterOn ? Instant.now( Clock.systemUTC() ) : _stop;

            // calculate how long the heater was on (or has been on)...
            long onTime = Duration.between( _start, stop ).getSeconds();

            // make our pretty answer...
            ZonedDateTime start = ZonedDateTime.ofInstant( _start, ZoneId.of( "America/Denver" ) );
            result = nowFormatter.format( start ) + " for " + onTime + " seconds";
        }

        _page.set( _page.get().replace( _pattern, result ) );
    }


    private void fillHeaterCycles( final AtomicReference<String> _page, final String _pattern, final int _cycles ) {
        _page.set( _page.get().replace( _pattern, "" + _cycles ) );
    }


    private void fillHeaterState( final AtomicReference<String> _page, final String _pattern, final boolean _state ) {
        _page.set( _page.get().replace( _pattern, _state
                ? "<span style='margin: 0; color: #ff6600;'>ON</span>"
                : "<span style='margin: 0; color: #0000ff;'>OFF</span>" ) );
    }


    private void fillAge(  final AtomicReference<String> _page, final String _pattern, final InfoSource<?> _temp  ) {
        long age = Duration.between( _temp.getInfoTimestamp(), Instant.now( Clock.systemUTC() ) ).getSeconds();
        _page.set( _page.get().replace( _pattern, age + " seconds ago" ) );
    }


    private final static DecimalFormat temperatureFormatter = new DecimalFormat( "##0.00" );

    private void fillTemperature( final AtomicReference<String> _page, final String _pattern, final InfoSource<Float> _temp ) {

        String target = "unavailable";
        if( _temp.isInfoAvailable() ) {
            String dc = temperatureFormatter.format( _temp.getInfo() );
            String df = temperatureFormatter.format( _temp.getInfo() * 9.0f / 5.0f + 32.0f );
            target = dc + "°C (" + df + "°F)";
        }
        _page.set( _page.get().replace( _pattern, target ) );
    }


    DateTimeFormatter nowFormatter    = DateTimeFormatter.ofPattern( "MMM dd, yyyy 'at' HH:mm:ss" );

    private void fillDateTime( final AtomicReference<String> _page, final String _pattern, final Instant _datetime ) {
        ZonedDateTime local = ZonedDateTime.ofInstant( _datetime, ZoneId.of( "America/Denver" ) );
        String localDT = nowFormatter.format( local );
        _page.set( _page.get().replace( _pattern, localDT ) );
    }


    private static final String PAGE =
        "<!DOCTYPE html>" +
        "<html lang='en'>" +
            "<head>" +
                "<style>" +
                    "body {" +
                    "  background-color: ivory;" +
                    "}" +
                    "thead tr th {" +
                    "  text-align: left;" +
                    "}" +
                    "tbody tr td {" +
                    "  text-align: left;" +
                    "}" +
                    "thead tr th span {" +
                    "  margin-left: 10px;" +
                    "  margin-right: 10px;" +
                    "}" +
                    "tbody tr td span {" +
                    "  margin-left: 10px;" +
                    "  margin-right: 10px;" +
                    "}" +
                "</style>" +
                "<meta charset='UTF-8'>" +
                "<meta name='description' content='ShedSolar Status'>" +
                "<meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "<meta http-equiv='refresh' content='60'>" +
            "</head>" +
            "<body>" +
                "<h1>Shed Solar Status (as of ##now##)</h1>" +
                "<table>" +
                    "<thead>" +
                        "<tr>" +
                            "<th><span>Item</span></th>" +
                            "<th><span>Status</span></th>" +
                            "<th><span>Info</span></th>" +
                        "</tr>" +
                    "</thead>" +
                    "<tbody>" +
                        "<tr>" +
                            "<td><span>Heater State</span></td>" +
                            "<td><span>##heater_state##</span></td>" +
                            "<td><span>Current heater state...</span></td>" +
                        "</tr>" +
                        "<tr>" +
                            "<td><span>Last Heater Cycle</span></td>" +
                            "<td><span>##heater_cycle##</span></td>" +
                            "<td><span>There were ##heater_cycles## heater cycles in the past week.</span></td>" +
                        "</tr>" +
                        "<tr>" +
                            "<td><span>Battery Temperature</span></td>" +
                            "<td><span>##bat_temp##</span></td>" +
                            "<td><span>##bat_temp_time##</span></td>" +
                        "</tr>" +
                        "<tr>" +
                            "<td><span>Heater Output Temperature</span></td>" +
                            "<td><span>##heater_temp##</span></td>" +
                            "<td><span>##heater_temp_time##</span></td>" +
                        "</tr>" +
                        "<tr>" +
                            "<td><span>Ambient (Shed) Temperature</span></td>" +
                            "<td><span>##amb_temp##</span></td>" +
                            "<td><span>##amb_temp_time##</span></td>" +
                        "</tr>" +
                        "<tr>" +
                            "<td><span>Outside Temperature</span></td>" +
                            "<td><span>##out_temp##</span></td>" +
                            "<td><span>##out_temp_time##</span></td>" +
                        "</tr>" +
                    "</tbody>" +
                "</table>" +
            "</body>" +
        "</html>";
}
