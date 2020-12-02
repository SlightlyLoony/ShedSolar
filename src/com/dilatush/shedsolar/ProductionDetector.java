package com.dilatush.shedsolar;

import com.dilatush.shedsolar.events.OutbackReading;
import com.dilatush.shedsolar.events.Weather;
import com.dilatush.util.Config;
import com.dilatush.util.syncevents.SubscribeEvent;
import com.dilatush.util.syncevents.SubscriptionDefinition;
import com.dilatush.util.syncevents.SynchronousEvents;
import org.shredzone.commons.suncalc.SunTimes;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.logging.Logger;

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


    /**
     * Creates a new instance of this class.  Also subscribes to the events we need to do the job.
     *
     * @param _config the app's configuration
     */
    public ProductionDetector( final Config _config ) {
        lat = _config.getDoubleDotted( "productionDetector.lat" );
        lon = _config.getDoubleDotted( "productionDetector.lon" );

        // subscribe to weather events...
        SynchronousEvents.getInstance().publish(
                new SubscribeEvent(
                        new SubscriptionDefinition( event -> handleWeatherEvent( (Weather) event ), Weather.class ) )
        );

        // subscribe to Outback data events...
        SynchronousEvents.getInstance().publish(
                new SubscribeEvent(
                        new SubscriptionDefinition( event -> handleOutbackReadingEvent( (OutbackReading) event ), OutbackReading.class )
                )
        );
    }


    /**
     * Handle the Outback reading event that the constructor subscribed us to.
     *
     * @param _event the Outback reading event
     */
    public void handleOutbackReadingEvent( final OutbackReading _event ) {
        LOGGER.finest( _event.toString() );
    }


    /**
     * Handle the weather event that the constructor subscribed us to.
     *
     * @param _event the weather event
     */
    public void handleWeatherEvent( final Weather _event ) {
        LOGGER.finest( _event.toString() );
    }


    /**
     * Handle the Outback reading event that the constructor subscribed us to.
     *
     * @param _event the Outback reading event
     */
    public void handleOutbackFailureEvent( final OutbackReading _event ) {
        LOGGER.finest( _event.toString() );
    }


    /**
     * Handle the weather event that the constructor subscribed us to.
     *
     * @param _event the weather event
     */
    public void handleWeatherFailureEvent( final Weather _event ) {
        LOGGER.finest( _event.toString() );
    }


    private Instant getSunrise( ZonedDateTime _date ) {
        SunTimes times = SunTimes.compute()
                .on( _date )
                .at( lat, lon )
                .execute();
        return Objects.requireNonNull( times.getRise() ).toInstant();
    }


    private Instant getSunset( ZonedDateTime _date ) {
        SunTimes times = SunTimes.compute()
                .on( _date )
                .at( lat, lon )
                .execute();
        return Objects.requireNonNull( times.getSet() ).toInstant();
    }
}
