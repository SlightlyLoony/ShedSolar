/*
 * ShedSolar configuration.
 */

// load our secret keys from a secure location...
load( "/apps/shedsolar/key.js" );     // the console server shared secret...
load( "/apps/shedsolar/secret.js" );  // the CPO shared secret...


/*
 * Helper constructors and functions for test framework.
 */

// create constructor functions for some useful classes...
var HashMap     = Java.type( "java.util.HashMap" );
var ArrayList   = Java.type( "java.util.ArrayList" );
var InetAddress = Java.type( "java.net.InetAddress" );
var Duration    = Java.type( "java.time.Duration" );

// returns a HashMap with the properties of the given JavaScript object
function makeMap( obj ) {
    var map = new HashMap();
    for( prop in obj ) {
        if( obj.hasOwnProperty( prop ) )
            map.put( prop, obj[prop] );
    }
    return map;
}

// returns an ArrayList with the values of the given JavaScript array
function makeList( array ) {
    var list = new ArrayList();
    array.forEach( function( item ) {list.add( item ); });
    return list;
}


// returns a Java TestEnabler of the given name, with the properties in the given JavaScript object
function makeEnabler( name, properties ) {

    // if name contains a period, assume it's a fully qualified type name; otherwise assume it's "com.dilatush.util.test.???TestEnabler",
    // where "???" is this name...
    var typeName = (name.indexOf( "." ) >= 0 )? name : "com.dilatush.util.test." + name + "TestEnabler";

    var props = makeMap( properties );

    return new (Java.type( typeName ) )( props );
}


// returns a Java CompositeTestEnabler with the properties in the given JavaScript object, and containing the Java test enablers in the given
// JavaScript array
function makeComposite( properties, enablers ) {
    var props = makeMap( properties );
    var enablerList = makeList( enablers );
    return new (Java.type( "com.dilatush.util.test.CompositeTestEnabler" ))( props, enablerList );
}


function makeJavaScript( properties, script ) {
    var props = makeMap( properties );
    return new (Java.type( "com.dilatush.util.test.JavaScriptTestEnabler" ) )( props, script );
}


/*
 * Our actual configuration initialization function.
 */
function init( config ) {


    /*
     * CPO (Central Post Office) configuration.
     */

    // The name of this post office, which must be a string with one or more characters, unique amongst all post offices connected to the specified CPO.
    config.cpo.name = "shedsolar";

    // The secret that encrypts messages from this post office.  This is a string that must exactly match the one for this post office that has been
    // configured at the specified CPO.
    config.cpo.secret = secret;

    // The number of received messages that may be queued up before processing, in each mailbox on this post office.  This defaults to 100 if not
    // specified here.
    config.cpo.queueSize = 100;

    // The fully qualified host name (or IP address) for the central post office.
    config.cpo.cpoHost = "cpo.dilatush.com";

    // The TCP port number for the central post office.  This defaults to 4000 if not specified here.
    config.cpo.cpoPort = 4000;


    /*
     * Temperature Reader configuration.
     */

    // The startup interval between temperature readings as a Duration.  Valid values are in the range [0.1 second .. 10 minutes].  The default
    // value is 250 ms.
    config.tempReader.startupInterval = Duration.ofMillis( 250 );

    // The normal interval between temperature readings as a duration.  Valid values are in the range of [5 seconds .. 60 seconds].  Because the
    // sensor noise has an observed periodicity of about 10 seconds, this value SHOULD be relatively prime to 10 seconds.  The default value is
    // 7 seconds.
    config.tempReader.normalInterval = Duration.ofSeconds( 7 );

    // The number of samples (history) to keep in the filter.  Valid values are 2 or greater.
    config.tempReader.noiseFilter.numSamples = 41;

    // An instance of the class that implements ErrorCalc, for the noise filter.
    config.tempReader.noiseFilter.errorCalc = new (Java.type( 'com.dilatush.util.noisefilter.MedianErrorCalc' ))();

    // The maximum number of samples that may be ignored by this filter, as a fraction of the number of samples.  Valid values
    // are in the range [0..1].
    config.tempReader.noiseFilter.maxIgnoreFraction = 0.25;

    // The maximum total error of the samples that may be ignored by this filter, as a fraction of the total error of all the samples
    // in this filter.  Valid values are in the range of [0..1].
    config.tempReader.noiseFilter.maxTotalErrorIgnoreFraction = 1.0;

    // The minimum value of the error for any sample to be ignored.  This value must be non-negative.
    config.tempReader.noiseFilter.minSampleErrorIgnore = 0.75;  // in degree C


    /*
     * Battery temperature LED configuration.
     */

    // The minimum battery temperature (in degrees Celcius) that can be shown by the battery temperature LED (when the LED is solid off).  This value
    // defaults to 0C, and must be less than the value of config.batteryTempLED.maxTemp.
    config.batteryTempLED.minTemp = 0.0;

    // The maximum battery temperature (in degrees Celcius) that can be shown by the battery temperature LED (when the LED is solidly on).  This
    // value defaults to 45C, and must be greater than the value of config.batteryTempLED.minTemp.
    config.batteryTempLED.maxTemp = 45.0;

    // The interval, in milliseconds, between the start of each normal flash of the battery temperature LED, indicating that the battery
    // temperature can be read.  This value must be in the range [500..5000], and must be at least twice config.batteryTempLED.errorInterval.
    config.batteryTempLED.normalInterval = 2000;

    // The interval, in milliseconds, between the start of each error flash of the battery temperature LED, indicating that the battery
    // temperature cannot be read.  This value must be in the range [250..2500], and must be less than half config.batteryTempLED.normalInterval.
    config.batteryTempLED.errorInterval = 400;


    /*
     * Outback interrogator configuration.
     */

    // The host or IP address for the Outback Mate3S supervisor on the charger/inverter.
    config.outbacker.host = "outback-solar.dilatush.com";

    // The interval between interrogations of the Outback Mate3S, in milliseconds.  This value must be greater than 30,000; it defaults to 60,000.
    config.outbacker.interval = 60000;


    /*
     * Light Detector configuration.
     */

    // The latitude, in degrees, of the solar system's location.  This is used to calculate the sunrise and sunset times.  The value must be in
    // the range [-90..90].  There is no default value.
    config.lightDetector.lat = 41.582777;

    // The longitude, in degrees, of the solar system's location.  This is used to calculate the sunrise and sunset times.  The value must be in
    // the range [-180..180].  There is no default value.
    config.lightDetector.lon = -111.839533;

    // The maximum state of charge (SOC) for the batteries to use solar panel power production to indicate enough light for solar production.
    config.lightDetector.socThreshold = 98;

    // The interval (in milliseconds) that the production detector operates on; the "ticks" of its clock.  The value must be in the
    // range [10,000..600,000]; the default value is 60,000 (one minute).
    config.lightDetector.interval = 60000;

    // The pyrometer reading (in watts/square meter) threshold.  Values above the specified value indicate enough light for solar production.
    // The value must be in the range [0..1200]; the default value is 225.
    config.lightDetector.pyrometerThreshold = 200;

    // The solar panel power threshold.  Values above the specified value indicate enough light for solar production.  The value must be in
    // the range [0..10000]; the default value is 200.
    config.lightDetector.panelThreshold = 200;

    // The delay before switching from dormant to production mode, when adequate brightness has been detected, in "ticks" (see interval).  The
    // idea behind this delay is to avoid jumping to production mode if there's only a brief burst of light, like a hole in the clouds.  The
    // value must be in the range [0..120]; the default value is 5.
    config.lightDetector.toProductionDelay = 5;

    // The delay before switching from production to dormant mode, when inadequate brightness has been detected, in "ticks" (see interval).  The
    // idea behind this delay is to avoid jumping to dormant mode if there's oly a brief interruption of light, like a cloud blocking the sun.
    // The value must be in the range [0..240]; the default value is 60.
    config.lightDetector.toDormantDelay = 60;


    /*
     * Heater Control configuration.
     */

    // The time (in milliseconds) between "ticks" of the heater control state machine.  This value must be in the range [1,000..15,000]
    // milliseconds, and the default value is 5,000 milliseconds (five seconds).
    config.heaterControl.tickTime = 5000;  // 5 seconds

    // The lowest battery temperature (in degrees Celcius) allowed when in dark mode.  This value must be in the range [-10..25], and it must
    // be less than darkHighTemp and less than lightLowTemp.  Its default value is 0C.
    config.heaterControl.darkLowTemp = 0;

    // The highest battery temperature (in degrees Celcius) allowed when in dark mode.  This value must be in the range [-10..25], and it must
    // be greater than darkLowTemp and less than lightHighTemp.  Its default value is 5C.
    config.heaterControl.darkHighTemp = 5;

    // The lowest battery temperature (in degrees Celcius) allowed when in light mode.  This value must be in the range [0..40], and it must
    // be less than lightHighTemp and greater than darkLowTemp.  Its default value is 25C.
    config.heaterControl.lightLowTemp = 25;

    // The highest battery temperature (in degrees Celcius) allowed when in light mode.  This value must be in the range [0..40], and it must
    // be greater than lightLowTemp and greater than darkHighTemp.  It's default value is 30C.
    config.heaterControl.lightHighTemp = 30;

    /*--- normal heater controller configuration ---*/

    // The minimum temperature increase (in °C) from the heater output thermocouple to verify that the heater is working.  The default is 10°C, valid
    // values are in the range [5..30].
    config.heaterControl.normal.confirmOnDelta = 10;

    // The maximum time, in milliseconds, to wait for confirmation of the heater working (by sensing the temperature increase on the heater output
    // thermocouple).  The default is 30,000 (30 seconds); valid values are in the range [10,000..600,000].
    config.heaterControl.normal.confirmOnTimeMS = 30000;

    // The initial cooldown period (in milliseconds) to use after the heater fails to start.  If the heater doesnt's start, it may be that the
    // thermal "fuse" has tripped and the heater needs to cool down.  The first attempted cooldown period is the length specified here;
    // subsequent cooldown periods are gradually increased to 5x the length specified here.  The default period is 60,000 (60 seconds);
    // valid values are in the range [10,000..600,000].
    config.heaterControl.normal.initialCooldownPeriodMS = 60000;

    // The minimum temperature decrease (in °C) from the heater output thermocouple to verify that the heater is working.  The default is
    //  -10°C, valid values are in the range [-30..-5].  Note that the value is negative (indicating a temperature drop).
    config.heaterControl.normal.confirmOffDelta = -10;

    // The maximum time, in milliseconds, to wait for confirmation of the heater turning off (by sensing the temperature decrease on the
    // heater output thermocouple).  The default is 30,000 (30 seconds); valid values are in the range [10,000..600,000].
    config.heaterControl.normal.confirmOffTimeMS = 30000;

    // The maximum temperature (in °C), sensed by the heater output thermocouple, to allow while the heater is on.  If the temperature rises
    // above this level, the heater will be shut off.  The default temperature is 50°C; valid values are in the range [30..60].
    config.heaterControl.normal.heaterTempLimit = 50;

    // The time, in milliseconds, to cool down the heater after turning it off.  The default is 180000 (3 minutes); valid values are
    // in the range [60000..600000].
    config.heaterControl.normal.coolingTimeMS = 180000;

    /*--- battery-only heater controller configuration ---*/

    // The minimum temperature increase (in °C) from the battery thermocouple to verify that the heater is working.  The default is 5°C, valid
    // values are in the range [5..30].
    config.heaterControl.batteryOnly.confirmOnDelta = 10;

    // The maximum time, in milliseconds, to wait for confirmation of the heater working (by sensing the temperature increase on the battery
    // thermocouple).  The default is 30,000 (30 seconds); valid values are in the range [10,000..600,000].
    config.heaterControl.batteryOnly.confirmOnTimeMS = 30000;

    // The initial cooldown period (in milliseconds) to use after the heater fails to start.  If the heater doesnt's start, it may be that the
    // thermal "fuse" has tripped and the heater needs to cool down.  The first attempted cooldown period is the length specified here;
    // subsequent cooldown periods are gradually increased to 5x the length specified here.  The default period is 60,000 (60 seconds);
    // valid values are in the range [10,000..600,000].
    config.heaterControl.batteryOnly.initialCooldownPeriodMS = 60000;

    // The minimum temperature decrease (in °C) from the heater output thermocouple to verify that the heater is working.  The default is
    //  -10°C, valid values are in the range [-30..-5].  Note that the value is negative (indicating a temperature drop).
    config.heaterControl.batteryOnly.confirmOffDelta = -5;

    // The maximum time, in milliseconds, to wait for confirmation of the heater turning off (by sensing the temperature decrease on the
    // battery thermocouple).  The default is 30,000 (30 seconds); valid values are in the range [10,000..600,000].
    config.heaterControl.batteryOnly.confirmOffTimeMS = 30000;

    // The time, in milliseconds, to cool down the heater after turning it off.  The default is 180000 (3 minutes); valid values are
    // in the range [60000..600000].
    config.heaterControl.batteryOnly.coolingTimeMS = 180000;




    // This value defines the amount of change in the temperature (in degrees Celcius) measured by the thermocouple in the heater's air output
    // must be seen to verify that the heater has successfully turned on or off.  This value must be in the range [1..40] degrees Celcius, and
    // its default value is 10 degrees Celcius.
    config.heaterControl.heaterTempChangeSenseThreshold = 10.0;

    // This value defines the amount of change in the temperature (in degrees Celcius) measured by the thermocouple under the batteries
    // must be seen to verify that the batteries are being heated or cooled.  This value must be in the range [0.25..10] degrees Celcius, and
    // its default value is 2.5 degrees Celcius.
    config.heaterControl.batteryTempChangeSenseThreshold = 2.5;

    // This value defines the maximum temperature (in degrees Celcius) allowed in the heater's air output.  If this temperature is exceeded, the
    // heater will be shut down even if the batteries' temperature is too low.  This is a safety feature in case the heater's internal
    // overtemperature "breaker" fails.  The heater will be restarted after a cooldown period.  This value must be in the range [30..75] degrees
    // Celcius, and its default value is 50C.
    config.heaterControl.maxHeaterTemperature = 50;

    // This value determines how many times to attempt starting the heater before assuming it has actually failed.  The heater has an
    // overtemperature "breaker" that can prevent it from starting if the internal temperature of the heater is too high.  To handle this, if
    // we try and fail to start the heater, then we wait for a while (see heaterCooldownTime) to let the heater cool down and try again.
    // This value must be in the range [1..10], and its default value is 4.
    config.heaterControl.maxHeaterStartAttempts = 4;


    // The heater thermocouple measures the temperature of the air blowing out of the heater.  When turning the heater on, the temperature is
    // measured just before turning it on, and then the heater's operation is verified when the temperature increases by at least
    // heaterTempChangeSenseThreshold degrees C.  This value determines the maximum time (in milliseconds) to wait for that
    // verification.  If the time is exceeded, the heater has failed to start.  Note that the heater failing to start isn't necessarily fatal,
    // as it may simply be too hot and in need of a cooldown cycle.  This value must be in the range [0..600,000], and the default value is
    // 150,000 (or two and a half minutes).
    config.heaterControl.maxHeaterOnVerifyTime = 150000;  // 2.5 minutes...

    // The heater thermocouple measures the temperature of the air blowing out of the heater.  When turning the heater off, the temperature is
    // measured just before turning it off, and then the heater's operation is verified when the temperature decreases by at least
    // heaterTempChangeSenseThreshold degrees C.  This value determines the maximum time (in milliseconds) to wait for that
    // verification.  If the time is exceeded, the heater has failed to shut off.  This value must be in the range [0..600,000], and the default
    // value is 180,000 (or three minutes).
    config.heaterControl.maxHeaterOffVerifyTime = 180000;  // 3 minutes...

    // When the heater fails to turn on, a heater cooldown cycle is initiated for up to maxHeaterStartAttempts times.  This value is
    // multiplied by the retry attempt number to determine how long to wait (in milliseconds) for cooling down (with the heater off).  For
    // example, if this value was set to 180,000 (for 3 minutes), then the cooldown period would be 3 minutes on the first heater start retry, 6
    // minutes on the second retry, 9 minutes on the third retry, and so on.  This value must be in the range [60,000..600,000] milliseconds.
    // The default value is 180,000.
    config.heaterControl.heaterCooldownTime = 180000;  // 3 minutes...

    // If the battery temperature thermocouple fails, but we sense that the heater temperature is below the current low battery temperature
    // threshold, then we assume that the batteries need to be heated and we turn the heater on.  However, because we can't sense the actual
    // battery temperature we just run "open loop", leaving the heater on for a fixed amount of time.  This value determines that time, in
    // milliseconds.  Its value must be in the range [60,000..600,000] milliseconds (one minute to ten minutes); the default value is 300,000
    // milliseconds (five minutes).
    config.heaterControl.maxOpenLoopHeaterRunTime = 300000;  // 5 minutes...

    /*
     * Console server configuration...
     */

    // The maximum number of console clients that may connect simultaneously.  Defaults to 1.
    config.consoleServer.maxClients = 1;

    // The name of this console server.
    config.consoleServer.name = "shedsolar";

    // The base64-encoded shared secret for this console server.  It must be a 16 byte (128 bit) value.
    config.consoleServer.key = secretKey;

    // The TCP port to listen for client connections on.
    config.consoleServer.port = 8217;

    // The IP address of the network interface to listen on (i.e., to bind to).  If {@code null} (the default), listens on all network interfaces.
    config.consoleServer.bindTo = null;

    // Add the providers to the providers map.  The key is the provider's name; the value is the fully qualified class name of the provider.
    // Any number of providers may be included as long as their names are unique.  The same provider may be provisioned under multiple names.
    config.consoleServer.providers.put( "test", "com.dilatush.util.test.TestConsoleProvider" );
    config.consoleServer.providers.put( "app", "com.dilatush.shedsolar.ShedSolarConsole" );



    /*
     * Test scenario configuration...
     */

    // Set to true to enable the testing framework, false otherwise.  Defaults to false.
    config.testManager.enabled = false;


    // Sets the name of the default test scenario, which should of course be configured in the scenarios below.  Defaults to none.
    config.testManager.scenario = "";


    // A map of test scenarios.  Each scenario is itself a map of test enabler names (which must match the names registered by test code) to test
    // enabler instances.
    config.testManager.scenarios = makeMap( {
        simple: {
            batteryRaw: makeEnabler( "Manual", { "enabled": false, "mask": 0x00000001 } ),
            heaterRaw:  makeEnabler( "Manual", { "enabled": false, "mask": 0x00000004 } )
        }
    } );
}
