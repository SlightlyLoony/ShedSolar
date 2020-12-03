package com.dilatush.shedsolar.events;

import com.dilatush.shedsolar.TemperatureMode;
import com.dilatush.util.syncevents.SynchronousEvent;

/**
 * @author Tom Dilatush  tom@dilatush.com
 */
public class TempMode implements SynchronousEvent {

    public final TemperatureMode mode;


    public TempMode( final TemperatureMode _mode ) {
        mode = _mode;
    }


    public String toString() {
        return "TemperatureMode: " + mode;
    }
}
