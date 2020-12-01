package com.dilatush.shedsolar.events;

import com.dilatush.shedsolar.OutbackData;
import com.dilatush.util.syncevents.SynchronousEvent;

/**
 * @author Tom Dilatush  tom@dilatush.com
 */
public class OutbackReading implements SynchronousEvent {

    public final OutbackData outbackData;


    public OutbackReading( final OutbackData _outbackData ) {
        outbackData = _outbackData;
    }


    public String toString() {
        return outbackData.toString();
    }
}
