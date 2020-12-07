package com.dilatush.shedsolar;

import com.dilatush.shedsolar.events.AmbientTemperature;
import com.dilatush.shedsolar.events.BatteryTemperature;
import com.dilatush.shedsolar.events.HeaterTemperature;
import com.dilatush.util.Config;
import com.dilatush.util.syncevents.SynchronousEvent;
import com.dilatush.util.syncevents.SynchronousEvents;
import com.dilatush.util.test.ATestInjector;
import com.pi4j.io.spi.SpiChannel;
import com.pi4j.io.spi.SpiDevice;
import com.pi4j.io.spi.SpiFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <p>Implements a temperature reader for two MAX31855 type K thermocouple interface chips, one for battery temperature and the other for
 * the heater output temperature.  The ambient temperature of the MAX31855 is also obtained as a side effect.</p>
 * <p>Reading these chips is far more challenging than one would expect.  The problem is that the thermocouple reading is occasionally anomalous,
 * for reasons that we've never been able to figure out.  Both parts that we used exhibit the same behavior.  Furthermore, if the chips are read
 * multiple times in quick succession, the anomalous reading persists.  Our solution is to read the temperatures at 500ms intervals and throw out
 * the outliers.  This is not as easy as it sounds!</p>
 * <p>While temperatures are actually read twice a second, they are only reported (by events) upon change.  During startup, readings are taken for
 * 10 seconds (20 readings) to determine the baseline.</p>
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class TempReader {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private final static int THERMOCOUPLE_MASK    = 0xFFFC0000;
    private final static int THERMOCOUPLE_OFFSET  = 18;
    private final static int COLD_JUNCTION_MASK   = 0x0000FFF0;
    private final static int COLD_JUNCTION_OFFSET = 4;
    private final static int IO_ERROR_MASK        = 0x00020000;
    private final static int SHORT_TO_VCC_MASK    = 0x00000004;
    private final static int SHORT_TO_GND_MASK    = 0x00000002;
    private final static int OPEN_MASK            = 0x00000001;
    private final static int FAULT_MASK           = IO_ERROR_MASK | SHORT_TO_GND_MASK | SHORT_TO_VCC_MASK + OPEN_MASK;


    // the Pi4J SPI device instances for each of our sensors...
    private final SpiDevice batteryTemp;
    private final SpiDevice heaterTemp;

    // four bytes of data to write when reading temperature...
    private final byte[] writeData = new byte[] { (byte) 0, (byte) 0, (byte) 0, (byte) 0 };

    private final TempReadTest batteryTest = new TempReadTest();
    private final TempReadTest heaterTest = new TempReadTest();

    private final NoiseFilter batteryFilter;
    private final NoiseFilter heaterFilter;

    private final long         sampleTreeDepthMS;
    private final long         sampleTreeNoiseMS;
    private final long         errorEventIntervalMS;

    private NoiseFilter.MeasurementReading lastBatteryReading = null;
    private NoiseFilter.MeasurementReading lastHeaterReading  = null;
    private NoiseFilter.MeasurementReading lastAmbientReading = null;

    private Instant lastBatteryErrorEvent = null;
    private Instant lastHeaterErrorEvent  = null;


    public TempReader( final Config _config ) throws IOException {

        // get our configuration parameters...
        long intervalMS               = _config.getLongDotted(  "tempReader.intervalMS"              );
        sampleTreeDepthMS             = _config.getLongDotted(  "tempReader.sampleTreeDepthMS"       );
        sampleTreeNoiseMS             = _config.getLongDotted(  "tempReader.sampleTreeNoiseMS"       );
        errorEventIntervalMS          = _config.getLongDotted(  "tempReader.errorEventIntervalMS"    );
        float sampleTreeDeratingPower = _config.getFloatDotted( "tempReader.sampleTreeDeratingPower" );

        // get our SPI devices...
        batteryTemp = SpiFactory.getInstance( SpiChannel.CS0, SpiDevice.DEFAULT_SPI_SPEED, SpiDevice.DEFAULT_SPI_MODE );
        heaterTemp  = SpiFactory.getInstance( SpiChannel.CS1, SpiDevice.DEFAULT_SPI_SPEED, SpiDevice.DEFAULT_SPI_MODE );

        // create our noise filters...
        batteryFilter = new NoiseFilter( sampleTreeDepthMS, TempReader::closeness );
        heaterFilter  = new NoiseFilter( sampleTreeDepthMS, TempReader::closeness );

        // register our tests...
        App.instance.orchestrator.registerTestInjector( batteryTest, "TempReader.readBattery" );
        App.instance.orchestrator.registerTestInjector( heaterTest,  "TempReader.readHeater" );

        // schedule our temperature reader...
        App.instance.timer.schedule( new TempReaderTask(), 0, intervalMS );
    }


    private static float closeness( final NoiseFilter.MeasurementReading _new, final NoiseFilter.MeasurementReading _existing ) {
        return 0;
    }



    private class TempReaderTask extends TimerTask {

        /**
         * The action to be performed by this timer task.
         */
        @Override
        public void run() {

            try {

                /* handle the battery reading... */
                // first get the raw reading...
                int rawBattery = batteryTest.inject( getRaw( batteryTemp, "Battery" ) );

                // if there's an error, handle it...
                if( (rawBattery & FAULT_MASK) != 0 ) {

                    // see if we need to send an event...
                    if( (lastBatteryErrorEvent == null) || (Instant.now().isAfter( lastBatteryErrorEvent.plusMillis( errorEventIntervalMS ))) ) {

                        // get our flags...
                        boolean ioerror         = ((rawBattery & IO_ERROR_MASK    ) != 0);
                        boolean open            = ((rawBattery & OPEN_MASK        ) != 0);
                        boolean shortToVCC      = ((rawBattery & SHORT_TO_VCC_MASK) != 0);
                        boolean shortToGnd      = ((rawBattery & SHORT_TO_GND_MASK) != 0);

                        // publish the event...
                        publishEvent( new BatteryTemperature( -1000, false, ioerror, open, shortToGnd, shortToVCC ) );

                        // mark the time...
                        lastBatteryErrorEvent = Instant.now();
                    }
                }

                // otherwise, handle the sample...
                else {

                    // add a sample to the filter...
                    float batteryTemp        = ((rawBattery & THERMOCOUPLE_MASK ) >> THERMOCOUPLE_OFFSET ) /  4.0f;
                    batteryFilter.addSample( new NoiseFilter.MeasurementReading( batteryTemp, Instant.now() ) );

                    // prune our filter...
                    batteryFilter.prune( Instant.now() );

                    // get a temperature reading, if we can...
                    NoiseFilter.MeasurementReading reading = batteryFilter.measurementAt( sampleTreeDepthMS * 3/4, sampleTreeNoiseMS, Instant.now() );

                    // if we got a reading, and it's different than our last reading, send an event...
                    if( (reading != null) && ((lastBatteryReading == null) || (reading.measurement != lastBatteryReading.measurement)) ) {
                        publishEvent( new BatteryTemperature( reading.measurement, true, false, false, false, false ) );
                        lastBatteryReading = reading;
                    }
                }

                /* handle the heater reading... */
                // first get the raw reading...
                 int rawHeater  = heaterTest.inject(  getRaw( heaterTemp,  "Heater"  ) );

                 // if there's an error, handle it...
                if( (rawHeater & FAULT_MASK) != 0 ) {

                    // see if we need to send an event...
                    if( (lastHeaterErrorEvent == null) || (Instant.now().isAfter( lastHeaterErrorEvent.plusMillis( errorEventIntervalMS ))) ) {

                        // get our flags...
                        boolean ioerror         = ((rawHeater & IO_ERROR_MASK    ) != 0);
                        boolean open            = ((rawHeater & OPEN_MASK        ) != 0);
                        boolean shortToVCC      = ((rawHeater & SHORT_TO_VCC_MASK) != 0);
                        boolean shortToGnd      = ((rawHeater & SHORT_TO_GND_MASK) != 0);

                        // publish the event...
                        publishEvent( new HeaterTemperature( -1000, false, ioerror, open, shortToGnd, shortToVCC ) );

                        // mark the time...
                        lastHeaterErrorEvent = Instant.now();
                    }
                }

                // otherwise, handle the sample...
                else {

                    // add a sample to the filter...
                    float heaterTemp        = ((rawHeater & THERMOCOUPLE_MASK ) >> THERMOCOUPLE_OFFSET ) /  4.0f;
                    heaterFilter.addSample( new NoiseFilter.MeasurementReading( heaterTemp, Instant.now() ) );

                    // prune our filter...
                    heaterFilter.prune( Instant.now() );

                    // get a temperature reading, if we can...
                    NoiseFilter.MeasurementReading reading = heaterFilter.measurementAt( sampleTreeDepthMS * 3/4, sampleTreeNoiseMS, Instant.now() );

                    // if we got a reading, and it's different than our last reading, send an event...
                    if( (reading != null) && ((lastHeaterReading == null) || (reading.measurement != lastHeaterReading.measurement)) ) {
                        publishEvent( new HeaterTemperature( reading.measurement, true, false, false, false, false ) );
                        lastHeaterReading = reading;
                    }
                }

                /*
                * Now handle the ambient temperature.  Both chips supply it; we use the battery temperature chip if it's got a good reading,
                * the heater temperature chip otherwise.  If neither is available, we simply don't report ambient temperature.
                */

                // figure out what we're going to do with respect to ambient temperature...
                float ambientTemp = Float.NaN;
                if( (rawBattery & FAULT_MASK) == 0 )
                    ambientTemp = ((rawBattery & COLD_JUNCTION_MASK) >> COLD_JUNCTION_OFFSET) / 16.0f;
                else if( (rawHeater & FAULT_MASK) == 0 )
                    ambientTemp = ((rawHeater  & COLD_JUNCTION_MASK) >> COLD_JUNCTION_OFFSET) / 16.0f;

                // if we don't have a NaN, then we have a temperature...
                if( !Float.isNaN( ambientTemp ) ) {
                    NoiseFilter.MeasurementReading ambient = new NoiseFilter.MeasurementReading( ambientTemp, Instant.now() );
                    if( (lastAmbientReading == null) || (lastAmbientReading.measurement != ambient.measurement) ) {
                        publishEvent( new AmbientTemperature( ambient.measurement ) );
                        lastAmbientReading = ambient;
                    }
                }
            }
            catch( Exception _e ) {

                // by definition, any exception caught here is, well, exception!
                LOGGER.log( Level.SEVERE, "Caught unhandled, unexpected exception in TempReading", _e );
            }
        }
    }


    private void publishEvent( final SynchronousEvent _event ) {
        LOGGER.finest( "Published " + _event.toString() );
        SynchronousEvents.getInstance().publish( _event );
    }


    /**
     * <p>Reads temperature from the given SPI device, assuming a MAX31855 chip, returning the result from the chip.</p>
     *
     * @param _device the SPI device to read temperature from
     * @param _name the name of the temperature being read
     * @return the newly read temperature, or the type of fault if the temperature couldn't be read
     */
    private int getRaw( final SpiDevice _device, final String _name ) {

        try {

            // get a raw reading...
            // we write four bytes (which are ignored by the device), to read four...
            byte[] readData = _device.write( writeData );

            // if we didn't get at least four bytes, there's a problem...
            if( readData.length < 4 ) {
                LOGGER.log( Level.SEVERE, "Read temperature from " + _name + " got " + readData.length + " bytes, instead of 4" );
                return IO_ERROR_MASK;
            }

            // turn it into an integer...
            int rawReading = ((readData[0] & 0xff) << 24) | ((readData[1] & 0xff) << 16) |((readData[2] & 0xff) << 8) |(readData[3] & 0xff);

            LOGGER.finest( String.format( "Raw temperature read: %1$08x", rawReading ) );

            return rawReading;
        }

        // this is a major problem if we can't read the SPI device!
        catch( IOException _e ) {
            LOGGER.log( Level.SEVERE, "Error when reading SPI device " + _name, _e );
            return IO_ERROR_MASK;
        }
    }


    private static class TempReadTest extends ATestInjector<Integer> {

        private int testPattern;

        @Override
        public void set( final Object _testPattern ) {
            super.set( _testPattern );
            testPattern = Integer.decode( (String) _testPattern );
        }


        @Override
        public Integer inject( final Integer _int ) {
            return enabled ? testPattern | _int : _int;
        }
    }
}
