package com.dilatush.shedsolar.events;

import com.dilatush.util.syncevents.SynchronousEvent;

/**
 * An event reporting the results of measuring the heater output temperature.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class HeaterTemperatureEvent implements SynchronousEvent {

    public final float   degreesC;
    public final boolean goodMeasurement;
    public final boolean unstable;
    public final boolean ioerror;
    public final boolean thermocoupleOpen;
    public final boolean thermocoupleShortedToGround;
    public final boolean thermocoupleShortedToVCC;


    public HeaterTemperatureEvent( final float _degreesC, final boolean _goodMeasurement, final boolean _unstable,
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
            return String.format( "Heater temperature %1$.2f degrees Celcius", degreesC );
        if( unstable )
            return "Heater temperature unstable";
        if( ioerror )
            return "Heater temperature I/O error";
        if( thermocoupleOpen )
            return "Heater thermocouple open";
        if( thermocoupleShortedToGround )
            return "Heater thermocouple shorted to ground";
        return "Heater thermocouple shorted to VCC";
    }
}