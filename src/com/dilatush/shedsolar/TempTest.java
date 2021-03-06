package com.dilatush.shedsolar;

import com.dilatush.util.noisefilter.NoiseFilter;
import com.dilatush.util.noisefilter.Sample;
import com.pi4j.io.spi.SpiChannel;
import com.pi4j.io.spi.SpiDevice;
import com.pi4j.io.spi.SpiFactory;
import com.pi4j.io.spi.SpiMode;

import java.io.IOException;
import java.time.Instant;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Logger;

/**
 * This class implements a simple test to read temperature from an MX31855 chip, to see what the output pattern looks like.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class TempTest {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private SpiDevice batteryTemp;
    private NoiseFilter filter;
    private long count;
    private final Config config;


    public TempTest( final Config _config ) {
        config = _config;
    }


    public void run() {

        try {

            // setup our SPI channels...
            batteryTemp = SpiFactory.getInstance( SpiChannel.CS0, 1000000, SpiMode.MODE_1 );

            filter = new NoiseFilter( config.tempReader.noiseFilter );

            Timer timer = new Timer();
            timer.schedule( new Reader(), 250, 250 );

            //noinspection InfiniteLoopStatement
            while( true ) {
                //noinspection BusyWait
                Thread.sleep( 1000 );
            }
        }
        catch( InterruptedException | IOException _e ) {
            _e.printStackTrace();
        }
    }


    private final byte[] data = new byte[] { (byte) 0, (byte) 0, (byte) 0, (byte) 0 };

    private class Reader extends TimerTask {

        @Override
        public void run() {

            // we write four bytes (which are ignored by the device), to read four...
            try {

                byte[] readData = batteryTemp.write( data );

                float temp = (((byt(readData[0]) << 8) | byt(readData[1])) >> 2) / 4.0f;
                //LOGGER.info( String.format( "Temp: %1$6.2f", temp ) );
                Sample sample = new Sample( temp, Instant.now() );
                LOGGER.info( String.format( "Raw temperature: %1$.2f", sample.value ) );

                filter.add( sample );

                count++;

                if( count % 4 == 0 ) {
                    sample = filter.getFilteredAt(  Instant.now() );
                    if( sample != null ) {
                        LOGGER.info( String.format( "Filtered temperature: %1$.2f", sample.value ) );
                    }
                }
            }
            catch( IOException _e ) {
                _e.printStackTrace();
            }
        }
    }

    private int byt( final byte _b ) {
        return (int) _b & 0xFF;
    }
}

