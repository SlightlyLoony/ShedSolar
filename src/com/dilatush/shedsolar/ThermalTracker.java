package com.dilatush.shedsolar;

import com.dilatush.util.info.InfoSource;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.time.*;
import java.time.format.DateTimeFormatter;
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
 * as heater on to heater on.  The most recent 50 successful cycle recordings are stored in text files in the "recordings" subdirectory, with file
 * names reflecting the UTC time the recording started.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class ThermalTracker {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private static final int MAX_RECORDINGS             = 50;
    private static final String RECORDINGS_DIRECTORY    = "recordings";

    private final ShedSolar shedSolar = ShedSolar.instance;

    private final AtomicBoolean                   tracking;     // true if we're currently tracking (IOW, we're in a heater cycle)...
    private final AtomicReference<BufferedWriter> writer;       // writer for the tracking file...
    private final AtomicReference<File>           file;         // the file we're writing to (just in case we need to delete it)...
    private final AtomicReference<Instant>        started;      // the time we started a recording cycle...


    /**
     * Create a new instance of this class.
     */
    public ThermalTracker() {

        // initialize...
        tracking    = new AtomicBoolean( false );
        writer      = new AtomicReference<>( null );
        file        = new AtomicReference<>( null );
        started     = new AtomicReference<>( null );

        // subscribe to the haps we care about...
        shedSolar.haps.subscribe( NORMAL_HEATER_ON,       this::heaterOn      );
        shedSolar.haps.subscribe( NORMAL_HEATER_OFF,      this::heaterOff     );
        shedSolar.haps.subscribe( NORMAL_HEATER_NO_START, this::heaterNoStart );

        // schedule our once-per-second temperature readings...
        shedSolar.scheduledExecutor.scheduleAtFixedRate( this::temperaturesRead, Duration.ZERO, Duration.ofSeconds( 1 ) );
    }


    /* event and schedule listeners; they all just start a worker thread to do their actual work */


    private void heaterOn() {
        shedSolar.executor.submit( this::heaterOnImpl );
    }


    private void heaterOff() {

        // if we're not tracking, just leave...
        if( !tracking.get() )
            return;

        shedSolar.executor.submit( this::heaterOffImpl );
    }


    private void heaterNoStart() {

        // if we're not tracking, just leave...
        if( !tracking.get() )
            return;

        shedSolar.executor.submit( this::heaterNoStartImpl );
    }


    private void temperaturesRead() {

        // if we're not tracking, just leave...
        if( !tracking.get() )
            return;

        shedSolar.executor.submit( this::temperaturesReadImpl );
    }


    private enum RecordType { ON, OFF, TEMP }


    /**
     * Called when there's a normal heater controller running and the heater has just been turned on.
     */
    private void heaterOnImpl() {

        // capture a precise start time (as finalizeRecording could take several seconds)...
        Instant startTime = Instant.now( Clock.systemUTC() );
        ZonedDateTime localStartTime = ZonedDateTime.ofInstant( startTime, ZoneId.of( "America/Denver" ) );

        // if we have a recording underway, finalize it...
        if( tracking.get() )
            finalizeRecording();

        LOGGER.log( Level.FINEST, () -> "Heater on: Tracking started" );

        /* start up a new tracking session */

        try {
            // set up our buffered writer...
            DateTimeFormatter fileNameFormatter    = DateTimeFormatter.ofPattern( "yyyy-MM-dd_HH-mm-ss'.rec'" );
            String fileName = fileNameFormatter.format( localStartTime );
            file.set( new File( RECORDINGS_DIRECTORY + "/" + fileName ) );
            LOGGER.log( Level.FINEST, () -> "File: " + file.get().getAbsolutePath() );
            writer.set( new BufferedWriter( new OutputStreamWriter( new FileOutputStream( file.get() ), StandardCharsets.UTF_8 ) ) );

            // write out the "heater on" record...
            writeRecord(RecordType.ON, startTime, null );

            // indicate we are tracking...
            tracking.set( true );
            started.set( Instant.now( Clock.systemUTC() ) );

        } catch( IOException _e ) {
            handleFileError( _e );
        }
    }


    private void handleFileError( final IOException _e ) {

        LOGGER.log( Level.SEVERE, "File error while writing thermal tracking information: " + _e.getMessage(), _e );

        // make sure we've closed our writer and turned tracking off...
        try {
            writer.get().close();
        } catch (IOException e) {
            // naught to do here, so just ignore the exception...
        }
        writer.set( null );
        tracking.set( false );
    }


    private final static DateTimeFormatter timestampFormatter   = DateTimeFormatter.ofPattern( "yyyy/MM/dd HH:mm:ss"       );

    /**
     * Writes a single record out to the recording file, with the given timestamp, type, and optional fields.  The fields must have commas
     * between them, but no leading comma.
     *
     * @param _type The type of this record.
     * @param _timestamp The timestamp for this record.
     * @param _fields The (optional) fields for this record.
     */
    private void writeRecord( final RecordType _type, final Instant _timestamp, final String _fields ) {

        BufferedWriter bw = writer.get();

        LOGGER.log( Level.FINEST, "Write record: " + _type.toString() );

        try {
            // write out the record type...
            switch( _type ) {
                case ON:   bw.write( 'O' ); break;
                case OFF:  bw.write( 'F' ); break;
                case TEMP: bw.write( 'T' ); break;
            }
            bw.write( ',' );

            // write out the timestamp...
            bw.write( timestampFormatter.format( _timestamp ) );

            // if we have fields, write them out...
            if( _fields != null ) {
                bw.write( ',' );
                bw.write( _fields );
            }

            // end the line...
            bw.newLine();
            bw.flush();

        } catch (IOException _e) {
            handleFileError( _e );
        }
    }


    private final static DecimalFormat     temperatureFormatter = new DecimalFormat( "##0.00" );

    /**
     * Returns a string representing the temperature in °C, "OLD" if the data is more than two minutes old, or
     * "MISSING" if the data is unavailable.
     *
     * @param _temp The {@link InfoSource} for the temperature to stringify.
     * @return the string representing the given temperature
     */
    private String getTemp( final InfoSource<Float> _temp ) {

        // if the data is unavailable, we know what to do...
        if( !_temp.isInfoAvailable() )
            return "MISSING";

        // is the data more than two minutes old?
        Duration delta = Duration.between( _temp.getInfoTimestamp(), Instant.now( Clock.systemUTC() ) );
        if( delta.compareTo(Duration.ofMinutes( 2 ) )  > 0 )
            return "OLD";

        // we have data, and it is fresh, so spit it out...
        return temperatureFormatter.format( _temp.getInfo() );
    }


    /**
     * Called when there's a normal heater controller running and the heater has just been turned off.
     */
    private void heaterOffImpl() {
        LOGGER.log( Level.FINEST, () -> "Heater off" );
        writeRecord(RecordType.OFF, Instant.now( Clock.systemUTC() ), null );
    }


    /**
     * Called when there's a normal heater controller running and the heater has failed to start.  This causes the tracker to abandon the tracking
     * for the current cycle.
     */
    private void heaterNoStartImpl() {

        LOGGER.log( Level.FINEST, () -> "Heater no start" );

        // close the writer...
        try {
            writer.get().close();
        }
        catch( IOException _e ) {
            // naught to do; just ignore it...
        }

        // erase the file...
        if( !file.get().delete() )
            LOGGER.warning( "Failed to delete thermal tracking file: " + file.get().getName() );

        // get ready for a new start...
        writer.set( null );
        file.set( null );
        tracking.set( false );
    }


    /**
     * Called once per second, whether we're tracking or not.
     */
    private void temperaturesReadImpl() {

        LOGGER.log( Level.FINEST, () -> "Temperatures Read" );

        // check to see if we've exceeded our three day limit...
        if( Duration.between( started.get(), Instant.now( Clock.systemUTC() ) ).compareTo( Duration.ofDays( 3 ) ) > 0 ) {

            // shut our tracking down...

            // make sure we've closed our writer and turned tracking off...
            try {
                writer.get().close();
            } catch (IOException e) {
                // naught to do here, so just ignore the exception...
            }
            writer.set( null );
            tracking.set( false );
            return;
        }

        // we ARE tracking, so let's get our temps and write the record...
        String temps =
                getTemp( shedSolar.batteryTemperature.getInfoSource() ) + ","
                + getTemp( shedSolar.heaterTemperature.getInfoSource() ) + ","
                + getTemp( shedSolar.ambientTemperature.getInfoSource() ) + ","
                + getTemp( shedSolar.outsideTemperature.getInfoSource() );
        writeRecord( RecordType.TEMP, Instant.now( Clock.systemUTC() ), temps );
    }


    /**
     * Create a TrackingCycle instance with the data we just finished collecting, and submit it for writing to a file.
     */
    private void finalizeRecording() {

        LOGGER.log( Level.FINEST, () -> "Finalizing recording" );

        // close our writer...
        try {
            writer.get().close();
        }
        catch( IOException _e ) {
            LOGGER.log( Level.SEVERE, "Unexpected problem closing thermal tracking file: " + _e.getMessage(), _e );
        }

        // get things ready for the next cycle...
        writer.set( null );
        file.set( null );
        tracking.set( false );

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

        LOGGER.info( () -> "Number of thermal tracking files: " + files.size() );
    }
}
