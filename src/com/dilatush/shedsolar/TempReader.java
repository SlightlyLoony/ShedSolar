package com.dilatush.shedsolar;

import com.dilatush.shedsolar.events.AmbientTemperatureEvent;
import com.dilatush.shedsolar.events.BatteryTemperatureEvent;
import com.dilatush.shedsolar.events.HeaterTemperatureEvent;
import com.dilatush.util.syncevents.SynchronousEvent;
import com.dilatush.util.syncevents.SynchronousEvents;
import com.dilatush.util.test.ATestInjector;
import com.pi4j.io.spi.SpiChannel;
import com.pi4j.io.spi.SpiDevice;
import com.pi4j.io.spi.SpiFactory;

import java.io.IOException;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Instances of this class implement a {@link TimerTask} that reads battery temperature, heater output temperature, and ambient shed temperature.
 * Results are reported by events.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class TempReader extends TimerTask {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private final static int THERMOCOUPLE_MASK    = 0xFFFC0000;
    private final static int THERMOCOUPLE_OFFSET  = 18;
    private final static int COLD_JUNCTION_MASK   = 0x0000FFF0;
    private final static int COLD_JUNCTION_OFFSET = 4;
    private final static int IO_ERROR_MASK        = 0x00020000;
    private final static int UNSTABLE_MASK        = 0x00000008;
    private final static int SHORT_TO_VCC_MASK    = 0x00000004;
    private final static int SHORT_TO_GND_MASK    = 0x00000002;
    private final static int OPEN_MASK            = 0x00000001;
    private final static int FAULT_MASK           = IO_ERROR_MASK | UNSTABLE_MASK | SHORT_TO_GND_MASK | SHORT_TO_VCC_MASK + OPEN_MASK;


    /**
     * The maximum number of sequential readings to take, without getting the minumum number of identical sequential readings, before throwing up
     * our hands in despair.
     */
    private final int maxRetries;

    /**
     * The minimum number of identical sequential readings before we believe the reading is correct.
     */
    private final int minStableReads;


    // the Pi4J SPI device instances for each of our sensors...
    private final SpiDevice batteryTemp;
    private final SpiDevice heaterTemp;

    // four bytes of data to write when reading temperature...
    private final byte[] writeData = new byte[] { (byte) 0, (byte) 0, (byte) 0, (byte) 0 };

    // the last stable raw readings, or -1 if no such reading...
    private int lastRawBatteryTemp;
    private int lastRawHeaterTemp;

    private final TempReadTest batteryTest = new TempReadTest();
    private final TempReadTest heaterTest = new TempReadTest();


    public TempReader( final int _maxRetries, final int _minStableReads ) throws IOException {

        maxRetries = _maxRetries;
        minStableReads = _minStableReads;

        // get our SPI devices...
        batteryTemp = SpiFactory.getInstance( SpiChannel.CS0, SpiDevice.DEFAULT_SPI_SPEED, SpiDevice.DEFAULT_SPI_MODE );
        heaterTemp  = SpiFactory.getInstance( SpiChannel.CS1, SpiDevice.DEFAULT_SPI_SPEED, SpiDevice.DEFAULT_SPI_MODE );

        // we have no last readings to start with...
        lastRawBatteryTemp = -1;
        lastRawHeaterTemp = -1;

        // register our tests...
        App.instance.orchestrator.registerTestInjector( batteryTest, "TempReader.readBattery" );
        App.instance.orchestrator.registerTestInjector( heaterTest,  "TempReader.readHeater" );
    }


    /**
     * The action to be performed by this timer task.
     */
    @Override
    public void run() {

        // get raw readings from both thermocouples...
        int rawBattery = batteryTest.inject( getRaw( batteryTemp, lastRawBatteryTemp, "Battery" ) );
        int rawHeater  = heaterTest.inject(  getRaw( heaterTemp,  lastRawHeaterTemp,  "Heater"  ) );

        // remember these readings for next time around...
        lastRawBatteryTemp = rawBattery;
        lastRawHeaterTemp = rawHeater;

        // get values read converted to degrees Celcius...
        float batteryTemp        = ((rawBattery & THERMOCOUPLE_MASK ) >> THERMOCOUPLE_OFFSET ) / 4.0f;
        float heaterTemp         = ((rawHeater  & THERMOCOUPLE_MASK ) >> THERMOCOUPLE_OFFSET ) / 4.0f;
        float batteryAmbientTemp = ((rawBattery & COLD_JUNCTION_MASK) >> COLD_JUNCTION_OFFSET) / 16.0f;
        float heaterAmbientTemp  = ((rawHeater  & COLD_JUNCTION_MASK) >> COLD_JUNCTION_OFFSET) / 16.0f;

        // handle the battery temperature event...
        boolean goodMeasurement = ((rawBattery & FAULT_MASK)        == 0);
        boolean unstable        = ((rawBattery & UNSTABLE_MASK)     != 0);
        boolean ioerror         = ((rawBattery & IO_ERROR_MASK)     != 0);
        boolean open            = ((rawBattery & OPEN_MASK)         != 0);
        boolean shortToVCC      = ((rawBattery & SHORT_TO_VCC_MASK) != 0);
        boolean shortToGnd      = ((rawBattery & SHORT_TO_GND_MASK) != 0);

        publishEvent( new BatteryTemperatureEvent( batteryTemp, goodMeasurement, unstable, ioerror, open, shortToGnd, shortToVCC ) );

        // handle the heater temperature event...
        goodMeasurement = ((rawHeater & FAULT_MASK)        == 0);
        unstable        = ((rawHeater & UNSTABLE_MASK)     != 0);
        ioerror         = ((rawHeater & IO_ERROR_MASK)     != 0);
        open            = ((rawHeater & OPEN_MASK)         != 0);
        shortToVCC      = ((rawHeater & SHORT_TO_VCC_MASK) != 0);
        shortToGnd      = ((rawHeater & SHORT_TO_GND_MASK) != 0);

       publishEvent( new HeaterTemperatureEvent( heaterTemp, goodMeasurement, unstable, ioerror, open, shortToGnd, shortToVCC ) );

        // handle the ambient temperature event...
        float ambientTemp = 0;
        int ambientCount = 0;
        if( (rawBattery & (IO_ERROR_MASK | UNSTABLE_MASK)) == 0 ) {
            ambientCount++;
            ambientTemp += batteryAmbientTemp;
        }
        if( (rawHeater  & (IO_ERROR_MASK | UNSTABLE_MASK)) == 0 ) {
            ambientCount++;
            ambientTemp += heaterAmbientTemp;
        }
        if( ambientCount >= 1 ) {
            ambientTemp /= ambientCount;
            publishEvent( new AmbientTemperatureEvent( ambientTemp ) );
        }
    }


    private void publishEvent( final SynchronousEvent _event ) {
        LOGGER.finest( "Published " + _event.toString() );
        SynchronousEvents.getInstance().publish( _event );
    }


    /**
     * Reads temperature from the given SPI device, assuming a MAX31855 chip, returning the result from the chip except that the unstable bit is
     * synthesized by this method.
     *
     * @param _device the SPI device to read temperature from
     * @param _lastRawTemp the last stable raw temperature read from the given device
     * @param _name the name of the temperature being read
     * @return the newly read temperature, or the type of fault if the temperature couldn't be read
     */
    private int getRaw( final SpiDevice _device, final int _lastRawTemp, final String _name ) {

        int lastRaw     = _lastRawTemp;        // the most recent raw temperature reading we got...
        int stableReads = minStableReads - 1;  // we start out assuming that the last stable temp was read twice in a row...
        int tries       = 0;                   // the total number of times we've tried reading this temperature...

        try {
            // while we've tried less than the maximum number of times...
            while( tries < maxRetries ) {

                tries++;

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

                // if it matches what we last read, then increment our stable read count; otherwise reset it...
                if( lastRaw == rawReading ) {
                    stableReads++;
                }
                else {
                    stableReads = 1;
                    lastRaw = rawReading;
                }

                // if we've met our minimum stability requirements, then leave with that...
                if( stableReads >= minStableReads ) {

                    LOGGER.finest( String.format( "Took %1$d tries to read temperature (%2$s)", tries, _name ) );

                    // if we took more than min+1 tries to get a stable reading, log it...
                    if( tries > minStableReads + 1 )
                        LOGGER.fine( "High number of reads required to get a stable reading: " + tries );
                    return lastRaw;
                }
            }

            // log it and return unstable result...
            LOGGER.log( Level.WARNING, "Unstable result from thermocouple interface", _name );
            return UNSTABLE_MASK;
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
