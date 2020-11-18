package com.dilatush.shedsolar;

/**
 * Implemented by classes that can read temperature values.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public interface TempReader {

    /**
     * Returns the temperature in degrees Celcius.
     *
     * @return the temperature in degrees Celcius
     */
    double temperature();
}
