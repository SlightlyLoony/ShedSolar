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
import static com.dilatush.shedsolar.LightDetector.Mode;
import static com.dilatush.shedsolar.LightDetector.Mode.LIGHT;

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
        InfoSource<Float>       bat        = ss.batteryTemperature.getInfoSource();
        InfoSource<Float>       htr        = ss.heaterTemperature.getInfoSource();
        InfoSource<Float>       amb        = ss.ambientTemperature.getInfoSource();
        InfoSource<Float>       out        = ss.outsideTemperature.getInfoSource();
        InfoSource<Float>       irradiance = ss.solarIrradiance.getInfoSource();
        InfoSource<OutbackData> outback    = ss.outback.getInfoSource();
        InfoSource<Mode>        light      = ss.light.getInfoSource();

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
        fillInfoInt(      page, "##solar_irr##",        irradiance                       );
        fillLightMode(    page, "##light##",            light                            );

        if( outback.isInfoAvailable() ) {
            fillInt(    page, "##soc##",         (int) Math.round( outback.getInfo().stateOfCharge                                                ) );
            fillInt(    page, "##bc_pow##",      (int) Math.round( outback.getInfo().batteryChargePower                                           ) );
            fillInt(    page, "##bd_pow##",      (int) Math.round( outback.getInfo().batteryDischargePower                                        ) );
            fillInt(    page, "##bn_pow##",      (int) Math.round( outback.getInfo().batteryChargePower - outback.getInfo().batteryDischargePower ) );
            fillInt(    page, "##inv_pow##",     (int) Math.round( outback.getInfo().inverterPower                                                ) );
            fillFloat1( page, "##bat_volts##",   outback.getInfo().batteryVoltage                                                                   );
            fillFloat1( page, "##panel_volts##", outback.getInfo().panelVoltage                                                                     );
        } else {
            fillNotAvailable( page, "##soc##"         );
            fillNotAvailable( page, "##bc_pow##"      );
            fillNotAvailable( page, "##bd_pow##"      );
            fillNotAvailable( page, "##bn_pow##"      );
            fillNotAvailable( page, "##inv_pow##"     );
            fillNotAvailable( page, "##bat_volts##"   );
            fillNotAvailable( page, "##panel_volts##" );
        }

        // now send our page...
        _httpServletResponse.setContentType( "text/html" );
        _httpServletResponse.setStatus( HttpServletResponse.SC_OK );
        _httpServletResponse.getWriter().println( page.get() );
        _request.setHandled( true );
    }


    private void fillLightMode( final AtomicReference<String> _page, final String _pattern, final InfoSource<Mode> _light ) {
        if( _light.isInfoAvailable() ) {
            _page.set( _page.get().replace( _pattern, _light.getInfo() == LIGHT
                    ? "<span style='margin: 0; color: #87ceeb;'>LIGHT</span>"
                    : "<span style='margin: 0; color: #00008b;'>DARK</span>" ) );
        }
        else
            _page.set( _page.get().replace( _pattern, "(not available)" ) );
    }


    private static final DecimalFormat float1Formatter = new DecimalFormat( "####0.0" );

    private void fillFloat1( final AtomicReference<String> _page, final String _pattern, final double _data ) {
        _page.set( _page.get().replace( _pattern, float1Formatter.format( _data ) ) );
    }


    private void fillInfoInt( final AtomicReference<String> _page, final String _pattern, final InfoSource<Float> _infoSource ) {
        String target = "(not available)";
        if( _infoSource.isInfoAvailable() ) {
            target = Integer.toString( Math.round( _infoSource.getInfo() ) );
        }
        _page.set( _page.get().replace( _pattern, target ) );
    }


    private void fillNotAvailable( final AtomicReference<String> _page, final String _pattern ) {
        _page.set( _page.get().replace( _pattern, "(not available)" ) );
    }


    private void fillInt( final AtomicReference<String> _page, final String _pattern, final int _data ) {
        _page.set( _page.get().replace( _pattern, Integer.toString( _data ) ) );
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

        String target = "(not available)";
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
                "<link rel='apple-touch-icon' sizes='180x180' href='/apple-touch-icon.png'>" +
                "<link rel='icon' type='image/png' sizes='32x32' href='/favicon-32x32.png'>" +
                "<link rel='icon' type='image/png' sizes='16x16' href='/favicon-16x16.png'>" +
                "<link rel='manifest' href='/site.webmanifest'>" +
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
                            "<td><span>Current heater state</span></td>" +
                        "</tr>" +
                        "<tr>" +
                            "<td><span>Last Heater Cycle</span></td>" +
                            "<td><span>##heater_cycle##</span></td>" +
                            "<td><span>There were ##heater_cycles## heater cycles in the past week</span></td>" +
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
                        "<tr>" +
                            "<td><span>State of Charge (SOC)</span></td>" +
                            "<td><span>##soc##%</span></td>" +
                            "<td><span>As reported by the Outback FNDC</span></td>" +
                        "</tr>" +
                        "<tr>" +
                            "<td><span>Battery Charge Power</span></td>" +
                            "<td><span>##bc_pow## watts</span></td>" +
                            "<td><span>From photovoltaic panels</span></td>" +
                        "</tr>" +
                        "<tr>" +
                            "<td><span>Battery Discharge Power</span></td>" +
                            "<td><span>##bd_pow## watts</span></td>" +
                            "<td><span>To inverter and Outback system</span></td>" +
                        "</tr>" +
                        "<tr>" +
                            "<td><span>Battery Net Power</span></td>" +
                            "<td><span>##bn_pow## watts</span></td>" +
                            "<td><span>Net power into (+) or out of (-) battery</span></td>" +
                        "</tr>" +
                        "<tr>" +
                            "<td><span>Inverter Output Power</span></td>" +
                            "<td><span>##inv_pow## watts</span></td>" +
                            "<td><span>To load center</span></td>" +
                        "</tr>" +
                        "<tr>" +
                            "<td><span>Battery Voltage</span></td>" +
                            "<td><span>##bat_volts## VDC</span></td>" +
                            "<td><span>Measured by the Outback FNDC</span></td>" +
                        "</tr>" +
                        "<tr>" +
                            "<td><span>Solar Panel Voltage</span></td>" +
                            "<td><span>##panel_volts## VDC</span></td>" +
                            "<td><span>Measured by the Outback charge controller</span></td>" +
                        "</tr>" +
                        "<tr>" +
                            "<td><span>Solar Irradiance</span></td>" +
                            "<td><span>##solar_irr## watts/square meter</span></td>" +
                            "<td><span>Measured by the weather station</span></td>" +
                        "</tr>" +
                        "<tr>" +
                            "<td><span>Light Mode</span></td>" +
                            "<td><span>##light##</span></td>" +
                            "<td><span></span></td>" +
                        "</tr>" +
                    "</tbody>" +
                "</table>" +
            "</body>" +
        "</html>";
}
