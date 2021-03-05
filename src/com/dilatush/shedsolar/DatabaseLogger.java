package com.dilatush.shedsolar;

import com.dilatush.util.AConfig;
import com.dilatush.util.SimpleConnectionPool;
import com.dilatush.util.info.InfoSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.dilatush.shedsolar.Events.*;
import static com.dilatush.shedsolar.LightDetector.Mode;
import static com.dilatush.util.Internet.isValidHost;
import static com.dilatush.util.Strings.isEmpty;

/**
 * Records a log of ShedSolar's status once per minute into a database on a remote server (in our case, a MySQL server on Beast).
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class DatabaseLogger {

    private static final Logger LOGGER = Logger.getLogger( new Object(){}.getClass().getEnclosingClass().getCanonicalName() );

    private final ShedSolar            ss;
    private final SimpleConnectionPool connectionPool;

    private Instant heaterOn;
    private Duration heaterOnTime;


    /**
     * Creates a new instance of this class with the given configuration.
     *
     * @param _config The configuration for this instance.
     */
    public DatabaseLogger( final Config _config ) {

        // get our ShedSolar instance...
        ss = ShedSolar.instance;

        // make ourselves a pooled connection...
        connectionPool = new SimpleConnectionPool( _config.host, _config.password, _config.user, 1, 1 );

        // subscribe to some haps to keep track of heater on time...
        ss.haps.subscribe( HEATER_ON,       this::heaterOn      );
        ss.haps.subscribe( HEATER_OFF,      this::heaterOff     );
        ss.haps.subscribe( HEATER_NO_START, this::heaterNoStart );

        // schedule our job to post, for once per minute, to happen in worker thread...
        ss.scheduledExecutor.scheduleAtFixedRate(
                () -> ss.executor.submit( () -> post( new LogRecord() ) ),
                Duration.ofMinutes( 1 ),
                Duration.ofMinutes( 1 ) );
    }


    /**
     * Called when the heater is turned on.  It may not actually turn on, if the thermal circuit breaker is tripped.
     */
    private void heaterOn() {
        heaterOn = Instant.now( Clock.systemUTC() );
    }


    /**
     * Called when the heater is turned off.
     */
    private void heaterOff() {

        if( heaterOn == null )
            return;

        Duration thisHeaterOnTime = Duration.between( heaterOn, Instant.now( Clock.systemUTC() ) );

        if( heaterOnTime == null )
            heaterOnTime = thisHeaterOnTime;
        else
            heaterOnTime = Duration.ofMillis( heaterOnTime.toMillis() + thisHeaterOnTime.toMillis() );

        heaterOn = null;
    }


    /**
     * Called if the heater had been turned on, but the heater controller has determined that it didn't actually start.
     */
    private void heaterNoStart() {
        heaterOn = null;
    }


    private static final String INSERT_LOG = "INSERT INTO shedsolar.log " +
            "(timestamp,heater_on,battery,heater,ambient,outside,solar_irradiance,soc,panel_voltage,panel_current,panel_power," +
            "light_mode,battery_voltage,inverter_voltage,inverter_current,inverter_power) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";


    /**
     * Called once per minute, in a worker thread, to post the given log record to the database.
     *
     * @param _logRecord The {@link LogRecord} containing the data to post to the database log.
     */
    private void post( final LogRecord _logRecord ) {

        try {

            try (
            // grab ourselves a connection and do our insert...
            Connection connection = connectionPool.getConnection();
            PreparedStatement ps = connection.prepareStatement( INSERT_LOG ) ) {

                // now set all our non-Outback fields...
                Timestamp timestamp = new Timestamp( _logRecord.timestamp.toEpochMilli() );
                ps.setTimestamp(         1, timestamp                             );

                ps.setDouble(            2, _logRecord.heaterOnSeconds            );  // heater on time...

                setFloatInfoSource( ps,  3, _logRecord.batteryTemperature );
                setFloatInfoSource( ps,  4, _logRecord.heaterTemperature  );
                setFloatInfoSource( ps,  5, _logRecord.ambientTemperature );
                setFloatInfoSource( ps,  6, _logRecord.outsideTemperature );
                setFloatInfoSource( ps,  7, _logRecord.solarIrradiance    );

                // handle the light mode...
                if( _logRecord.lightMode.isInfoAvailable() )
                    ps.setString( 12, _logRecord.lightMode.getInfo() == Mode.LIGHT ? "LIGHT" : "DARK" );
                else
                    ps.setNull( 12, ps.getParameterMetaData().getParameterType( 12 ) );

                // if we have Outback data, set it...
                if( _logRecord.outbackData.isInfoAvailable() ) {
                    OutbackData od = _logRecord.outbackData.getInfo();
                    ps.setDouble(        8, od.stateOfCharge                      );
                    ps.setDouble(        9, od.panelVoltage                       );
                    ps.setDouble(       10, od.panelCurrent                       );
                    ps.setDouble(       11, od.panelPower                         );
                    ps.setDouble(       13, od.batteryVoltage                     );
                    ps.setDouble(       14, od.inverterVoltage                    );
                    ps.setDouble(       15, od.inverterCurrent                    );
                    ps.setDouble(       16, od.inverterPower                      );
                }

                // otherwise, nulls for the outback data...
                else {
                    ps.setNull(  8, ps.getParameterMetaData().getParameterType(  8 ) );
                    ps.setNull(  9, ps.getParameterMetaData().getParameterType(  9 ) );
                    ps.setNull( 10, ps.getParameterMetaData().getParameterType( 10 ) );
                    ps.setNull( 11, ps.getParameterMetaData().getParameterType( 11 ) );
                    ps.setNull( 13, ps.getParameterMetaData().getParameterType( 13 ) );
                    ps.setNull( 14, ps.getParameterMetaData().getParameterType( 14 ) );
                    ps.setNull( 15, ps.getParameterMetaData().getParameterType( 15 ) );
                    ps.setNull( 16, ps.getParameterMetaData().getParameterType( 16 ) );
                }

                // now we can execute our statement...
                ps.execute();
            }
        }
        catch( Exception _e ) {

            LOGGER.log( Level.SEVERE, "Problem when posting database log record " + _e.getMessage(), _e );
        }
    }


    /**
     * Sets {@code Double} data field at the given index, in the given prepared statement, from the given {@link InfoSource}.  If the data is not
     * available, sets a {@code null}.
     *
     * @param _ps The {@link PreparedStatement} to set a field in.
     * @param _index The index of the field to set.
     * @param _infoSource The {@link InfoSource} to get the data from.
     * @throws SQLException on any error
     */
    private void setFloatInfoSource( final PreparedStatement _ps, final int _index, final InfoSource<Float> _infoSource )
            throws SQLException {

        if( _infoSource.isInfoAvailable() )
            _ps.setDouble( _index, _infoSource.getInfo() );
        else
            _ps.setNull( _index, _ps.getParameterMetaData().getParameterType( _index ) );
    }


    /**
     * A log record, destined for the database log.  The data for the log record is collected at construction time, as the actual posting to the log
     * could be deferred until a working thread is available.
     */
    private class LogRecord {

        private final Instant                 timestamp;
        private final double                  heaterOnSeconds;
        private final InfoSource<Float>       batteryTemperature;
        private final InfoSource<Float>       heaterTemperature;
        private final InfoSource<Float>       ambientTemperature;
        private final InfoSource<Float>       outsideTemperature;
        private final InfoSource<Float>       solarIrradiance;
        private final InfoSource<Mode>        lightMode;
        private final InfoSource<OutbackData> outbackData;


        private LogRecord() {

            // capture the timestamp...
            timestamp = Instant.now( Clock.systemUTC() );

            // calculator how long the heater was on in the past minute...
            double hot = 0;

            // if the heater isn't on at the moment, it's easy - whatever heater on time we've captured....
            if( heaterOn == null ) {
                if( heaterOnTime != null ) {
                    hot = 0.001 * heaterOnTime.toMillis();
                    heaterOnTime = null;
                }
            }

            // if the heater IS on right now, we have to add the time on this run to any time we've already accumulated...
            else {
                hot = Duration.between( heaterOn, Instant.now( Clock.systemUTC() ) ).toMillis() * 0.001;
                heaterOn = Instant.now( Clock.systemUTC() );  // reset the heater on time to right now, so subsequent calculations are correct...
                if( heaterOnTime != null ) {
                    hot += 0.001 * heaterOnTime.toMillis();
                    heaterOnTime = null;
                }
            }
            heaterOnSeconds = hot;

            // collect data sources...
            batteryTemperature = ss.batteryTemperature.getInfoSource();
            heaterTemperature  = ss.heaterTemperature.getInfoSource();
            ambientTemperature = ss.ambientTemperature.getInfoSource();
            outsideTemperature = ss.outsideTemperature.getInfoSource();
            solarIrradiance    = ss.solarIrradiance.getInfoSource();
            lightMode          = ss.light.getInfoSource();
            outbackData        = ss.outback.getInfoSource();
        }
    }


    /**
     * The configuration for {@link DatabaseLogger}.
     */
    public static class Config extends AConfig {

        /**
         * The fully-qualified host name (or dotted-form IPv4 address) of the database server.  There is no default value.
         */
        public String host;

        /**
         * The user name for the database server.  There is no default value.
         */
        public String user;

        /**
         * The password for the database server.  There is no default value.
         */
        public String password;


        @Override
        public void verify( final List<String> _messages ) {

            validate( () -> isValidHost( host ), _messages,
                    "Database server host not found: " + host );
            validate( () -> !isEmpty( user ), _messages,
                    "Database user name not supplied" );
            validate( () -> !isEmpty( password ), _messages,
                    "Database password not supplied" );
        }
    }
}
