package com.dilatush.shedsolar;

import com.dilatush.util.info.InfoSource;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.DecimalFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides a simple handler for the one web page provided by this server.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class WebPageHandler extends AbstractHandler implements Handler {


    @Override
    public void handle( final String _s, final Request _request, final HttpServletRequest _httpServletRequest,
                        final HttpServletResponse _httpServletResponse ) throws IOException, ServletException {

        // if this isn't a request for our one-and-only page, then just leave...
        if( !"/index.html".equalsIgnoreCase(  _request.getOriginalURI() ) && !"/".equals( _request.getOriginalURI() ) )
            return;

        // test data...
        InfoSource<Float> bat = new InfoSource<>( 16.498927f );
        InfoSource<Float> htr = new InfoSource<>( 31.444f );
        InfoSource<Float> amb = new InfoSource<>( 17.6f );
        InfoSource<Float> out = new InfoSource<>( 15f );

        // fill in the data on our page...
        AtomicReference<String> page = new AtomicReference<>( PAGE );
        fillDateTime(    page, "##now##", Instant.now( Clock.systemUTC() ) );
        fillTemperature( page, "##bat_temp##",         bat );
        fillAge(         page, "##bat_temp_time##",    bat );
        fillTemperature( page, "##heater_temp##",      htr );
        fillAge(         page, "##heater_temp_time##", htr );
        fillTemperature( page, "##amb_temp##",         amb );
        fillAge(         page, "##amb_temp_time##",    amb );
        fillTemperature( page, "##out_temp##",         out );
        fillAge(         page, "##out_temp_time##",    out );

        // now send our page...
        _httpServletResponse.setContentType( "text/html" );
        _httpServletResponse.setStatus( HttpServletResponse.SC_OK );
        _httpServletResponse.getWriter().println( page.get() );
        _request.setHandled( true );
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
                            "<th><span>State</span></th>" +
                            "<th><span>Info</span></th>" +
                        "</tr>" +
                    "</thead>" +
                    "<tbody>" +
                        "<tr>" +
                            "<td><span>Heater</span></td>" +    // TODO: working on heater on...
                            "<td><span>##bat_temp##</span></td>" +
                            "<td><span>##bat_temp_time##</span></td>" +
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
