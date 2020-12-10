package com.dilatush.shedsolar;

import com.dilatush.shedsolar.events.*;
import com.dilatush.util.Config;
import org.shredzone.commons.suncalc.SunTimes;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import static com.dilatush.shedsolar.App.schedule;
import static com.dilatush.shedsolar.TemperatureMode.DORMANT;
import static com.dilatush.shedsolar.TemperatureMode.PRODUCTION;
import static com.dilatush.util.syncevents.SynchronousEvents.publishEvent;
import static com.dilatush.util.syncevents.SynchronousEvents.subscribeToEvent;

/**
 * Keeps track of transitions between periods of possible solar production and periods of solar system dormancy, using information about solar panel
 * output voltage, weather station pyrometer (solar power), and computed sunrise and sunset times.  Note that these transitions can occur because of
 * daytime and nighttime, and also from cloudy daytime conditions.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class ProductionDetector {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private final double lat;
    private final double lon;
    private final float  pyrometerThreshold;
    private final float  panelThreshold;
    private final int    toProductionDelay;
    private final int    toDormantDelay;


    // these fields are set on the events thread, read on the timer thread...
    private volatile boolean weatherGood;
    private volatile boolean outbackGood;
    private volatile float   pyrometerPower;
    private volatile float   panelVoltage;
    private volatile float   panelCurrent;

    // these fields are set and read on the timer thread only...
    private int              minutesSinceChange;
    private TemperatureMode  lastMode;


    /**
     * Creates a new instance of this class.  Also subscribes to the events we need to do the job.
     *
     * @param _config the app's configuration
     */
    public ProductionDetector( final Config _config ) {

        lat                = _config.getDoubleDotted( "productionDetector.lat"                        );
        lon                = _config.getDoubleDotted( "productionDetector.lon"                        );
        pyrometerThreshold = _config.optFloatDotted(  "productionDetector.pyrometerThreshold",    80f );
        panelThreshold     = _config.optFloatDotted(  "productionDetector.panelThreshold",       225f );
        toProductionDelay  = _config.optIntDotted(    "productionDetector.toProductionDelay",      5  );
        toDormantDelay     = _config.optIntDotted(    "productionDetector.toDormantDelay",        60  );
        long interval      = _config.optLongDotted(   "productionDetector.intervalMS",         60000  );

        minutesSinceChange = 0;
        lastMode           = PRODUCTION;    // we assume production mode on startup, just to be safe...

        // subscribe to the events we need to listen to...
        subscribeToEvent( event -> handleWeatherEvent(        (Weather) event        ), Weather.class        );
        subscribeToEvent( event -> handleWeatherFailureEvent( (WeatherFailure) event ), WeatherFailure.class );
        subscribeToEvent( event -> handleOutbackReadingEvent( (OutbackReading) event ), OutbackReading.class );
        subscribeToEvent( event -> handleOutbackFailureEvent( (OutbackFailure) event ), OutbackFailure.class );

        // announce our default production mode...
        publishEvent( new TempMode( lastMode ) );

        // schedule our detector...
        schedule( new Detector(), interval, interval, TimeUnit.MILLISECONDS );
    }


    /**
     * The {@link TimerTask} that does all the actual work of this class.
     */
    private class Detector extends TimerTask {

         @Override
        public void run() {

            // get some calculated sunrise/sunset times...
            ZonedDateTime now     = ZonedDateTime.now();
            Instant todaySunrise  = getSunrise( now );
            Instant todaySunset   = getSunset( now );

            // this is what we're figuring out...
            TemperatureMode mode;

            // if we have no solar power data at all, then we'll rely exclusively on sunrise/sunset times...
            if( !(weatherGood || outbackGood) ) {

                mode = (now.toInstant().isAfter( todaySunrise ) && now.toInstant().isBefore( todaySunset )) ? PRODUCTION : DORMANT;
            }

            // otherwise, we'll incorporate the solar power data into our thinking...
            else {

                // if the current time is before sunrise or after sunset, then we're going hard dormant...
                if( now.toInstant().isBefore( todaySunrise ) || now.toInstant().isAfter( todaySunset )) {
                    mode = DORMANT;
                }

                // if it's daytime and we have pyrometer data, we'll use that, as it's simple and direct...
                else if( weatherGood ) {

                    mode = (pyrometerPower > pyrometerThreshold) ? PRODUCTION : DORMANT;
                }

                // otherwise, we'll use the solar panel data - voltage and current...
                else {
                    mode = ((panelCurrent > 0) && (panelVoltage > panelThreshold)) ? PRODUCTION : DORMANT;
                }
            }

            // if the last mode and this mode are the same, reset our minutes counter and leave...
            if( mode == lastMode ) {
                minutesSinceChange = 0;
                return;
            }

            // otherwise, bump our minutes counter and see if we're transitioning...
            minutesSinceChange++;
            if( (lastMode == PRODUCTION) && (minutesSinceChange >= toDormantDelay) ) {

                // it's time to transition to dormant mode...
                lastMode = DORMANT;
                minutesSinceChange = 0;
                publishEvent( new TempMode( lastMode ) );

            }
            else if( (lastMode == DORMANT) && (minutesSinceChange >= toProductionDelay) ) {

                // it's time to transition to production mode...
                lastMode = PRODUCTION;
                minutesSinceChange = 0;
                publishEvent( new TempMode( lastMode ) );
            }
        }
    }


    /**
     * Handle the Outback reading event that the constructor subscribed us to.
     *
     * @param _event the Outback reading event
     */
    private void handleOutbackReadingEvent( final OutbackReading _event ) {
        outbackGood = true;
        panelVoltage = (float) _event.outbackData.panelVoltage;
        panelCurrent = (float) _event.outbackData.panelCurrent;
        LOGGER.finest( _event.toString() );
    }


    /**
     * Handle the weather event that the constructor subscribed us to.
     *
     * @param _event the weather event
     */
    private void handleWeatherEvent( final Weather _event ) {
        weatherGood = true;
        pyrometerPower = (float) _event.irradiance;
        LOGGER.finest( _event.toString() );
    }


    /**
     * Handle the Outback reading event that the constructor subscribed us to.
     *
     * @param _event the Outback reading event
     */
    private void handleOutbackFailureEvent( final OutbackFailure _event ) {
        outbackGood = false;
        LOGGER.finest( _event.toString() );
    }


    /**
     * Handle the weather event that the constructor subscribed us to.
     *
     * @param _event the weather event
     */
    private void handleWeatherFailureEvent( final WeatherFailure _event ) {
        weatherGood = false;
        LOGGER.finest( _event.toString() );
    }


    /**
     * Returns the computed sunrise time at this instance's location on the given date.
     *
     * @param _date the date to compute sunrise time for
     * @return the computed sunrise time
     */
    private Instant getSunrise( ZonedDateTime _date ) {
        SunTimes times = SunTimes.compute()
                .on( _date )
                .at( lat, lon )
                .execute();
        return Objects.requireNonNull( times.getRise() ).toInstant();
    }


    /**
     * Returns the computed sunset time at this instance's location on the given date.
     *
     * @param _date the date to compute sunset time for
     * @return the computed sunset time
     */
    private Instant getSunset( ZonedDateTime _date ) {
        SunTimes times = SunTimes.compute()
                .on( _date )
                .at( lat, lon )
                .execute();
        return Objects.requireNonNull( times.getSet() ).toInstant();
    }
}
