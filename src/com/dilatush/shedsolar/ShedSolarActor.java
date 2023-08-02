package com.dilatush.shedsolar;

import com.dilatush.mop.Actor;
import com.dilatush.mop.Message;
import com.dilatush.mop.PostOffice;
import com.dilatush.util.info.Info;
import com.dilatush.util.info.InfoView;
import org.json.JSONException;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.dilatush.util.Conversions.fromFtoC;

/**
 * Provides an MOP actor that provides the mailbox "shedsolar.main" and listens for once-per-minute weather reports.  Additionally, this class checks
 * to see that weather reports are actually received.  If they fail for some reason, a WeatherFailure event is published periodically.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class ShedSolarActor extends Actor {

    // our published data...
    public final Info<Float> solarIrradiance;
    public final Info<Float> outsideTemperature;

    // our setters...
    private Consumer<Float> solarIrradianceSetter;
    private Consumer<Float> outsideTemperatureSetter;

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private static final long MAX_WEATHER_REPORT_DELAY = 150000;

    private static final String mailboxName = "main";

    private volatile ScheduledFuture<?> checker;

    /**
     * Creates a new instance of this class, using the given post office and creating a mailbox with the given name.
     *
     * @param _po the post office to create an actor for
     */
    public ShedSolarActor( final PostOffice _po ) {
        super( _po, mailboxName );

        // set up our published data...
        solarIrradiance = new InfoView<>( (setter) -> solarIrradianceSetter = setter, false );
        outsideTemperature = new InfoView<>( (setter) -> outsideTemperatureSetter = setter, false );

        // start our checker going (to see if we're receiving weather reports)...
        startChecker();

        // register our handlers...
        registerFQPublishMessageHandler( this::handleWeatherReport, "weather.weather", "minute", "report" );
        registerPublishMessageHandler(   this::handleYoLinkReport,  "yolink", "monitor" );

        // subscribe to the messages we want to monitor...
        mailbox.subscribe( "beast.monitor",   "yolink.monitor" );
        mailbox.subscribe( "weather.weather", "minute.report"  );
    }


    private void startChecker() {
        checker = ShedSolar.instance.scheduledExecutor.schedule( this::check, Duration.ofMillis( MAX_WEATHER_REPORT_DELAY ) );
    }


    private void handleYoLinkReport( final Message _message ) {
        var deckTemp     = _message.getFloatDotted( "monitor.yolink.sensors.Deck.temperature" );
        var pinesTemp    = _message.getFloatDotted( "monitor.yolink.sensors.Pines.temperature" );
        var shedDoorTemp = _message.getFloatDotted( "monitor.yolink.sensors.Shed Door.temperature" );
        var avgTemp = (deckTemp + pinesTemp + shedDoorTemp ) / 3.0f;
        registerOutsideTemp( (float) fromFtoC( deckTemp ),     15 );
        registerOutsideTemp( (float) fromFtoC( pinesTemp ),    15 );
        registerOutsideTemp( (float) fromFtoC( shedDoorTemp ), 15 );
        LOGGER.info( String.format( "YoLink message: average outside temperature is %.1fC", fromFtoC( avgTemp ) ));
    }


    /**
     * Handle the weather data message that we subscribed to in the constructor.  All we do here is to extract the information we need and publish
     * a Weather event containing it.
     *
     * @param _message the weather data message received
     */
    private void handleWeatherReport( final Message _message ) {

        try {

            // cancel our checker...
            checker.cancel( true );

            // extract the data we want (irradiance and temperature) from the message...
            float solar = _message.getFloat( "solarIrradianceAvg" );
            float temp  = _message.getFloat( "temperatureAvg"     );
            LOGGER.info( String.format( "Weather message: solar irradiance is %1$.0f watts/meter2, temperature is %2$.1fC", solar, temp ) );

            // publish our data...
            solarIrradianceSetter.accept( solar );
            registerOutsideTemp( temp, 1 );

            // start a new checker...
            startChecker();
        }
        catch( JSONException _e ) {
            LOGGER.log( Level.SEVERE, "Problem extracting data from weather message: " + _e.getMessage(), _e );
        }
    }


    private void registerOutsideTemp( final float _temp, final int _minutes ) {

        // if we have no temperature yet, just set it with the value we've just received...
        if( !outsideTemperature.isInfoAvailable() ) {
            outsideTemperatureSetter.accept( _temp );
            return;
        }

        // otherwise, calculate our new average...
        var temp = outsideTemperature.getInfo();
        temp = (((60 - _minutes) * temp) + (_minutes * _temp)) / 60.0f;
        outsideTemperatureSetter.accept( temp );
    }


    private void check() {

        // send a weather failure event...
        ShedSolar.instance.haps.post( Events.WEATHER_REPORT_MISSED );
        LOGGER.info( "Failed to receive weather report" );

        // check again...
        startChecker();

        // try re-subscribing...
        mailbox.subscribe( "weather.weather", "minute.report" );
    }
}
