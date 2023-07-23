package com.dilatush.shedsolar;

import com.dilatush.mop.PostOffice;
import com.dilatush.util.config.AConfig;
import com.dilatush.util.console.ConsoleServer;
import com.dilatush.util.test.TestManager;

import java.util.List;
import java.util.logging.Logger;

import static com.dilatush.util.Strings.isOneOf;

/**
 * Configuration POJO for the ShedSolar application.
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class Config extends AConfig {

    @SuppressWarnings( "unused" )
    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    /**
     * The mode to run ShedSolar in; the default is "normal".  There are three possibilities:<ul>
     * <li>"normal": the mode for normal operation</li>
     * <li>"assemblyTest": mode that does a simple test of all the hardware components</li>
     * <li>"tempTest": mode that runs a simple test of temperature measurement</li>
     * </ul>
     */
    public String                      mode               = "normal";
    public PostOffice.PostOfficeConfig cpo                = new PostOffice.PostOfficeConfig();
    public TempReader.Config           tempReader         = new TempReader.Config();
    public BatteryTempLED.Config       batteryTempLED     = new BatteryTempLED.Config();
    public Outbacker.Config            outbacker          = new Outbacker.Config();
    public LightDetector.Config        lightDetector      = new LightDetector.Config();
    public HeaterControl.Config        heaterControl      = new HeaterControl.Config();
    public TestManager.Config          testManager        = new TestManager.Config();
    public ConsoleServer.Config        consoleServer      = new ConsoleServer.Config();
    public DatabaseLogger.Config       databaseLogger     = new DatabaseLogger.Config();
    public StatusLED.Config            statusLED          = new StatusLED.Config();


    /**
     * Verify the fields of this configuration.
     */
    @Override
    public void verify( final List<String> _messages ) {

        validate( () -> isOneOf( mode, "normal", "tempTest", "assemblyTest"), _messages,
                "ShedSolar mode is invalid: " + mode );

        tempReader         .verify( _messages );
        cpo                .verify( _messages );
        batteryTempLED     .verify( _messages );
        outbacker          .verify( _messages );
        lightDetector      .verify( _messages );
        heaterControl      .verify( _messages );
        testManager        .verify( _messages );
        consoleServer      .verify( _messages );
        databaseLogger     .verify( _messages );
        statusLED          .verify( _messages );
    }
}
