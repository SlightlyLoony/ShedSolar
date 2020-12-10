package com.dilatush.shedsolar;

import com.dilatush.mop.Actor;
import com.dilatush.mop.Message;
import com.dilatush.mop.PostOffice;
import com.dilatush.shedsolar.events.CPOFailure;
import com.dilatush.shedsolar.events.Weather;
import com.dilatush.shedsolar.events.WeatherFailure;
import com.dilatush.util.test.ATestInjector;
import org.json.JSONException;

import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.dilatush.util.syncevents.SynchronousEvents.publishEvent;

/**
 * Provides an MOP actor that provides the mailbox "shedsolar.main" and listens for once-per-minute weather reports.  Additionally this class checks
 * to see that weather reports are actually received.  If they fail for some reason, a WeatherFailure event is published periodically.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class ShedSolarActor extends Actor {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private static final long MAX_WEATHER_REPORT_DELAY = 90000;
    private static final long DELAY_BETWEEN_FAILURE_EVENTS = 60 * 60000;

    private static final String mailboxName = "main";

    private volatile TimerTask     checker;
    private final    CheckerTester checkerTester;

    /**
     * Creates a new instance of this class, using the given post office and creating a mailbox with the given name.
     *
     * @param _po the post office to create an actor for
     */
    public ShedSolarActor( final PostOffice _po ) {
        super( _po, mailboxName );

        // make our testers...
        checkerTester = new CheckerTester();
        App.instance.orchestrator.registerTestInjector( checkerTester, "ShedSolarActor.checker" );

        // start our checker going (to see if we're receiving weather reports)...
        checker = new Checker();
        App.instance.timer.schedule( checker, MAX_WEATHER_REPORT_DELAY );

        // register our handlers...
        registerFQPublishMessageHandler( this::handleWeatherReport, "weather.weather", "minute", "report" );

        // subscribe to the messages we want to monitor...
        mailbox.subscribe( "weather.weather", "minute.report" );
    }


    /**
     * Handle the weather data message that we subscribed to in the constructor.  All we do here is to extract the information we need and publish
     * a Weather event containing it.
     *
     * @param _message the weather data message received
     */
    private void handleWeatherReport( final Message _message ) {

        try {
            // if we're testing the checker, just leave...
            if( checkerTester.inject( true ) )
                return;

            // cancel our checker...
            checker.cancel();

            // extract the data we want (irradiance and temperature) from the message...
            double solar = _message.getDouble( "solarIrradianceAvg" );
            double temp  = _message.getDouble( "temperatureAvg"     );
            LOGGER.finest( String.format( "Weather message, solar irradiance is %1$.0f watts/meter2, temperature is %2$.1fC", solar, temp ) );

            // send an event with this data...
            publishEvent( new Weather( solar, temp ) );

            // start a new checker...
            checker = new Checker();
            App.instance.timer.schedule( checker, MAX_WEATHER_REPORT_DELAY );
        }
        catch( JSONException _e ) {
            LOGGER.log( Level.SEVERE, "Problem extracting data from weather message: " + _e.getMessage(), _e );
        }
    }


    /**
     * An instance of this class is only executed if we fail to receive a weather data message within 90 seconds.
     */
    private class Checker extends TimerTask {

        /**
         * The action to be performed by this timer task.
         */
        @Override
        public void run() {

            // send a weather failure event...
            publishEvent( new WeatherFailure() );

            // this time wait longer before we send the next one...
            checker = new Checker();
            App.instance.timer.schedule( checker, DELAY_BETWEEN_FAILURE_EVENTS );

            LOGGER.info( "Failed to receive weather report" );

            // a little problem analysis here...
            // if we've lost connection to the post office, publish an event to that effect...
            if( !App.instance.po.isConnected() ) {
                publishEvent( new CPOFailure() );
            }

            // otherwise, try re-subscribing...
            else {
                mailbox.subscribe( "weather.weather", "minute.report" );
            }
        }
    }


    /**
     * Test injector to enable or disable testing weather data message failure.
     */
    private static class CheckerTester extends ATestInjector<Boolean> {

        @Override
        public Boolean inject( final Boolean _boolean ) {
            return enabled;
        }
    }
}
