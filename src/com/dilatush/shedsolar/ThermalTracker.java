package com.dilatush.shedsolar;

import com.dilatush.util.info.InfoSource;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.dilatush.shedsolar.Events.NORMAL_HEATER_ON;
import static com.dilatush.shedsolar.Events.TEMPERATURES_READ;

/**
 * Records battery and heater temperatures each time they're published, as well as heater on and off times, for each normal heater cycle, defined
 * as heater on to heater on.  Recording lasts three hours at most; after that we assume something went horribly wrong.  The most recent 25 successful
 * cycle recordings are stored in text files in the "recordings" subdirectory, with file names reflecting the UTC time the recording started.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class ThermalTracker {

    private final ShedSolar shedSolar = ShedSolar.instance;

    private final AtomicBoolean                      tracking;     // true if we're currently tracking (IOW, we're in a heater cycle)...
    private final AtomicReference<Instant>           startTime;    // the time we started this heater cycle...
    private final AtomicReference<Instant>           stopTime;     // the time we stopped this heater cycle...
    private final AtomicReference<InfoSource<Float>> ambientTemp;  // the ambient temperature at the start of this heater cycle...
    private final AtomicReference<InfoSource<Float>> outsideTemp;  // the outside temperature at the start of this heater cycle...
    private final List<TrackingRecord>               records;      // the temperature records we've received...



    /**
     * Create a new instance of this class.
     */
    public ThermalTracker() {

        // initialize all our atomics...
        tracking    = new AtomicBoolean( false );
        startTime   = new AtomicReference<>( null );
        stopTime    = new AtomicReference<>( null );
        ambientTemp = new AtomicReference<>( null );
        outsideTemp = new AtomicReference<>( null );

        // initialize our tracking list...
        records = new ArrayList<>( 3600 );

        // subscribe to the haps we care about...
        shedSolar.haps.subscribe( NORMAL_HEATER_ON, this::heaterOn );
        shedSolar.haps.subscribe( TEMPERATURES_READ, this::temperaturesRead );
    }


    private void heaterOn() {

        // if we have a recording underway, finalize it...
        if( tracking.get() )
            finalizeRecording();

        // indicate we are tracking...
        tracking.set( true );

        // set the start time for our new recording...
        startTime.set( Instant.now( Clock.systemUTC() ) );

        // remember the ambient and outside temperatures for recording purposes...
        ambientTemp.set( shedSolar.ambientTemperature.getInfoSource() );
        outsideTemp.set( shedSolar.outsideTemperature.getInfoSource() );
        
        // make the first record our starting battery and heater temperatures...
        records.add( new TrackingRecord( shedSolar.batteryTemperature.getInfoSource(), shedSolar.heaterTemperature.getInfoSource() ) );
    }


    private void temperaturesRead() {

        // if we're not tracking, just leave...
        if( !tracking.get() )
            return;

        // we ARE tracking, so save a record...
        records.add( new TrackingRecord( shedSolar.batteryTemperature.getInfoSource(), shedSolar.heaterTemperature.getInfoSource() ) );
    }


    private void finalizeRecording() {

        // our stop time...
        stopTime.set( Instant.now( Clock.systemUTC() ) );

        // do the file recording in another thread...

        // clear our recorded data...
        records.clear();
        startTime.set( null );
        stopTime.set( null );
    }


    private static class TrackingRecord {

        private final InfoSource<Float> batteryTemp;
        private final InfoSource<Float> heaterTemp;


        private TrackingRecord( final InfoSource<Float> _batteryTemp, final InfoSource<Float> _heaterTemp ) {
            batteryTemp = _batteryTemp;
            heaterTemp = _heaterTemp;
        }
    }
}
