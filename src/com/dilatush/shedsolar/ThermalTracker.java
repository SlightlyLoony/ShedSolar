package com.dilatush.shedsolar;

import com.dilatush.util.info.InfoSource;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.dilatush.shedsolar.Events.*;

/**
 * Records battery and heater temperatures each time they're published, as well as heater on and off times, for each normal heater cycle, defined
 * as heater on to heater on.  Recording lasts three hours at most; after that we assume something went horribly wrong.  The most recent 50 successful
 * cycle recordings are stored in text files in the "recordings" subdirectory, with file names reflecting the UTC time the recording started.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class ThermalTracker {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private static final int MAX_RECORDING_TIME_MINUTES = 180;
    private static final int MAX_TEMPERATURE_RECORDS    = MAX_RECORDING_TIME_MINUTES * 60;
    private static final int MAX_RECORDINGS             = 50;
    private static final String RECORDINGS_DIRECTORY    = "recordings";

    private final ShedSolar shedSolar = ShedSolar.instance;

    private final AtomicBoolean                         tracking;     // true if we're currently tracking (IOW, we're in a heater cycle)...
    private final AtomicReference<Instant>              startTime;    // the time we started this heater cycle...
    private final AtomicReference<Instant>              stopTime;     // the time we stopped the heater on this heater cycle...
    private final AtomicReference<Float>                ambientTemp;  // the ambient temperature at the start of this heater cycle...
    private final AtomicReference<Float>                outsideTemp;  // the outside temperature at the start of this heater cycle...
    private final AtomicReference<List<TrackingRecord>> records;      // the temperature records we've received...



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
        records     = new AtomicReference<>( null );

        // initialize our tracking list...
        records.set( new ArrayList<>( MAX_TEMPERATURE_RECORDS ) );

        // subscribe to the haps we care about...
        shedSolar.haps.subscribe( NORMAL_HEATER_ON,       this::heaterOn      );
        shedSolar.haps.subscribe( NORMAL_HEATER_OFF,      this::heaterOff     );
        shedSolar.haps.subscribe( NORMAL_HEATER_NO_START, this::heaterNoStart );

        // schedule our once-per-second temperature readings...
        shedSolar.scheduledExecutor.scheduleAtFixedRate( this::temperaturesRead, Duration.ZERO, Duration.ofSeconds( 1 ) );
    }


    /**
     * Called when there's a normal heater controller running and the heater has just been turned on.
     */
    private void heaterOn() {

        // if we have a recording underway, finalize it...
        if( tracking.get() )
            finalizeRecording();

        LOGGER.log( Level.FINEST, () -> "Tracking started" );

        // indicate we are tracking...
        tracking.set( true );

        // set the start time for our new recording...
        startTime.set( Instant.now( Clock.systemUTC() ) );

        // remember the ambient and outside temperatures for recording purposes...
        InfoSource<Float> ambientSource = shedSolar.ambientTemperature.getInfoSource();
        InfoSource<Float> outsideSource = shedSolar.outsideTemperature.getInfoSource();
        if( !ambientSource.isInfoAvailable() || !outsideSource.isInfoAvailable() ) {
            LOGGER.log( Level.WARNING, "Ambient temperature and outside temperature are not both available; abandoning tracking for this cycle" );
            clear();
            return;
        }
        ambientTemp.set( ambientSource.getInfo() );
        outsideTemp.set( outsideSource.getInfo() );
    }


    /**
     * Called when there's a normal heater controller running and the heater has just been turned off.
     */
    private void heaterOff() {
        LOGGER.log( Level.FINEST, () -> "Heater off" );
        stopTime.set( Instant.now( Clock.systemUTC() ) );
    }


    /**
     * Called when there's a normal heater controller running and the heater has failed to start.  This causes the tracker to abandon the tracking
     * for the current cycle.
     */
    private void heaterNoStart() {
        LOGGER.log( Level.FINEST, () -> "Heater no start" );
        clear();
    }


    /**
     * Called once per second, whether we're tracking or not.
     */
    private void temperaturesRead() {

        LOGGER.log( Level.FINEST, () -> "Temperatures Read" );

        // if we're not tracking, just leave...
        if( !tracking.get() )
            return;

        // if our records array is full, then this recording is taking too long; abandon it...
        if( records.get().size() == MAX_TEMPERATURE_RECORDS ) {
            LOGGER.log( Level.INFO, "Thermal tracker buffer is full; abandoning thermal tracking for this cycle" );
            clear();
            return;
        }

        // we ARE tracking, so let's see if we have valid temperatures...
        InfoSource<Float> batterySource = shedSolar.batteryTemperature.getInfoSource();
        InfoSource<Float> heaterSource = shedSolar.heaterTemperature.getInfoSource();
        if(
                !batterySource.isInfoAvailable()
                || batterySource.getInfoTimestamp().isBefore( Instant.now( Clock.systemUTC() ).minusSeconds( 1 ) )
                || !heaterSource.isInfoAvailable()
                || heaterSource.getInfoTimestamp().isBefore( Instant.now( Clock.systemUTC() ).minusSeconds( 1 ) ) ) {

            LOGGER.log( Level.WARNING, "Problem reading temperatures; thermal tracking abandoned" );
            LOGGER.finest( () -> "Battery available: " + batterySource.isInfoAvailable() );
            LOGGER.finest( () -> "Battery too old: " + batterySource.getInfoTimestamp().isBefore( Instant.now( Clock.systemUTC() ).minusSeconds( 1 ) ) );
            LOGGER.finest( () -> "Heater available: " + heaterSource.isInfoAvailable() );
            LOGGER.finest( () -> "Heater too old: " + heaterSource.getInfoTimestamp().isBefore( Instant.now( Clock.systemUTC() ).minusSeconds( 1 ) ) );
            clear();
            return;
        }

        // we have valid temperatures, so record them...
        LOGGER.log( Level.FINEST, () -> "Recording temperatures" );
        records.get().add( new TrackingRecord( batterySource.getInfo(), heaterSource.getInfo() ) );
    }


    /**
     * Create a TrackingCycle instance with the data we just finished collecting, and submit it for writing to a file.
     */
    private void finalizeRecording() {

        LOGGER.log( Level.FINEST, () -> "Finalizing recording" );

        // our stop time...
        stopTime.set( Instant.now( Clock.systemUTC() ) );

        // do the file recording in another thread...
        TrackingCycle cycle = new TrackingCycle( records.get(), startTime.get(), stopTime.get(), ambientTemp.get(), outsideTemp.get() );
        shedSolar.executor.submit( cycle::record );

        // create a new list for records, and clear...
        records.set( new ArrayList<>( MAX_TEMPERATURE_RECORDS ) );
        clear();
    }


    /**
     * Clear all our tracking data, and set tracking to {@code false} (not tracking).
     */
    private void clear() {

        // stop tracking...
        tracking.set( false );

        // clear our recorded data...
        records.get().clear();
        startTime.set( null );
        stopTime.set( null );

    }


    private final static DateTimeFormatter fileNameFormatter    = DateTimeFormatter.ofPattern( "yyyy-MM-dd_HH-mm-ss'.rec'" );
    private final static DateTimeFormatter timestampFormatter   = DateTimeFormatter.ofPattern( "yyyy/MM/dd HH:mm:ss"       );
    private final static DecimalFormat     temperatureFormatter = new DecimalFormat( "##0.00" );


    /**
     * Contains the data from one tracking cycle, and records it into a file.  It also deletes older files.
     */
    private static class TrackingCycle {

        private final List<TrackingRecord> records;
        private final Instant              startTime;
        private final Instant              stopTime;
        private final float                ambientTemperature;
        private final float                outsideTemperature;


        private TrackingCycle( final List<TrackingRecord> _records, final Instant _startTime, final Instant _stopTime,
                              final float _ambientTemperature, final float _outsideTemperature ) {

            records = _records;
            startTime = _startTime;
            stopTime = _stopTime;
            ambientTemperature = _ambientTemperature;
            outsideTemperature = _outsideTemperature;
        }


        /**
         * Creates and writes this tracking cycle to a file in the recordings directory, and deletes the oldest of any files that exceed the maximum
         * number of files.  The file format is very simple: the first line is the header, and the subsequent lines are temperature records.  The file
         * is text, encoded in UTF-8.  The header line has the following comma-separated fields (in order): start time, heater off time, ambient
         * temperature, outside temperature.  The temperature records have the following comma-separated fields (in order): timestamp, battery
         * temperature, heater temperature.  The times are all in YYYY/MM/DD hh:mm:ss format (for example, "2021/03/03 14:54:22").  The temperatures
         * are all in °C with two decimals (for example, 3.88).
         */
        private void record() {

            try {

                LOGGER.log( Level.FINEST, () -> "Recording" );

                /* First we write out our new file */

                // make our file name...
                ZonedDateTime localStartTime = ZonedDateTime.ofInstant( startTime, ZoneId.of( "America/Denver" ) );
                String fileName = fileNameFormatter.format( localStartTime );

                // open the file for writing...
                File recording = new File( RECORDINGS_DIRECTORY + "/" + fileName );
                LOGGER.log( Level.FINEST, () -> "File: " + recording.getAbsolutePath() );
                if( recording.createNewFile() ) {

                    // get the writer we'll use to output our text...
                    BufferedWriter writer = new BufferedWriter( new OutputStreamWriter( new FileOutputStream( recording ), StandardCharsets.UTF_8 ) );

                    // write out the header...
                    ZonedDateTime localStopTime = ZonedDateTime.ofInstant( stopTime, ZoneId.of( "America/Denver" ) );
                    writer.write( timestampFormatter.format( localStartTime ) + "," + timestampFormatter.format( localStopTime ) + ","
                            + temperatureFormatter.format( ambientTemperature ) + "," + temperatureFormatter.format( outsideTemperature ) );
                    writer.newLine();

                    // write out all our temperature records...
                    for( TrackingRecord record : records ) {
                        ZonedDateTime localTimestamp = ZonedDateTime.ofInstant( record.timestamp, ZoneId.of( "America/Denver" ) );
                        writer.write( timestampFormatter.format( localTimestamp ) + ","
                                + temperatureFormatter.format( record.batteryTemp ) + "," + temperatureFormatter.format( record.heaterTemp ) );
                        writer.newLine();
                    }

                    // we're done writing, so close our writer...
                    writer.flush();
                    writer.close();
                }
                else {
                    LOGGER.log( Level.SEVERE, "Could not create cycle tracking record" );
                }

                /* Now we make sure we're not accumulating too many records */

                // get a list of the files in our recordings directory...
                File recordings = new File( RECORDINGS_DIRECTORY );
                File[] filesFound = recordings.listFiles();

                // make sure we actually got a directory...
                if( filesFound == null ) {
                    LOGGER.log( Level.SEVERE, "Recordings directory is not a directory" );
                    return;
                }

                // get the files into a list and sort them so the oldest are first on the list...
                List<File> files = Arrays.asList( filesFound );
                files.sort( Comparator.comparing( File::getName ) );

                // now delete files as needed to get the number of files down to the maximum allowed...
                while( files.size() > MAX_RECORDINGS ) {
                    if( files.get( 0 ).delete() )
                        files.remove( 0 );
                    else {
                        LOGGER.log( Level.SEVERE, "Could not delete " + files.get( 0 ).getName() );
                        break;
                    }
                }
            }
            catch( Exception _e ) {
                LOGGER.log( Level.SEVERE, "Problem while recording thermal cycle", _e );
            }
        }
    }


    /**
     * Contains the data from one tracking record (one per second).
     */
    private static class TrackingRecord {

        private final float   batteryTemp;
        private final float   heaterTemp;
        private final Instant timestamp;


        /**
         * Creates a new instance of this class with the given temperatures.
         *
         * @param _batteryTemp The battery temperature in °C.
         * @param _heaterTemp The heater output temperature in °C.
         */
        private TrackingRecord( final float _batteryTemp, final float _heaterTemp ) {
            batteryTemp = _batteryTemp;
            heaterTemp  = _heaterTemp;
            timestamp   = Instant.now( Clock.systemUTC() );
        }
    }
}
