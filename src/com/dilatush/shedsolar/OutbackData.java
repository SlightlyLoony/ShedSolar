package com.dilatush.shedsolar;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;

/**
 * Container for Outback performance data extracted from API response JSON.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class OutbackData {

    public Instant timestamp;
    public double  batteryVoltage;
    public double  panelVoltage;
    public double  stateOfCharge;
    public double  todayKwhIn;
    public double  todayKwhOut;


    public OutbackData( final JSONObject _outbackData ) {

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
