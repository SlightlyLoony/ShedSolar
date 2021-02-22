package com.dilatush.shedsolar;

/**
 * A static container class for functions computing values related to thermal performance.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class ThermalCalcs {


    /**
     * Computes Tb in the equation below, from the given values, for a closed system consisting of a mass with a constant thermal inertia surrounded
     * by an insulated container with a constant thermal resistance.
     * <pre>{@code
     *    Tb = Ti + Td(1-e^(-tK)), where:
     *         Tb = temperature inside the insulated container at the given time, in °C
     *         Ti = temperature inside the insulated container at t=0, in °C
     *         Td = temperature difference at t=0 between the outside of the container and Ti (Toutside - Ti), in °C
     *         e = Euler's constant
     *         t = time since Ti was measured, in seconds
     *         K = dimensionless constant, the product of thermal inertia and thermal resistance of the system, determined by observation
     * }</pre>
     *
     * @param _Ti The temperature inside the insulated container at t=0, in °C.
     * @param _Td The temperature difference at t=0 between the outside of the container and Ti (Toutside - Ti), in °C.
     * @param _K A constant, the product of thermal inertia and thermal resistance of the system, determined by observation.
     * @param _t The time since Ti was measured, in seconds.
     * @return Tb, the temperature inside the closed system at the given time, in °C
     */
    public static double temperatureChange( final double _Ti, final double _Td, final double _K, final double _t ) {
        return _Ti + _Td * ( 1 - Math.pow( Math.E, -_t * _K ) );
    }


    /**
     * Computes K in the equation below, from the given values, for a closed system consisting of a mass with a constant thermal inertia surrounded
     * by an insulated container with a constant thermal resistance.
     * <pre>{@code
     *    K = -(ln(-((Tb - Ti)/Td - 1))/t), where:
     *         Tb = temperature inside the insulated container at the given time, in °C
     *         Ti = temperature inside the insulated container at t=0, in °C
     *         Td = temperature difference at t=0 between the outside of the container and Ti (Toutside - Ti), in °C
     *         e = Euler's constant
     *         ln() = natural logarithm
     *         t = time since Ti was measured, in seconds
     *         K = dimensionless constant, the product of thermal inertia and thermal resistance of the system, determined by observation
     * }</pre>
     *
     * @param _Tb The temperature inside the closed system at the given time, in °C
     * @param _Ti The temperature inside the insulated container at t=0, in °C.
     * @param _Td The temperature difference at t=0 between the outside of the container and Ti (Toutside - Ti), in °C.
     * @param _t The time since Ti was measured, in seconds.
     * @return K, the temperature inside the closed system
     */
    public static double k( final double _Tb, final double _Ti, final double _Td, final double _t ) {
        return -Math.log( -((_Tb - _Ti)/_Td - 1)) / _t ;
    }


    /**
     * Computes t in the equation below, from the given values, for a closed system consisting of a mass with a constant thermal inertia surrounded
     * by an insulated container with a constant thermal resistance.
     * <pre>{@code
     *    t = -(ln(-((Tb - Ti)/Td - 1))/K), where:
     *         Tb = temperature inside the insulated container at the given time, in °C
     *         Ti = temperature inside the insulated container at t=0, in °C
     *         Td = temperature difference at t=0 between the outside of the container and Ti (Toutside - Ti), in °C
     *         e = Euler's constant
     *         ln() = natural logarithm
     *         t = time since Ti was measured, in seconds
     *         K = dimensionless constant, the product of thermal inertia and thermal resistance of the system, determined by observation
     * }</pre>
     *
     * @param _Tb The temperature inside the closed system at the computed time, in °C
     * @param _Ti The temperature inside the insulated container at t=0, in °C.
     * @param _Td The temperature difference at t=0 between the outside of the container and Ti (Toutside - Ti), in °C.
     * @param _K A constant, the product of thermal inertia and thermal resistance of the system, determined by observation.
     * @return t, the time required for the temperature inside the closed system to change from Ti to Tb
     */
    public static double t( final double _Tb, final double _Ti, final double _Td, final double _K ) {
        return -Math.log( -((_Tb - _Ti)/_Td - 1)) / _K ;
    }
}
