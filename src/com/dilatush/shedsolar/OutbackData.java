package com.dilatush.shedsolar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.util.logging.Logger;

/**
 * Container for Outback performance data extracted from API response JSON.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class OutbackData {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    public final Instant timestamp;
    public final double  batteryVoltage;
    public final double  panelVoltage;
    public final double  stateOfCharge;
    public final double  panelCurrent;
    public final double  chargeCurrent;
    public final double  todayKwhIn;
    public final double  todayKwhOut;


    /**
     * Creates a new instance of this class containing the data extracted from the given JSON data object.  Throws an {@link IllegalStateException} if
     * there is any problem in the JSON.
     *
     * @param _outbackData the JSON data object containing a response from the Outback API.
     */
    public OutbackData( final JSONObject _outbackData ) throws JSONException {

        // some temporary variables to fill in, in the loop below...
        // we do this so that the compiler won't complain about final variables being assigned in the loop..
        double tPanelVoltage   = 0;
        double tBatteryVoltage = 0;
        double tStateOfCharge  = 0;
        double tTodayKwhIn     = 0;
        double tTodayKwhOut    = 0;
        double tPanelCurrent   = 0;
        double tChargeCurrent  = 0;

        JSONObject devstatus = _outbackData.getJSONObject( "devstatus" );
        timestamp = Instant.ofEpochMilli( 1000 * devstatus.getLong( "Sys_Time" ) );
        JSONArray ports = devstatus.getJSONArray( "ports" );
        for( int i = 0; i < ports.length(); i++ ) {
            JSONObject port = ports.getJSONObject( i );
            int portNum = port.getInt( "Port" );
            switch( portNum ) {
                case 9:
                    tPanelVoltage   = port.getDouble( "In_V" );
                    tPanelCurrent   = port.getDouble( "In_I" );
                    tChargeCurrent  = port.getDouble( "Out_I" );
                    break;
                case 10:
                    tBatteryVoltage = port.getDouble( "Batt_V" );
                    tStateOfCharge  = port.getDouble( "SOC" );
                    tTodayKwhIn     = port.getDouble( "In_kWh_today" );
                    tTodayKwhOut    = port.getDouble( "Out_kWh_today" );
                    break;
            }
        }

        panelVoltage   = tPanelVoltage;
        batteryVoltage = tBatteryVoltage;
        stateOfCharge  = tStateOfCharge;
        todayKwhIn     = tTodayKwhIn;
        todayKwhOut    = tTodayKwhOut;
        chargeCurrent  = tChargeCurrent;
        panelCurrent   = tPanelCurrent;

        LOGGER.finest( toString() );
    }


    public String toString() {
        return String.format( "Outback data: at %1$s, SOC %2$.0f%%, panels %3$.1fV, %4$.1fA, batteries %5$.1fV, %6$.1fA (charging), " +
                              "today KWH %7$.1f in, %8$.1f out",
                timestamp.toString(), stateOfCharge, panelVoltage, panelCurrent, batteryVoltage, chargeCurrent, todayKwhIn, todayKwhOut );
    }
}
