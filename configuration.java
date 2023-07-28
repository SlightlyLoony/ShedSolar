import com.dilatush.mop.PostOffice;
import com.dilatush.util.config.Configurator;
import com.dilatush.util.config.AConfig;
import com.dilatush.util.noisefilter.MedianErrorCalc;
import com.dilatush.util.test.TestEnabler;
import com.dilatush.util.test.SimpleTestEnabler;
import com.dilatush.shedsolar.Config;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.Arrays;
import java.time.Duration;
import java.util.function.Function;

/**
 * Configurator for ShedSolar running on Raspberry Pi shedsolar.
 */

public class ShedSolarConfigurator implements Configurator {

    public void config( final AConfig _config ) {

        // set up our configuration object...
        Config config = (Config) _config;



        /*
         * CPO (Central Post Office) configuration.
         */

        // The name of this post office, which must be a string with one or more characters, unique amongst all post offices connected to the specified CPO.
        config.cpo.name = === MOP user ===;

        // The secret that encrypts messages from this post office.  This is a string that must exactly match the one for this post office that has been
        // configured at the specified CPO.
        config.cpo.secret = === MOP password ===;

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

        // The interval between temperature readings as a Duration.  Valid values are in the range [0.1 second .. 10 minutes].  The default
        // value is 250 ms.
        config.tempReader.interval = Duration.ofMillis( 250 );

        // The number of samples (history) to keep in the filter.  Valid values are 2 or greater.
        config.tempReader.noiseFilter.numSamples = 41;

        // An instance of the class that implements ErrorCalc, for the noise filter.
        config.tempReader.noiseFilter.errorCalc = new MedianErrorCalc();

        // The maximum number of samples that may be ignored by this filter, as a fraction of the number of samples.  Valid values
        // are in the range [0..1].
        config.tempReader.noiseFilter.maxIgnoreFraction = 0.25F;

        // The maximum total error of the samples that may be ignored by this filter, as a fraction of the total error of all the samples
        // in this filter.  Valid values are in the range of [0..1].
        config.tempReader.noiseFilter.maxTotalErrorIgnoreFraction = 1.0F;

        // The minimum value of the error for any sample to be ignored.  This value must be non-negative.
        config.tempReader.noiseFilter.minSampleErrorIgnore = 0.75F;  // in degree C


        /*
         * Battery temperature LED configuration.
         */

        // The minimum battery temperature (in degrees Celcius) that can be shown by the battery temperature LED (when the LED is solid off).  This value
        // defaults to 0C, and must be less than the value of config.batteryTempLED.maxTemp.
        config.batteryTempLED.minTemp = 0.0F;

        // The maximum battery temperature (in degrees Celcius) that can be shown by the battery temperature LED (when the LED is solidly on).  This
        // value defaults to 45C, and must be greater than the value of config.batteryTempLED.minTemp.
        config.batteryTempLED.maxTemp = 45.0F;

        // The interval, in milliseconds, between the start of each normal flash of the battery temperature LED, indicating that the battery
        // temperature can be read.  This value must be in the range [500..5000], and must be at least twice config.batteryTempLED.errorInterval.
        config.batteryTempLED.normalInterval = 2000;

        // The interval, in milliseconds, between the start of each error flash of the battery temperature LED, indicating that the battery
        // temperature cannot be read.  This value must be in the range [250..2500], and must be less than half config.batteryTempLED.normalInterval.
        config.batteryTempLED.errorInterval = 400;


        /*
         * Database logger configuration.
         */

        // The fully-qualified host name (or dotted-form IPv4 address) of the database server.  There is no default value.
        config.databaseLogger.host = "beast.dilatush.com";

        // The user name for the database server.  There is no default value.
        config.databaseLogger.user = === db user ===;

        // The password for the database server.  There is no default value.
        config.databaseLogger.password = === db password ===;


        /*
         * Status LED configuration.
         */

        // The duration of a short (0) flash, in milliseconds.  The default value is 250; valid values are in the range [50..1000].
        config.statusLED.shortFlashMS = 250;

        // The duration of a long (1) flash, in milliseconds.  The default value is 750; valid values are in the range [100..2000].
        config.statusLED.longFlashMS = 1000;

        // The delay between flashes within a single message, in milliseconds.  The default value is 250; valid values are in the range [100..1000].
        config.statusLED.interFlashDelayMS = 150;

        // The delay between messages within a single cycle, in milliseconds.  The default value is 1000; valid values are in the range [500..3000].
        config.statusLED.interMessageDelayMS = 1000;

        // The delay between cycles, in milliseconds.  The default value is 2000; valid values are in the range [1000..10000].
        config.statusLED.interCycleDelayMS = 4000;


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

        /**
         * The delay before switching from dark to light mode, when adequate brightness has been detected, in "ticks" (see interval).  The
         * idea behind this delay is to avoid jumping to light mode if there's only a brief burst of light, like a hole in the clouds.  The
         * value must be in the range [0..120]; the default value is 5.
         */
        config.lightDetector.toLightDelay = 5;

        /**
         * The delay before switching from light to dark mode, when inadequate brightness has been detected, in "ticks" (see interval).  The
         * idea behind this delay is to avoid jumping to dark mode if there's only a brief interruption of light, like a cloud blocking the sun.
         * The value must be in the range [0..240]; the default value is 60.
         */
        config.lightDetector.toDarkDelay = 60;


        /*
         * Heater Control configuration.
         */

        // The time (in milliseconds) between "ticks" of the heater control state machine.  This value must be in the range [1,000..15,000]
        // milliseconds, and the default value is 5,000 milliseconds (five seconds).
        config.heaterControl.tickTime = 5000;  // 5 seconds

        // The lowest battery temperature (in degrees Celcius) allowed when in dark mode.  This value must be in the range [-10..25], and it must
        // be less than darkHighTemp and less than lightLowTemp.  Its default value is 0C.
        config.heaterControl.darkLowTemp = 3;

        // The highest battery temperature (in degrees Celcius) allowed when in dark mode.  This value must be in the range [-10..25], and it must
        // be greater than darkLowTemp and less than lightHighTemp.  Its default value is 5C.
        config.heaterControl.darkHighTemp = 8;

        // The lowest battery temperature (in degrees Celcius) allowed when in light mode.  This value must be in the range [0..40], and it must
        // be less than lightHighTemp and greater than darkLowTemp.  Its default value is 25C.
        config.heaterControl.lightLowTemp = 15;

        // The highest battery temperature (in degrees Celcius) allowed when in light mode.  This value must be in the range [0..40], and it must
        // be greater than lightLowTemp and greater than darkHighTemp.  It's default value is 30C.
        config.heaterControl.lightHighTemp = 20;

        /*--- normal heater controller configuration ---*/

        // The minimum temperature increase (in °C) from the heater output thermocouple to verify that the heater is working.  The default is 10°C, valid
        // values are in the range [5..30].
        config.heaterControl.normal.confirmOnDelta = 5;

        // The maximum time, in milliseconds, to wait for confirmation of the heater working (by sensing the temperature increase on the heater output
        // thermocouple).  The default is 30,000 (30 seconds); valid values are in the range [10,000..600,000].
        config.heaterControl.normal.confirmOnTimeMS = 60000;

        // The initial cooldown period (in milliseconds) to use after the heater fails to start.  If the heater doesnt's start, it may be that the
        // thermal "fuse" has tripped and the heater needs to cool down.  The first attempted cooldown period is the length specified here;
        // subsequent cooldown periods are gradually increased to 5x the length specified here.  The default period is 60,000 (60 seconds);
        // valid values are in the range [10,000..600,000].
        config.heaterControl.normal.initialCooldownPeriodMS = 60000;

        // The minimum temperature decrease (in °C) from the heater output thermocouple to verify that the heater is working.  The default is
        //  -10°C, valid values are in the range [-30..-5].  Note that the value is negative (indicating a temperature drop).
        config.heaterControl.normal.confirmOffDelta = -5;

        // The maximum time, in milliseconds, to wait for confirmation of the heater turning off (by sensing the temperature decrease on the
        // heater output thermocouple).  The default is 30,000 (30 seconds); valid values are in the range [10,000..600,000].
        config.heaterControl.normal.confirmOffTimeMS = 60000;

        // The maximum temperature (in °C), sensed by the heater output thermocouple, to allow while the heater is on.  If the temperature rises
        // above this level, the heater will be shut off.  The default temperature is 80°C; valid values are in the range [30..100].
        config.heaterControl.normal.heaterTempLimit = 100;

        // The time, in milliseconds, to cool down the heater after turning it off.  The default is 180000 (3 minutes); valid values are
        // in the range [60000..600000].
        config.heaterControl.normal.coolingTimeMS = 240000;

        /*--- battery-only heater controller configuration ---*/

        // The minimum temperature increase (in °C) from the battery thermocouple to verify that the heater is working.  The default is 5°C, valid
        // values are in the range [.1..30].
        config.heaterControl.batteryOnly.confirmOnDelta = 5;

        // The maximum time, in milliseconds, to wait for confirmation of the heater working (by sensing the temperature increase on the battery
        // thermocouple).  The default is 180,000 (3 minutes); valid values are in the range [10,000..600,000].
        config.heaterControl.batteryOnly.confirmOnTimeMS = 300000;

        // The initial cooldown period (in milliseconds) to use after the heater fails to start.  If the heater doesnt's start, it may be that the
        // thermal "fuse" has tripped and the heater needs to cool down.  The first attempted cooldown period is the length specified here;
        // subsequent cooldown periods are gradually increased to 5x the length specified here.  The default period is 60,000 (60 seconds);
        // valid values are in the range [10,000..600,000].
        config.heaterControl.batteryOnly.initialCooldownPeriodMS = 60000;

        // The minimum temperature decrease (in °C) from the heater output thermocouple to verify that the heater is working.  The default is
        //  -5°C, valid values are in the range [-30..-0.1].  Note that the value is negative (indicating a temperature drop).
        config.heaterControl.batteryOnly.confirmOffDelta = -5;

        // The maximum time, in milliseconds, to wait for confirmation of the heater turning off (by sensing the temperature decrease on the
        // battery thermocouple).  The default is 180,000 (3 minutes); valid values are in the range [10,000..600,000].
        config.heaterControl.batteryOnly.confirmOffTimeMS = 300000;

        // The time, in milliseconds, to cool down the heater after turning it off.  The default is 180000 (3 minutes); valid values are
        // in the range [60000..600000].
        config.heaterControl.batteryOnly.coolingTimeMS = 180000;

        /*--- heater-only heater controller configuration ---*/

        // The minimum temperature increase (in °C) from the heater thermocouple to verify that the heater is working.  The default is 10°C, valid
        // values are in the range [5..30].
        config.heaterControl.heaterOnly.confirmOnDelta = 10;

        // The maximum time, in milliseconds, to wait for confirmation of the heater working (by sensing the temperature increase on the battery
        // thermocouple).  The default is 30,000 (30 seconds); valid values are in the range [10,000..600,000].
        config.heaterControl.heaterOnly.confirmOnTimeMS = 60000;

        // The initial cooldown period (in milliseconds) to use after the heater fails to start.  If the heater doesnt's start, it may be that the
        // thermal "fuse" has tripped and the heater needs to cool down.  The first attempted cooldown period is the length specified here;
        // subsequent cooldown periods are gradually increased to 5x the length specified here.  The default period is 60,000 (60 seconds);
        // valid values are in the range [10,000..600,000].
        config.heaterControl.heaterOnly.initialCooldownPeriodMS = 60000;

        // The minimum temperature decrease (in °C) from the heater output thermocouple to verify that the heater is working.  The default is
        //  -10°C, valid values are in the range [-30..-5].  Note that the value is negative (indicating a temperature drop).
        config.heaterControl.heaterOnly.confirmOffDelta = -10;

        // The maximum time, in milliseconds, to wait for confirmation of the heater turning off (by sensing the temperature decrease on the
        // battery thermocouple).  The default is 30,000 (30 seconds); valid values are in the range [10,000..600,000].
        config.heaterControl.heaterOnly.confirmOffTimeMS = 30000;

        // The time, in milliseconds, to cool down the heater after turning it off.  The default is 180000 (3 minutes); valid values are
        // in the range [60000..600000].
        config.heaterControl.heaterOnly.coolingTimeMS = 180000;

        // The number of degrees per second of operation that the heater will raise the temperature of the batteries, as determined by direct
        // observation.  There is no default value; valid values are in the range (0..1].
        config.heaterControl.heaterOnly.degreesPerSecond = 0.05;


        /*--- no-temps heater controller configuration ---*/

        // The constant K for the thermal calculations in {@link ThermalCalcs}, as determined by direct observation.  There is no default value;
        // valid values are in the range (0..1].
        config.heaterControl.noTemps.k = 0.0004;

        // The number of degrees per second of operation that the heater will raise the temperature of the batteries, as determined by direct
        // observation.  There is no default value; valid values are in the range (0..1].
        config.heaterControl.noTemps.degreesPerSecond = 0.05;

        // The number to multiply the computed length of heater on time by, to provide a margin of safety on the high temperature side, as it is
        // better for the battery to be slightly warmer than the target temperatures than it is for it to be cooler.  The default value is 1.0;
        // valid values are in the range [1..1.25].
        config.heaterControl.noTemps.safetyTweak = 1.03;


        /*
         * Console server configuration...
         */

        // The maximum number of console clients that may connect simultaneously.  Defaults to 1.
        config.consoleServer.maxClients = 2;

        // The name of this console server.
        config.consoleServer.name = === console user ===;

        // The base64-encoded shared secret for this console server.  It must be a 16 byte (128 bit) value.
        config.consoleServer.key = === console key ===;

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
        var scenarios = new HashMap<String,Map<String,TestEnabler>>();

        var scenario = new HashMap<String,TestEnabler>();
        scenario.put( "heaterRaw", new SimpleTestEnabler( Map.of( "mask", 0x00010001 ) ) );
        scenario.put( "loTemp", new SimpleTestEnabler( Map.of( "temp", 0.0 ) ) );
        scenario.put( "hiTemp", new SimpleTestEnabler( Map.of( "temp", 30.0 ) ) );
        scenarios.put( "battery_only", scenario );

        scenario = new HashMap<String,TestEnabler>();
        scenario.put( "heaterRaw", new SimpleTestEnabler( Map.of( "mask", 0x00010001 ) ) );
        scenario.put( "loTemp", new SimpleTestEnabler( Map.of( "temp", 0.0 ) ) );
        scenario.put( "hiTemp", new SimpleTestEnabler( Map.of( "temp", 30.0 ) ) );
        scenarios.put( "heater_only", scenario );

        scenario = new HashMap<String,TestEnabler>();
        scenario.put( "heaterRaw", new SimpleTestEnabler( Map.of( "mask", 0x00010001 ) ) );
        scenario.put( "batteryRaw", new SimpleTestEnabler( Map.of( "mask", 0x00010001 ) ) );
        scenarios.put( "no_temps", scenario );

        config.testManager.scenarios = scenarios;
    }
}
