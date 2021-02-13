package com.dilatush.shedsolar;

import com.dilatush.util.AConfig;
import com.dilatush.util.info.Info;
import com.dilatush.util.info.InfoView;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.dilatush.shedsolar.Events.*;

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

    // the information this class publishes...
    public final Info<TempSensorStatus>     batteryTemperatureSensorStatus;
    public final Info<Float>                batteryTemperature;
    public final Info<TempSensorStatus>     heaterTemperatureSensorStatus;
    public final Info<Float>                heaterTemperature;
    public final Info<Float>                ambientTemperature;

    // the setters for the published information...
    private Consumer<TempSensorStatus> batteryTemperatureSensorStatusSetter;
    private Consumer<Float>            batteryTemperatureSetter;
    private Consumer<TempSensorStatus> heaterTemperatureSensorStatusSetter;
    private Consumer<Float>            heaterTemperatureSetter;
    private Consumer<Float>            ambientTemperatureSetter;

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

    // our ShedSolar instance...
    private final ShedSolar shedSolar = ShedSolar.instance;


    // the Pi4J SPI device instances for each of our sensors...
    private final SpiDevice batteryTempSPI;
    private final SpiDevice heaterTempSPI;

    // four bytes of data to write when reading temperature...
    private final byte[] writeData = new byte[] { (byte) 0, (byte) 0, (byte) 0, (byte) 0 };

    private final NoiseFilter batteryFilter;
    private final NoiseFilter heaterFilter;

    // our test enabler...
    private final TestEnabler batteryRawTE;
    private final TestEnabler heaterRawTE;

    // schedule canceller...
    private ScheduledFuture<?> canceller;

    // our configuration...
    private final Config config;


    /**
     * Creates a new instance of this class, configured according to the given configuration file.
     *
     * @param _config the configuration file
     */
    public TempReader( final Config _config ) throws IOException {

        config = _config;

        // set up our information publishing...
        batteryTemperature             = new InfoView<>( (setter) -> batteryTemperatureSetter             = setter, false );
        batteryTemperatureSensorStatus = new InfoView<>( (setter) -> batteryTemperatureSensorStatusSetter = setter, false );
        heaterTemperature              = new InfoView<>( (setter) -> heaterTemperatureSetter              = setter, false );
        heaterTemperatureSensorStatus  = new InfoView<>( (setter) -> heaterTemperatureSensorStatusSetter  = setter, false );
        ambientTemperature             = new InfoView<>( (setter) -> ambientTemperatureSetter             = setter, false );
        batteryTemperatureSensorStatusSetter.accept( new TempSensorStatus() );
        heaterTemperatureSensorStatusSetter .accept( new TempSensorStatus() );

        // get our SPI devices...
        batteryTempSPI = SpiFactory.getInstance( SpiChannel.CS0, SpiDevice.DEFAULT_SPI_SPEED, SpiMode.MODE_1 );
        heaterTempSPI  = SpiFactory.getInstance( SpiChannel.CS1, SpiDevice.DEFAULT_SPI_SPEED, SpiMode.MODE_1 );

        // create our noise filters...
        batteryFilter = new NoiseFilter( config.noiseFilter );
        heaterFilter  = new NoiseFilter( config.noiseFilter );

        // create our test enablers...
        batteryRawTE = TestManager.getInstance().register( "batteryRaw" );
        heaterRawTE  = TestManager.getInstance().register( "heaterRaw"  );

        // schedule our temperature reader...
        canceller = ShedSolar.instance.scheduledExecutor.scheduleAtFixedRate( this::tempTask, Duration.ZERO, config.startupInterval );
    }


    /**
     * Runs periodically to measure our temperatures...
     */
    private void tempTask() {

        try {

            // handle the battery reading
            int rawBattery = readThermocoupleTemperature(
                    "Battery", batteryTempSPI, batteryTemperatureSensorStatusSetter, batteryTemperatureSensorStatus, batteryTemperatureSetter,
                    BATTERY_TEMPERATURE_SENSOR_UP, BATTERY_TEMPERATURE_SENSOR_DOWN, batteryFilter, batteryRawTE );

            // handle the heater reading
            int rawHeater = readThermocoupleTemperature(
                    "Heater", heaterTempSPI, heaterTemperatureSensorStatusSetter, heaterTemperatureSensorStatus, heaterTemperatureSetter,
                    HEATER_TEMPERATURE_SENSOR_UP, HEATER_TEMPERATURE_SENSOR_DOWN, heaterFilter, heaterRawTE );

            /*
             * Now handle the ambient temperature.  Both chips supply it; we use the battery temperature chip if it's got a good reading,
             * the heater temperature chip otherwise.  If neither is available, we simply don't report ambient temperature.
             */
            if( batteryTemperature.isInfoAvailable() )
                ambientTemperatureSetter.accept( ((rawBattery & COLD_JUNCTION_MASK) >> COLD_JUNCTION_OFFSET) / 16.0f );
            else if( heaterTemperature.isInfoAvailable() )
                ambientTemperatureSetter.accept( ((rawHeater  & COLD_JUNCTION_MASK) >> COLD_JUNCTION_OFFSET) / 16.0f );
            else
                ambientTemperatureSetter.accept( null );

            // switch the interval to the normal interval if we've started to get data...
            if( batteryTemperature.isInfoAvailable() && heaterTemperature.isInfoAvailable() && (canceller != null) ) {
                canceller.cancel( false );
                canceller = null;
                ShedSolar.instance.scheduledExecutor.scheduleAtFixedRate( this::tempTask, Duration.ZERO, config.normalInterval );
            }
        }

        // by definition, any exception caught here is, well, exceptional!
        catch( Exception _e ) {
            LOGGER.log( Level.SEVERE, "Unhandled exception when reading temperature", _e );
        }
    }


    /**
     * Reads the temperature from a thermocouple, returning the raw value read from the sensor.
     *
     * @param _name The name of the thermocouple being measured.
     * @param _device The SPI bus device being used to make the measurement.
     * @param _statusSetter The setter for the sensor status.
     * @param _sensorStatus The sensor status Info instance.
     * @param _tempSetter The setter for the temperature measurement.
     * @param _up The Hap to send if the sensor goes from down to up.
     * @param _down The Hap to send if the sensor goes from up to down.
     * @param _filter The NoiseFilter for this sensor.
     * @param _testEnabler The test enabler for this sensor.
     * @return The raw value from the sensor.
     */
    private int readThermocoupleTemperature( final String _name, final SpiDevice _device, final Consumer<TempSensorStatus> _statusSetter,
                                             final Info<TempSensorStatus> _sensorStatus, final Consumer<Float> _tempSetter, final Events _up,
                                             final Events _down, final NoiseFilter _filter, final TestEnabler _testEnabler ) {

        // first get the raw reading...
        int rawTemp = getRaw( _device, _name );

        // modify if testing is enabled...
        if( _testEnabler.isEnabled() )
            rawTemp |= _testEnabler.getAsInt( "mask" );

        // get the previous status...
        TempSensorStatus oldStatus = _sensorStatus.getInfo();

        // get the current status...
        TempSensorStatus newStatus = new TempSensorStatus(
                (rawTemp & FAULT_MASK) != 0,
                (rawTemp & IO_ERROR_MASK    ) != 0,
                (rawTemp & OPEN_MASK        ) != 0,
                (rawTemp & SHORT_TO_VCC_MASK) != 0,
                (rawTemp & SHORT_TO_GND_MASK) != 0
        );

        // if we've changed from problem to no problem, or vice versa, let the world know...
        if( oldStatus.sensorProblem ^ newStatus.sensorProblem )
            shedSolar.haps.post( newStatus.sensorProblem ? _down : _up );

        // if our status has changed, update the published info...
        if( newStatus.changed( oldStatus ) )
            _statusSetter.accept( newStatus );

        // if the sensor is working, get our data...
        if( !newStatus.sensorProblem ) {

            // add a sample to the filter...
            float temp = ((rawTemp & THERMOCOUPLE_MASK ) >> THERMOCOUPLE_OFFSET ) /  4.0f;
            LOGGER.finest( _name + " temperature read: " + temp );
            _filter.add( new Sample( temp, Instant.now() ) );

            // if we can, get a temperature sample and publish it...
            Sample sample = _filter.getFilteredAt( Instant.now() );
            _tempSetter.accept( (sample == null) ? null : sample.value );
        }

        // otherwise, store invalid data...
        else {
            _tempSetter.accept( null );
        }

        return rawTemp;
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


    /**
     * Validatable POJO for {@link TempReader} configuration (see {@link TempReader#TempReader(Config)}).
     */
    public static class Config extends AConfig {

        /**
         * The startup interval between temperature readings as a Duration.  Valid values are in the range [0.1 second .. 10 minutes].
         */
        public Duration startupInterval = Duration.ofMillis( 250 );

        /**
         * The normal interval between temperature readings as a duration.  Valid values are in the range of [5 seconds .. 60 seconds].  Because the
         * sensor noise has an observed periodicity of about 10 seconds, this value <i>should</i> be relatively prime to 10 seconds.
         */
        public Duration normalInterval = Duration.ofSeconds( 7 );

        /**
         * An instance of the class that implements {@link ErrorCalc}, for the noise filter.
         */
        public NoiseFilter.NoiseFilterConfig noiseFilter = new NoiseFilter.NoiseFilterConfig();


        /**
         * Verify the fields of this configuration.
         */
        @Override
        public void verify( final List<String> _messages ) {
            validate( () -> ((startupInterval != null)
                            && (Duration.ofMillis( 100 ).compareTo( startupInterval ) < 0)
                            && (Duration.ofMinutes( 10 ).compareTo( startupInterval ) > 0) ), _messages,
                    "Temperature Reader startup interval out of range: " + startupInterval.toMillis() + "ms" );
            validate( () -> ((normalInterval != null)
                            && (Duration.ofSeconds( 5  ).compareTo( normalInterval ) < 0)
                            && (Duration.ofMinutes( 60 ).compareTo( normalInterval ) > 0) ), _messages,
                    "Temperature Reader startup interval out of range: " + normalInterval.toMillis() + "ms" );
            noiseFilter.verify( _messages );
        }
    }


    /**
     * Simple immutable POJO that contains the status of a temperature sensor.
     */
    public static class TempSensorStatus {

        /**
         * {@code True} if the sensor has any kind of problem.
         */
        public final boolean sensorProblem;

        /**
         * {@code True} if there was an I/O error while attempting to read the sensor.
         */
        public final boolean ioError;

        /**
         * {@code True} if the connection to the thermocouple is open.
         */
        public final boolean open;

        /**
         * {@code True} if the connection to the thermocouple is shorted to Vcc.
         */
        public final boolean shortToVCC;

        /**
         * {@code True} if the connection to the thermocouple is shorted to ground.
         */
        public final boolean shortToGnd;


        /**
         * Create a new instance of this class with the given values.
         *
         * @param _sensorProblem {@code True} if the sensor has any kind of problem.
         * @param _ioError {@code True} if there was an I/O error while attempting to read the sensor.
         * @param _open {@code True} if the connection to the thermocouple is open.
         * @param _shortToVCC {@code True} if the connection to the thermocouple is shorted to Vcc.
         * @param _shortToGnd {@code True} if the connection to the thermocouple is shorted to ground.
         */
        public TempSensorStatus( final boolean _sensorProblem, final boolean _ioError, final boolean _open,
                                 final boolean _shortToVCC, final boolean _shortToGnd ) {

            sensorProblem = _sensorProblem;
            ioError = _ioError;
            open = _open;
            shortToVCC = _shortToVCC;
            shortToGnd = _shortToGnd;
        }


        /**
         * Create a new instance of this class with all fields set to {@code false}.
         */
        public TempSensorStatus() {
            sensorProblem = false;
            ioError = false;
            open = false;
            shortToVCC = false;
            shortToGnd = false;
        }


        // returns true if any field in this instance is different than a field in the given instance...
        private boolean changed( final TempSensorStatus _other ) {
            return (sensorProblem ^ _other.sensorProblem) || (ioError ^ _other.ioError) || (open ^ _other.open)
                    || (shortToVCC ^ _other.shortToVCC) || (shortToGnd ^ _other.shortToGnd);
        }
    }
}
