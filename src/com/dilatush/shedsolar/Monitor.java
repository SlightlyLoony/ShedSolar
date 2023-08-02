package com.dilatush.shedsolar;

import com.dilatush.monitor.monitors.JVM;
import com.dilatush.mop.Actor;
import com.dilatush.mop.Message;
import com.dilatush.mop.PostOffice;
import com.dilatush.util.info.InfoSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implements a monitor that sends monitoring information about the operating system, the JVM, and the shed's solar system.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class Monitor extends Actor {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private final static String MAILBOX_NAME = "monitor";

    // some shortcut aliases...
    private final ShedSolar ss;


    public Monitor( final PostOffice _postOffice ) {
        super( _postOffice, MAILBOX_NAME );

        // set up our aliases...
        ss = ShedSolar.instance;

        // set up our JVM monitor...
        var params = new HashMap<String,Object>();
        params.put( "name", "shedsolar_shedsolar" );
        var jvm = new JVM( mailbox, params, Duration.ofMinutes( 10 ) );
        ss.scheduledExecutor.scheduleAtFixedRate( jvm, Duration.ofSeconds( 5 ), Duration.ofMinutes( 10 ) );

        // start our monitoring schedule, in a worker thread...
        ss.scheduledExecutor.scheduleAtFixedRate(
                () -> ss.executor.submit( this::monitor ),
                Duration.ofMinutes( 1 ),
                Duration.ofMinutes( 1 ) );
    }


    /**
     * The monitor task, which runs once per minute...
     */
    private void monitor() {

        // get our empty message...
        Message msg = mailbox.createPublishMessage( "shedsolar.monitor" );

        // run our monitors and fill in the info...
        fill( msg );

        // publish the message...
        mailbox.send( msg );

        LOGGER.log( Level.INFO, "Published monitor information" );
    }


    /**
     * Fill in the fields we're monitoring.
     *
     * @param _msg The message to fill in fields on.
     */
    private void fill( final Message _msg ) {

        // send the message interval...
        _msg.putDotted( "monitor.shedsolar.messageIntervalMs", Duration.ofMinutes( 1 ).toMillis() );

        // gather the information we're going to monitor (state of charge and battery temperature)...
        InfoSource<Float> batteryData       = ss.batteryTemperature.getInfoSource();
        InfoSource<Float> heaterData        = ss.heaterTemperature.getInfoSource();
        InfoSource<Float> ambientData       = ss.ambientTemperature.getInfoSource();
        InfoSource<Float> outsideData       = ss.outsideTemperature.getInfoSource();
        InfoSource<OutbackData> outbackData = ss.outback.getInfoSource();
        InfoSource<LightDetector.Mode> mode = ss.light.getInfoSource();

        // now fill in the message fields for which we have data...
        if( batteryData.isInfoAvailable() )
            _msg.putDotted( "monitor.shedsolar.batteryTemperature", batteryData.getInfo()                       );
        if( heaterData.isInfoAvailable() )
            _msg.putDotted( "monitor.shedsolar.heaterTemperature",  heaterData.getInfo()                        );
        if( ambientData.isInfoAvailable() )
            _msg.putDotted( "monitor.shedsolar.ambientTemperature", ambientData.getInfo()                       );
        if( outsideData.isInfoAvailable() )
            _msg.putDotted( "monitor.shedsolar.outsideTemperature", outsideData.getInfo()                       );
        if( mode.isInfoAvailable() )
            _msg.putDotted( "monitor.shedsolar.mode", mode.getInfo().toString()                                 );
        if( outbackData.isInfoAvailable() ) {
            var oi = outbackData.getInfo();
            _msg.putDotted( "monitor.shedsolar.soc",                   (float) oi.stateOfCharge                                 );
            _msg.putDotted( "monitor.shedsolar.batteryChargePower",    (float) oi.batteryChargePower                            );
            _msg.putDotted( "monitor.shedsolar.batteryDischargePower", (float) oi.batteryDischargePower                         );
            _msg.putDotted( "monitor.shedsolar.batteryNetPower",       (float) oi.batteryChargePower - oi.batteryDischargePower );
            _msg.putDotted( "monitor.shedsolar.inverterOutputPower",   (float) oi.inverterPower                                 );
            _msg.putDotted( "monitor.shedsolar.batteryVoltage",        (float) oi.batteryVoltage                                );
            _msg.putDotted( "monitor.shedsolar.solarPanelVoltage",     (float) oi.panelVoltage                                  );
        }
    }
}
