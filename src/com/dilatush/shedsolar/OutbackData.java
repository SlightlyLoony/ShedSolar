package com.dilatush.shedsolar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/**
 * Container for Outback performance data extracted from API response JSON.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class OutbackData {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );
    private static final DateTimeFormatter timestampFormat = DateTimeFormatter.ofPattern( "LLL dd, uuuu hh:mm:ss" );

    public final Instant timestamp;
    public final double  batteryVoltage;
    public final double  panelVoltage;
    public final double  stateOfCharge;
    public final double  panelCurrent;
    public final double  inverterCurrent;
    public final double  inverterVoltage;
    public final double  todayKwhIn;
    public final double  todayKwhOut;
    public final double  panelPower;
    public final double  inverterPower;


    /**
     * Creates a new instance of this class containing the data extracted from the given JSON data object.  Throws an {@link IllegalStateException} if
     * there is any problem in the JSON.
     *
     * @param _outbackData the JSON data object containing a response from the Outback API.
     */
    public OutbackData( final JSONObject _outbackData ) throws JSONException {

        // some temporary variables to fill in, in the loop below...
        // we do this so that the compiler won't complain about final variables being assigned in the loop..
        double tPanelVoltage     = 0;
        double tBatteryVoltage   = 0;
        double tStateOfCharge    = 0;
        double tTodayKwhIn       = 0;
        double tTodayKwhOut      = 0;
        double tPanelCurrent     = 0;
        double tInverterCurrent  = 0;
        double tInverterVoltage1 = 0;
        double tInverterVoltage2 = 0;

        JSONObject devstatus = _outbackData.getJSONObject( "devstatus" );
        timestamp = Instant.ofEpochMilli( 1000 * devstatus.getLong( "Sys_Time" ) );
        JSONArray ports = devstatus.getJSONArray( "ports" );
        for( int i = 0; i < ports.length(); i++ ) {
            JSONObject port = ports.getJSONObject( i );
            int portNum = port.getInt( "Port" );
            switch( portNum ) {
                case 1:
                    tInverterVoltage1 = port.getDouble( "VAC_out_L1" );
                    tInverterVoltage2 = port.getDouble( "VAC_out_L2" );
                    break;
                case 9:
                    tPanelVoltage   = port.getDouble( "In_V" );
                    tPanelCurrent   = port.getDouble( "In_I" );
                    tInverterCurrent  = port.getDouble( "Out_I" );
                    break;
                case 10:
                    tBatteryVoltage = port.getDouble( "Batt_V" );
                    tStateOfCharge  = port.getDouble( "SOC" );
                    tTodayKwhIn     = port.getDouble( "In_kWh_today" );
                    tTodayKwhOut    = port.getDouble( "Out_kWh_today" );
                    break;
            }
        }

        panelVoltage    = tPanelVoltage;
        batteryVoltage  = tBatteryVoltage;
        stateOfCharge   = tStateOfCharge;
        todayKwhIn      = tTodayKwhIn;
        todayKwhOut     = tTodayKwhOut;
        inverterCurrent = tInverterCurrent;
        inverterVoltage = tInverterVoltage1 + tInverterVoltage2;
        panelCurrent    = tPanelCurrent;
        panelPower      = panelCurrent * panelVoltage;
        inverterPower   = inverterCurrent * inverterVoltage;

        LOGGER.finest( toString() );
    }


    public String toString() {
        ZonedDateTime localTimestamp = ZonedDateTime.ofInstant( timestamp, ZoneId.systemDefault() );
        String stamp    = timestampFormat.format( localTimestamp );
        String soc      = String.format( "SOC: %1$.0f%%", stateOfCharge );
        String panels   = String.format( "Panels: %1$.1fV, %2$.1fA, %3$.1fW", panelVoltage, panelCurrent, panelPower );
        String inverter = String.format( "Inverter: %1$.1fV, %2$.1fA, %3$.1fW", inverterVoltage, inverterCurrent, inverterPower );
        String battery  = String.format( "Battery: %1$.1fV", batteryVoltage );
        String kwh      = String.format( "Today KWh: %1$.1f in, %2$.1f out", todayKwhIn, todayKwhOut );

        return String.format( "Outback data at %1$s:\n  %2$s\n  %3$s\n  %4$s\n  %5$s\n  %6$s", stamp, soc, panels, inverter, battery, kwh );
    }
}
