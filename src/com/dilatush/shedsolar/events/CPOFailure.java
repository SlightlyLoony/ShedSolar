package com.dilatush.shedsolar.events;

import com.dilatush.util.syncevents.SynchronousEvent;

/**
 * Published when we've lost connection to the CPO...
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class CPOFailure implements SynchronousEvent {

    public String toString() {
        return "Post office not connected to the CPO";
    }
}
