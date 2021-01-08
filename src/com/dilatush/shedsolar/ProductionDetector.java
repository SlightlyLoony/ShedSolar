package com.dilatush.shedsolar;

import com.dilatush.shedsolar.events.*;
import com.dilatush.util.AConfig;
import org.shredzone.commons.suncalc.SunTimes;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.dilatush.shedsolar.App.schedule;
import static com.dilatush.shedsolar.TemperatureMode.DORMANT;
import static com.dilatush.shedsolar.TemperatureMode.PRODUCTION;
import static com.dilatush.util.syncevents.SynchronousEvents.publishEvent;
import static com.dilatush.util.syncevents.SynchronousEvents.subscribeToEvent;

// TODO: enhance this to detect and handle cloudy days, snow-covered panels, etc.
// TODO: change to use panel power (In_V and In_I, multiplied) instead of threshold voltage...
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
     * @param _config the product detector's {@link Config}.
     */
    public ProductionDetector( final Config _config ) {

        lat                = _config.lat;
        lon                = _config.lon;
        pyrometerThreshold = _config.pyrometerThreshold;
        panelThreshold     = _config.panelThreshold;
        toProductionDelay  = _config.toProductionDelay;
        toDormantDelay     = _config.toDormantDelay;
        long interval      = _config.interval;

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


    public static class Config extends AConfig {

        /**
         * The latitude, in degrees, of the solar system's location.  This is used to calculate the sunrise and sunset times.  The value must be in
         * the range [-90..90].  There is no default value.
         */
        public double lat;

        /**
         * The longitude, in degrees, of the solar system's location.  This is used to calculate the sunrise and sunset times.  The value must be in
         * the range [-180..180].  There is no default value.
         */
        public double lon;

        /**
         * The pyrometer reading (in watts/square meter) threshold.  Values above the specified value indicate enough light for solar production.
         * The value must be in the range [0..1200]; the default value is 80.
         */
        public float pyrometerThreshold = 80;

        /**
         * The solar panel voltage threshold.  Values above the specified value indicate enough light for solar production.  The value must be in
         * the range [0..300]; the default value is 225.
         */
        public float panelThreshold = 225;

        /**
         * The interval (in milliseconds) that the production detector operates on; the "ticks" of its clock.  The value must be in the
         * range [10,000..600,000]; the default value is 60,000 (one minute).
         */
        public long interval = 60000;

        /**
         * The delay before switching from dormant to production mode, when adequate brightness has been detected, in "ticks" (see interval).  The
         * idea behind this delay is to avoid jumping to production mode if there's only a brief burst of light, like a hole in the clouds.  The
         * value must be in the range [0..120]; the default value is 5.
         */
        public int toProductionDelay = 5;

        /**
         * The delay before switching from production to dormant mode, when inadequate brightness has been detected, in "ticks" (see interval).  The
         * idea behind this delay is to avoid jumping to dormant mode if there's oly a brief interruption of light, like a cloud blocking the sun.
         * The value must be in the range [0..240]; the default value is 60.
         */
        public int toDormantDelay = 60;


        /**
         * Verify the fields of this configuration.
         */
        @Override
        public void verify( final List<String> _messages ) {
            validate( () -> ((lat >= -90) && (lat <= 90)), _messages,
                    "Production Detector latitude out of range: " + lat );
            validate( () -> ((lon >= -180) && (lon <= 180)), _messages,
                    "Production Detector longitude out of range: " + lat );
            validate( () -> ((pyrometerThreshold >= 0) && (pyrometerThreshold <= 1200)), _messages,
                    "Production Detector pyrometer watts/square meter threshold is out of range: " + pyrometerThreshold );
            validate( () -> ((panelThreshold >= 0) && (panelThreshold <= 300)), _messages,
                    "Production Detector solar panel voltage threshold is out of range: " + panelThreshold );
            validate( () -> ((interval >= 10000) && (interval <= 600000)), _messages,
                    "Production Detector 'tick' interval (in milliseconds) is out of range: " + interval);
            validate( () -> ((toProductionDelay >= 0) && (toProductionDelay <= 120)), _messages,
                    "Production Detector to production delay (in 'ticks') is out of range: " + toProductionDelay );
            validate( () -> ((toDormantDelay >= 0) && (toDormantDelay <= 240)), _messages,
                    "Production Detector to dormant delay (in 'ticks') is out of range: " + toDormantDelay );
        }
    }


    /**
     * The {@link Runnable} that does all the actual work of this class.
     */
    private class Detector  implements Runnable {

         @Override
        public void run() {

             try {
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
             catch( RuntimeException _e ) {
                 LOGGER.log( Level.SEVERE, "Unhandled exception in ProductionDetector.Detector", _e );
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
