package com.dilatush.shedsolar.events;

import com.dilatush.util.syncevents.SynchronousEvent;

/**
 * @author Tom Dilatush  tom@dilatush.com
 */
public class HeaterStuckOn implements SynchronousEvent {

    public String toString() {
        return "Heater Stuck On";
    }
}