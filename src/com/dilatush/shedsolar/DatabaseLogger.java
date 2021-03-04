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

import static com.dilatush.shedsolar.Events.*;

/**
 * Records a log of ShedSolar's status once per minute into a database on a remote server (in our case, a MySQL server on Beast).
 *
 * @author Tom Dilatush  tom@dilatush.com
 */
public class DatabaseLogger {

    private final ShedSolar            ss;
    private final SimpleConnectionPool connectionPool;

    private Instant heaterOn;
    private Duration heaterOnTime;

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


    private void heaterOn() {
        heaterOn = Instant.now( Clock.systemUTC() );
    }


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


    private void heaterNoStart() {
        heaterOn = null;
    }


    private static final String INSERT_LOG = "INSERT INTO shedsolar.log " +
            "(timestamp,heater_on,battery,heater,ambient,outside,solar_irradiance,soc,panel_voltage,panel_current,panel_power," +
            "light_mode,battery_voltage,inverter_voltage,inverter_current,inverter_power) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";


    private void post( final LogRecord _logRecord ) {

        try {

            try (
            // grab ourselves a connection and do our insert...
            Connection connection = connectionPool.getConnection();
            PreparedStatement ps = connection.prepareStatement( INSERT_LOG ) ) {

                // now set all our non-Outback fields...
                Timestamp timestamp = new Timestamp( _logRecord.timestamp.toEpochMilli() );
                ps.setTimestamp(         1, timestamp                             );

                double hot = 0;
                if( heaterOn == null ) {
                    if( heaterOnTime != null ) {
                        hot = 0.001 * heaterOnTime.toMillis();
                        heaterOnTime = null;
                    }
                }
                else {
                    hot = Duration.between( heaterOn, Instant.now( Clock.systemUTC() ) ).toMillis() * 0.001;
                    heaterOn = Instant.now( Clock.systemUTC() );
                    if( heaterOnTime != null ) {
                        hot += 0.001 * heaterOnTime.toMillis();
                        heaterOnTime = null;
                    }
                }
                ps.setDouble(            2, hot                                   );  // heater on time...

                setFloatInfoSource( ps,  3, ss.batteryTemperature.getInfoSource() );
                setFloatInfoSource( ps,  4, ss.heaterTemperature.getInfoSource()  );
                setFloatInfoSource( ps,  5, ss.ambientTemperature.getInfoSource() );
                setFloatInfoSource( ps,  6, ss.outsideTemperature.getInfoSource() );
                setFloatInfoSource( ps,  7, ss.solarIrradiance.getInfoSource()    );

                InfoSource<LightDetector.Mode> modeSource = ss.light.getInfoSource();
                if( modeSource.isInfoAvailable() )
                    ps.setString( 12, modeSource.getInfo() == LightDetector.Mode.LIGHT ? "LIGHT" : "DARK" );
                else
                    ps.setNull( 12, ps.getParameterMetaData().getParameterType( 12 ) );

                // if we have Outback data, set it...
                InfoSource<OutbackData> outback = ss.outback.getInfoSource();
                if( outback.isInfoAvailable() ) {
                    OutbackData od = outback.getInfo();
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
        catch( Exception _throwables ) {
            _throwables.printStackTrace();
        }
    }


    private void setFloatInfoSource( final PreparedStatement _ps, final int _index, final InfoSource<Float> _infoSource )
            throws SQLException {

        if( _infoSource.isInfoAvailable() )
            _ps.setDouble( _index, _infoSource.getInfo() );
        else
            _ps.setNull( _index, _ps.getParameterMetaData().getParameterType( _index ) );
    }


    private static class LogRecord {

        private final Instant timestamp;

        private LogRecord() {
            timestamp = Instant.now( Clock.systemUTC() );
        }
    }


    public static class Config extends AConfig {

        public String host;

        public String user;

        public String password;


        @Override
        public void verify( final List<String> _messages ) {

        }
    }

    public static void test( int _i ) {
        System.out.println(_i);
    }
}
