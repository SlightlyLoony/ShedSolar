package com.dilatush.shedsolar.events;

import com.dilatush.util.syncevents.SynchronousEvent;

/**
 * @author Tom Dilatush  tom@dilatush.com
 */
public class Weather implements SynchronousEvent {

    public final double irradiance;
    public final double temperature;


    public Weather( final double _irradiance, final double _temperature ) {
        irradiance = _irradiance;
        temperature = _temperature;
    }


    public String toString() {
        return String.format( "Weather: solar irradiance is %1$.0f watts/meter2, temperature is %2$.1fC", irradiance, temperature );
    }
}
