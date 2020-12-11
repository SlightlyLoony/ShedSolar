package com.dilatush.shedsolar.events;

import com.dilatush.util.syncevents.SynchronousEvent;

/**
 * @author Tom Dilatush  tom@dilatush.com
 */
public class SSRSenseFailure implements SynchronousEvent {

    public String toString() {
        return "SSR sense relay failure";
    }
}
