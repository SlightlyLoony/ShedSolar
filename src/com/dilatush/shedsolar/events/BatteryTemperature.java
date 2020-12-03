package com.dilatush.shedsolar.events;

import com.dilatush.util.syncevents.SynchronousEvent;

/**
 * An event reporting the results of measuring the battery temperature.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class BatteryTemperature implements SynchronousEvent {

    public final float   degreesC;
    public final boolean goodMeasurement;
    public final boolean unstable;
    public final boolean ioerror;
    public final boolean thermocoupleOpen;
    public final boolean thermocoupleShortedToGround;
    public final boolean thermocoupleShortedToVCC;


    public BatteryTemperature( final float _degreesC, final boolean _goodMeasurement, final boolean _unstable,
                               final boolean _ioerror, final boolean _thermocoupleOpen,
                               final boolean _thermocoupleShortedToGround, final boolean _thermocoupleShortedToVCC ) {
        degreesC = _degreesC;
        goodMeasurement = _goodMeasurement;
        unstable = _unstable;
        ioerror = _ioerror;
        thermocoupleOpen = _thermocoupleOpen;
        thermocoupleShortedToGround = _thermocoupleShortedToGround;
        thermocoupleShortedToVCC = _thermocoupleShortedToVCC;
    }


    public String toString() {
        if( goodMeasurement )
            return String.format( "Battery temperature %1$.2f degrees Celcius", degreesC );
        if( unstable )
            return "Battery temperature unstable";
        if( ioerror )
            return "Battery temperature I/O error";
        if( thermocoupleOpen )
            return "Battery thermocouple open";
        if( thermocoupleShortedToGround )
            return "Battery thermocouple shorted to ground";
        return "Battery thermocouple shorted to VCC";
    }
}
