package com.dilatush.shedsolar.events;

import com.dilatush.util.syncevents.SynchronousEvent;

/**
 * An event reporting the results of measuring the battery temperature.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class AmbientTemperature implements SynchronousEvent {

    public final float   degreesC;


    public AmbientTemperature( final float _degreesC ) {
        degreesC = _degreesC;
    }


    public String toString() {
        return String.format( "Ambient temperature %1$.2f degrees Celcius", degreesC );
    }
}
