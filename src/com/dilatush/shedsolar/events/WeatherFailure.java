package com.dilatush.shedsolar.events;

import com.dilatush.util.syncevents.SynchronousEvent;

/**
 * @author Tom Dilatush  tom@dilatush.com
 */
public class WeatherFailure implements SynchronousEvent {

    public String toString() {
        return "Weather report not received";
    }
}
