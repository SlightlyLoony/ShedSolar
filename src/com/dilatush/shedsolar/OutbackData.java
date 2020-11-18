package com.dilatush.shedsolar;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DecimalFormat;
import java.time.Instant;
import java.util.logging.Logger;

/**
 * Container for Outback performance data extracted from API response JSON.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class OutbackData {

    private static final DecimalFormat df = new DecimalFormat( "0.##" );
    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    public Instant timestamp;
    public double  batteryVoltage;
    public double  panelVoltage;
    public double  stateOfCharge;
    public double  todayKwhIn;
    public double  todayKwhOut;


    /**
     * Creates a new instance of this class containing the data extracted from the given JSON data object.  Throws an {@link IllegalStateException} if
     * there is any problem in the JSON.
     *
     * @param _outbackData the JSON data object containing a response from the Outback API.
     */
    public OutbackData( final JSONObject _outbackData ) {

        try {
            JSONObject devstatus = _outbackData.getJSONObject( "devstatus" );
            timestamp = Instant.ofEpochMilli( 1000 * devstatus.getLong( "Sys_Time" ) );
            JSONArray ports = devstatus.getJSONArray( "ports" );
            for( int i = 0; i < ports.length(); i++ ) {
                JSONObject port = ports.getJSONObject( i );
                int portNum = port.getInt( "Port" );
                switch( portNum ) {
                    case 9:
                        panelVoltage = port.getDouble( "In_V" );
                        break;
                    case 10:
                        batteryVoltage = port.getDouble( "Batt_V" );
                        stateOfCharge = port.getDouble( "SOC" );
                        todayKwhIn = port.getDouble( "In_kWh_today" );
                        todayKwhOut = port.getDouble( "Out_kWh_today" );
                        break;
                }
            }
        }
        catch( JSONException _e ) {
            throw new IllegalStateException( "Problem in Outback data response: " + _e.getMessage(), _e );
        }
    }


    @SuppressWarnings("StringBufferReplaceableByString")
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append( "Outback data: " );
        sb.append( "at " );
        sb.append( timestamp.toString() );
        sb.append( ", SOC " );
        sb.append( df.format( stateOfCharge ) );
        sb.append( ", panel voltage " );
        sb.append( panelVoltage );
        sb.append( ", battery voltage " );
        sb.append( batteryVoltage );
        sb.append( ", today KWH in " );
        sb.append( todayKwhIn );
        sb.append( ", out " );
        sb.append( todayKwhOut );
        return sb.toString();
    }


    public Instant getTimestamp() {
        return timestamp;
    }


    public double getBatteryVoltage() {
        return batteryVoltage;
    }


    public double getPanelVoltage() {
        return panelVoltage;
    }


    public double getStateOfCharge() {
        return stateOfCharge;
    }


    public double getTodayKwhIn() {
        return todayKwhIn;
    }


    public double getTodayKwhOut() {
        return todayKwhOut;
    }
}
