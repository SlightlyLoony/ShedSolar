package com.dilatush.shedsolar;

import static com.dilatush.shedsolar.HeaterControl.HeaterControllerContext;

/**
 * Implemented by classes that provide heater controllers.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public interface HeaterController {


    /**
     * Called periodically by {@link HeaterControl} with the given {@link HeaterControllerContext} to update this heater controller.
     *
     * @param _context The heater controller context.
     */
    void tick( final HeaterControllerContext _context );


    /**
     * Called by {@link HeaterControl} to tell this heater controller to turn off the heater and heater LED, and return to initial state, ready for
     * reuse.
     */
    void reset();
}
