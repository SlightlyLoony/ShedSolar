package com.dilatush.shedsolar.events;

import com.dilatush.util.syncevents.SynchronousEvent;

/**
 * @author Tom Dilatush  tom@dilatush.com
 */
public class OutbackFailure implements SynchronousEvent {

    public String toString() {
        return "Outback reading failure";
    }
}
