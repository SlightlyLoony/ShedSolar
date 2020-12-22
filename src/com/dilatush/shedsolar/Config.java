package com.dilatush.shedsolar;

import com.dilatush.mop.PostOffice;
import com.dilatush.util.AConfig;

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
    public ProductionDetector.Config   productionDetector = new ProductionDetector.Config();
    public HeaterControl.Config        heaterControl      = new HeaterControl.Config();


    /**
     * Verify the fields of this configuration.
     */
    @Override
    protected void verify() {
        validate( () -> isOneOf( mode, "normal", "tempTest", "assemblyTest"),
                "ShedSolar mode is invalid: " + mode );
        valid = valid && tempReader.isValid();
        valid = valid && cpo.isValid();
        valid = valid && batteryTempLED.isValid();
        valid = valid && outbacker.isValid();
        valid = valid && productionDetector.isValid();
        valid = valid && heaterControl.isValid();
    }
}
