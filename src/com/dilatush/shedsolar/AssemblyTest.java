package com.dilatush.shedsolar;

import com.pi4j.io.gpio.*;
import com.pi4j.io.spi.SpiChannel;
import com.pi4j.io.spi.SpiDevice;
import com.pi4j.io.spi.SpiFactory;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.lang.Thread.sleep;

/**
 * This class exercises all the input/output of the fully assembled hardware to verify correct assembly and function.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
@SuppressWarnings("InfiniteLoopStatement")
public class AssemblyTest {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    @SuppressWarnings("BusyWait")
    public void run() {

        try {
            // set up our GPIO pins...
            GpioController controller = GpioFactory.getInstance();

            // the SSR sense pin...
            GpioPinDigitalInput ssrSense = controller.provisionDigitalInputPin( RaspiPin.GPIO_00, "SSR Sense", PinPullResistance.PULL_UP );
            ssrSense.setShutdownOptions( true, PinState.HIGH, PinPullResistance.OFF, PinMode.DIGITAL_INPUT );

            // the LEDs...
            GpioPinDigitalOutput batteryTempLED = controller.provisionDigitalOutputPin( RaspiPin.GPIO_02, "Battery Temperature LED", PinState.HIGH );
            batteryTempLED.setShutdownOptions( true, PinState.HIGH );
            GpioPinDigitalOutput heaterPowerLED = controller.provisionDigitalOutputPin( RaspiPin.GPIO_03, "Heater Power LED", PinState.HIGH );
            heaterPowerLED.setShutdownOptions( true, PinState.HIGH );
            GpioPinDigitalOutput statusLED = controller.provisionDigitalOutputPin( RaspiPin.GPIO_04, "Status LED", PinState.HIGH );
            statusLED.setShutdownOptions( true, PinState.HIGH );

            // the heater's solid state relay...
            GpioPinDigitalOutput heaterSSR = controller.provisionDigitalOutputPin( RaspiPin.GPIO_05, "Heater SSR", PinState.HIGH );
            heaterSSR.setShutdownOptions( true, PinState.HIGH );

            // setup our SPI channels...
            SpiDevice batteryTemp = SpiFactory.getInstance( SpiChannel.CS0, SpiDevice.DEFAULT_SPI_SPEED, SpiDevice.DEFAULT_SPI_MODE );
            SpiDevice heaterTemp  = SpiFactory.getInstance( SpiChannel.CS1, SpiDevice.DEFAULT_SPI_SPEED, SpiDevice.DEFAULT_SPI_MODE );

            // now we just loop forever, exercising everything...
            long count = 0;
            while( true ) {

                // set our lights, incrementing in binary at 1 Hz...
                batteryTempLED.setState( (count & 0x01) == 0 );
                heaterPowerLED.setState( (count & 0x02) == 0 );
                statusLED.setState(      (count & 0x04) == 0 );

                // turn the heater on and off every 32 seconds...
                heaterSSR.setState( (count & 0x20) == 0 );

                // read temperatures and SSR sense every 4 seconds...
                if( count % 4 == 0 ) {

                    // first our temperatures...
                    TempResult br = readTemp( batteryTemp );
                    TempResult hr = readTemp( heaterTemp );
                    float ar = (br.referenceTemp + hr.referenceTemp) / 2.0f;

                    // then the SSR sense...
                    boolean ssr = ssrSense.isLow();

                    // log what we found...
                    LOGGER.info( String.format( "SSR: %1$1B %2$.2f %3$.2f %4$.4f", ssr, br.thermocoupleTemp, hr.thermocoupleTemp, ar ) );
                    if( hr.fault || br.fault ) {
                        LOGGER.info( "Faults: Battery: " + br.fault + " " + br.scvFault + " " + br.scgFault + " " + br.ocFault +
                                     " Heater: " + hr.fault + " " + hr.scvFault + " " + hr.scgFault + " " + hr.ocFault );
                    }
                }

                // wait a tick...
                sleep( 1000 );
                count++;
            }
        }
        catch( Exception _e ) {
            LOGGER.log( Level.WARNING, "Unexpected exception", _e );
            System.exit(1);
        }
    }

    private final byte[] data = new byte[] { (byte) 0, (byte) 0, (byte) 0, (byte) 0, (byte) 0 };

    private TempResult readTemp( SpiDevice _spi ) throws IOException {

        // we write four bytes (which are ignored by the device), to read four...
        byte[] readData = _spi.write( data );

        // if we didn't get at least four bytes, there's a problem...
        if( readData.length < 4 ) {
            throw new IllegalStateException( "Read temperature got " + readData.length + " bytes, instead of 4" );
        }

        // get our result...
        TempResult result = new TempResult();
        int ref = (((readData[2] << 8) | (0xFF & readData[3])) >> 4);
        int tc =  (((readData[0] << 8) | (0xFF & readData[1])) >> 2);
        result.thermocoupleTemp = tc / 4.0f;
        result.referenceTemp = ref / 16.0f;
        result.fault    = (readData[1] & 0x01) != 0;
        result.scvFault = (readData[3] & 0x04) != 0;
        result.scgFault = (readData[3] & 0x02) != 0;
        result.ocFault  = (readData[3] & 0x01) != 0;

        LOGGER.info( bytesToString( readData ) );

        return result;
    }


    private String bytesToString( final byte[] _bytes ) {
        StringBuilder sb = new StringBuilder();
        for( byte thisByte : _bytes ) {
            if( sb.length() > 0 )
                sb.append( ' ' );
            sb.append( String.format( "%02X", thisByte ) );
        }
        return sb.toString();
    }


    private static class TempResult {
        private float thermocoupleTemp;
        private float referenceTemp;
        private boolean fault;
        private boolean scvFault;
        private boolean scgFault;
        private boolean ocFault;
    }
}
