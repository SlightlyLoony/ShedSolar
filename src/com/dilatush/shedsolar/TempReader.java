package com.dilatush.shedsolar;

import com.dilatush.shedsolar.events.AmbientTemperature;
import com.dilatush.shedsolar.events.BatteryTemperature;
import com.dilatush.shedsolar.events.HeaterTemperature;
import com.dilatush.util.AConfig;
import com.dilatush.util.noisefilter.ErrorCalc;
import com.dilatush.util.noisefilter.NoiseFilter;
import com.dilatush.util.noisefilter.Sample;
import com.dilatush.util.test.TestEnabler;
import com.dilatush.util.test.TestManager;
import com.pi4j.io.spi.SpiChannel;
import com.pi4j.io.spi.SpiDevice;
import com.pi4j.io.spi.SpiFactory;
import com.pi4j.io.spi.SpiMode;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.dilatush.shedsolar.App.schedule;
import static com.dilatush.util.syncevents.SynchronousEvents.publishEvent;

/**
 * <p>Implements a temperature reader for two MAX31855 type K thermocouple interface chips, one for battery temperature and the other for
 * the heater output temperature.  The ambient temperature of the MAX31855 is also obtained as a side effect.</p>
 * <p>Reading these chips is far more challenging than one would expect.  The problem is that the thermocouple reading is occasionally anomalous,
 * for reasons that we've never been able to figure out.  Both parts that we used exhibit the same behavior.  Furthermore, if the chips are read
 * multiple times in quick succession, the anomalous reading persists.  Our solution is to read the temperatures at 500ms intervals and throw out
 * the outliers.  This is not as easy as it sounds!</p>
 * <p>Further observation of the MX31855 outputs showed that both of our boards had roughly 2 second long periods of anomalously low readings at
 * regular intervals of about 10 seconds.  We have no explanation for this, and can find no online discussion of it anywhere.  If we had known this
 * prior to building {@link NoiseFilter}, we'd probably have done something simpler.  But no matter, as {@link NoiseFilter} seems to handle the
 * issue just fine when configured with a function that is much more heavily weighted for delta
 * temperature than for delta time.</p>
 * <p>While temperatures are actually read twice a second, they are only reported (by events) upon change.  During startup, readings are taken for
 * 10 seconds (20 readings) to determine the baseline.</p>
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class TempReader {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    // These values are derived from the MAX31855 specification...
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
    private final SpiDevice batteryTempSPI;
    private final SpiDevice heaterTempSPI;

    // four bytes of data to write when reading temperature...
    private final byte[] writeData = new byte[] { (byte) 0, (byte) 0, (byte) 0, (byte) 0 };

    private final NoiseFilter batteryFilter;
    private final NoiseFilter heaterFilter;

    private final long  errorEventIntervalMS;

    private Sample     lastBatteryReading = null;
    private Sample     lastHeaterReading  = null;
    private Sample     lastAmbientReading = null;

    private Instant    lastBatteryErrorEvent = null;
    private Instant    lastHeaterErrorEvent  = null;

    private final TestEnabler batteryRawTE;
    private final TestEnabler heaterRawTE;


    /**
     * Creates a new instance of this class, configured according to the given configuration file.
     *
     * @param _config the configuration file
     */
    public TempReader( final Config _config ) throws IOException {

        // get our configuration parameters...
        errorEventIntervalMS = _config.errorEventIntervalMS;

        // get our SPI devices...
        batteryTempSPI = SpiFactory.getInstance( SpiChannel.CS0, SpiDevice.DEFAULT_SPI_SPEED, SpiMode.MODE_1 );
        heaterTempSPI  = SpiFactory.getInstance( SpiChannel.CS1, SpiDevice.DEFAULT_SPI_SPEED, SpiMode.MODE_1 );

        // create our noise filters...
        batteryFilter = new NoiseFilter( _config.noiseFilter );
        heaterFilter  = new NoiseFilter( _config.noiseFilter );

        // create our test enablers...
        batteryRawTE = TestManager.getInstance().register( "batteryRaw" );
        heaterRawTE  = TestManager.getInstance().register( "heaterRaw"  );

        // schedule our temperature reader...
        schedule( new TempReaderTask(), 0, _config.intervalMS, TimeUnit.MILLISECONDS );
    }


    /**
     * Validatable POJO for {@link TempReader} configuration (see {@link TempReader#TempReader(Config)}).
     */
    public static class Config extends AConfig {

        /**
         * The interval between temperature readings, in milliseconds.  Valid values are in the range [100..600,000] (0.1 second to 10 minutes).
         */
        public  long intervalMS = 250;

        /**
         * The interval between error events, in milliseconds.  Valid values are in the range [intervalMS..600,000].
         */
        public  long errorEventIntervalMS = 10000;

        /**
         * An instance of the class that implements {@link ErrorCalc}, for the noise filter.
         */
        public NoiseFilter.NoiseFilterConfig noiseFilter = new NoiseFilter.NoiseFilterConfig();


        /**
         * Verify the fields of this configuration.
         */
        @Override
        public void verify( final List<String> _messages ) {
            validate( () -> ((intervalMS >= 100) && (intervalMS <= 1000 * 60 * 10)), _messages,
                    "Temperature Reader interval out of range: " + intervalMS );
            validate( () -> ((errorEventIntervalMS >= intervalMS) && (errorEventIntervalMS <= 1000 * 60 * 10)), _messages,
                    "Temperature Reader error event interval is out of range: " + errorEventIntervalMS );
            noiseFilter.verify( _messages );
        }
    }


    /**
     * The {@link Runnable} that does all the work of this class.
     */
    private class TempReaderTask implements Runnable {

        @Override
        public void run() {

            try {

                /* handle the battery reading... */
                // first get the raw reading...
                int rawBattery = getRaw( batteryTempSPI, "Battery" );

                // modify if testing is enabled...
                if( batteryRawTE.isEnabled() )
                    rawBattery |= batteryRawTE.getAsInt( "mask" );

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
                    LOGGER.finest( "Battery temperature read: " + batteryTemp );
                    batteryFilter.add( new Sample( batteryTemp, Instant.now() ) );

                    // get a temperature sample, if we can...
                    Sample sample = batteryFilter.getFilteredAt( Instant.now() );

                    // if we got a sample, and it's different than our last sample, send an event...
                    if( (sample != null) && ((lastBatteryReading == null) || (sample.value != lastBatteryReading.value)) ) {
                        publishEvent( new BatteryTemperature( sample.value, true, false, false, false, false ) );
                        lastBatteryReading = sample;
                    }
                }

                /* handle the heater reading... */
                // first get the raw reading...
                 int rawHeater  = getRaw( heaterTempSPI,  "Heater" );

                // modify if testing is enabled...
                if( heaterRawTE.isEnabled() )
                    rawHeater |= heaterRawTE.getAsInt( "mask" );

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
                    LOGGER.finest( "Heater temperature read: " + heaterTemp );
                    heaterFilter.add( new Sample( heaterTemp, Instant.now() ) );

                    // get a temperature reading, if we can...
                    Sample reading = heaterFilter.getFilteredAt( Instant.now() );

                    // if we got a reading, and it's different than our last reading, send an event...
                    if( (reading != null) && ((lastHeaterReading == null) || (reading.value != lastHeaterReading.value)) ) {
                        publishEvent( new HeaterTemperature( reading.value, true, false, false, false, false ) );
                        if( reading.value < 20f )
                            LOGGER.finest( heaterFilter.toString() );
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

                // round our ambient temperature to the nearest quarter degree, to avoid flooding the zone with ambient temperature change events...
                ambientTemp = Math.round( ambientTemp * 4 ) / 4f;

                // if we don't have a NaN, then we have a temperature...
                if( !Float.isNaN( ambientTemp ) ) {
                    Sample ambient = new Sample( ambientTemp, Instant.now() );
                    if( (lastAmbientReading == null) || (lastAmbientReading.value != ambient.value) ) {
                        publishEvent( new AmbientTemperature( ambient.value ) );
                        lastAmbientReading = ambient;
                    }
                }
            }
            catch( RuntimeException _e ) {

                // by definition, any exception caught here is, well, exceptional!
                LOGGER.log( Level.SEVERE, "Unhandled exception in TempReading", _e );
            }
        }
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
}
