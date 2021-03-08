package com.dilatush.shedsolar;

import com.dilatush.mop.Actor;
import com.dilatush.mop.Message;
import com.dilatush.mop.PostOffice;
import com.dilatush.mop.util.JVMMonitor;
import com.dilatush.mop.util.OSMonitor;
import com.dilatush.util.info.InfoSource;

import java.time.Duration;
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

    private final OSMonitor osMonitor;
    private final JVMMonitor jvmMonitor;

    // some shortcut aliases...
    private final ShedSolar ss;


    public Monitor( final PostOffice _postOffice ) {
        super( _postOffice, MAILBOX_NAME );

        // set up our aliases...
        ss = ShedSolar.instance;

        // set up our monitors for the OS and the JVM...
        osMonitor = new OSMonitor();
        jvmMonitor = new JVMMonitor();

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
        osMonitor.fill( msg );
        jvmMonitor.fill( msg );
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

        // gather the information we're going to monitor (state of charge and battery temperature)...
        InfoSource<Float> batteryData       = ss.batteryTemperature.getInfoSource();
        InfoSource<OutbackData> outbackData = ss.outback.getInfoSource();
        boolean batteryAvailable            = batteryData.isInfoAvailable();
        boolean socAvailable                = outbackData.isInfoAvailable();
        float batteryTemp                   = batteryAvailable ? batteryData.getInfo() : 0;
        float soc                           = socAvailable ? (float) outbackData.getInfo().stateOfCharge : 0;

        // now fill in the message fields...
        _msg.putDotted( "monitor.shedsolar.batteryAvailable",   batteryAvailable );
        _msg.putDotted( "monitor.shedsolar.socAvailable",       socAvailable     );
        _msg.putDotted( "monitor.shedsolar.batteryTemperature", batteryTemp      );
        _msg.putDotted( "monitor.shedsolar.soc",                soc              );
    }
}
