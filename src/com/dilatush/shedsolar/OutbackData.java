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
    public final double  batteryChargeCurrent;
    public final double  batteryDischargeCurrent;
    public final double  batteryChargePower;
    public final double  batteryDischargePower;


    /**
     * Creates a new instance of this class containing the data extracted from the given JSON data object.  Throws an {@link IllegalStateException} if
     * there is any problem in the JSON.
     *
     * @param _outbackData the JSON data object containing a response from the Outback API.
     */
    public OutbackData( final JSONObject _outbackData ) throws JSONException {

        // some temporary variables to fill in, in the loop below...
        // we do this so that the compiler won't complain about final variables being assigned in the loop..
        double tPanelVoltage      = 0;
        double tBatteryVoltage    = 0;
        double tStateOfCharge     = 0;
        double tTodayKwhIn        = 0;
        double tTodayKwhOut       = 0;
        double tInverterCurrent1  = 0;
        double tInverterCurrent2  = 0;
        double tInverterVoltage1  = 0;
        double tInverterVoltage2  = 0;
        double tBatteryChargeI    = 0;
        double tBatteryDischargeI = 0;

        JSONObject devstatus = _outbackData.getJSONObject( "devstatus" );
        timestamp = Instant.ofEpochMilli( 1000 * devstatus.getLong( "Sys_Time" ) );
        JSONArray ports = devstatus.getJSONArray( "ports" );
        for( int i = 0; i < ports.length(); i++ ) {
            JSONObject port = ports.getJSONObject( i );
            int portNum = port.getInt( "Port" );
            switch( portNum ) {

                // GS: Radian inverter/charger...
                case 1:
                    tInverterVoltage1 = port.getDouble( "VAC_out_L1" );
                    tInverterVoltage2 = port.getDouble( "VAC_out_L2" );
                    tInverterCurrent1 = port.getDouble( "Inv_I_L1" );
                    tInverterCurrent2 = port.getDouble( "Inv_I_L2" );
                    break;

                // CC: charge controller...
                case 9:
                    tPanelVoltage   = port.getDouble( "In_V" );
                    break;

                // FNDC: FLEXnet DC, DC system monitoring...
                case 10:
                    tBatteryVoltage    = port.getDouble( "Batt_V" );
                    tStateOfCharge     = port.getDouble( "SOC" );
                    tTodayKwhIn        = port.getDouble( "In_kWh_today" );
                    tTodayKwhOut       = port.getDouble( "Out_kWh_today" );
                    tBatteryChargeI    = port.getDouble( "Shunt_C_I" );
                    tBatteryDischargeI = port.getDouble( "Shunt_A_I" );
                    break;
            }
        }

        panelVoltage            = tPanelVoltage;
        batteryVoltage          = tBatteryVoltage;
        stateOfCharge           = tStateOfCharge;
        todayKwhIn              = tTodayKwhIn;
        todayKwhOut             = tTodayKwhOut;
        inverterCurrent         = tInverterCurrent1 + tInverterCurrent2;  // this is actually giving me the total current at 120VAC; a bit weird...
        inverterVoltage         = tInverterVoltage1 + tInverterVoltage2;  // this darned well be 240VAC or thereabouts!
        panelCurrent            = batteryVoltage * tBatteryChargeI / panelVoltage;  // inferred from charge current - adding generator may screw this...
        panelPower              = panelCurrent * panelVoltage;
        batteryChargeCurrent    = tBatteryChargeI;
        batteryDischargeCurrent = -tBatteryDischargeI;   // inverted because shunt reads negative for discharge currents...
        batteryChargePower      = batteryChargeCurrent * batteryVoltage;
        batteryDischargePower   = batteryDischargeCurrent * batteryVoltage;

        // this is required because current could be MUCH different on each leg...
        inverterPower   = (tInverterCurrent1 * tInverterVoltage1) + (tInverterCurrent2 * tInverterVoltage2);

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
