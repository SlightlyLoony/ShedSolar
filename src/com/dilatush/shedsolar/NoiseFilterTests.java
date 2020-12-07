package com.dilatush.shedsolar;

import java.time.Instant;

/**
 * @author Tom Dilatush  tom@dilatush.com
 */
public class NoiseFilterTests {

    public static void main( final String[] _args ) {

        // set the configuration file location (must do before any logging actions occur)...
        System.getProperties().setProperty( "java.util.logging.config.file", "logging.properties" );

        NoiseFilter nf1 = new NoiseFilter( 40000, NoiseFilterTests::closeness );
        float[] data = new float[]
                {
                        22f, 23f, 12f, 22f, 25f, 24f, 25f, 26f, 27f, 28f,
                        29f, 30f, 14f, 59f, 32f, 33f, 34f, 35f, 36f, 36f,
                        87f, 35f, 34f, 32f, 28f, 25f, 24f, 24f, 24f, 24f,
                        24f, 24f, 24f, 24f, 24f, 24f, 24f, 24f, 11f, 24f,
                        24f, 24f, 24f, 24f, 24f, 24f, 24f, 24f, 24f, 24f,
                        24f, 24f, 24f, 24f, 24f, 24f, 24f, 24f, 11f, 24f
                };
        test( nf1, data, 500, 20000, 5000 );
        out( nf1.toString() );
        nf1.hashCode();
    }


    private static float closeness( final NoiseFilter.MeasurementReading _newReading, final NoiseFilter.MeasurementReading _existingReading ) {
        return (float) (1000f + Math.abs( _newReading.measurement - _existingReading.measurement )
                * Math.pow(_newReading.timestamp.toEpochMilli() - _existingReading.timestamp.toEpochMilli(), 0.9f));
    }


    private static void test( final NoiseFilter _nf, final float[] _measurements, final long _intervalMS, final long _minDepth, final long _noise ) {
        Instant currentTime = Instant.now().minusMillis( _intervalMS * _measurements.length );
        for( float measurement : _measurements ) {
            _nf.addSample( new NoiseFilter.MeasurementReading( measurement, currentTime ) );
            currentTime = currentTime.plusMillis( _intervalMS );
            _nf.prune( currentTime );
            if( measurement == 11f )
                currentTime.hashCode();
            NoiseFilter.MeasurementReading reading = _nf.measurementAt( _minDepth, _noise, currentTime );
            if( reading != null)
                out( reading.toString() );
//            out( _nf.toString() );
        }
    }


    private static void out( final String _s ) {
        System.out.println( _s );
    }
}
