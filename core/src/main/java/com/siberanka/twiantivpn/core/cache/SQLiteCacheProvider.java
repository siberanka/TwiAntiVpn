package com.siberanka.twiantivpn.core.cache;

import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.geo.GeoResult;
import com.siberanka.twiantivpn.core.vpn.VpnResult;

import java.sql.*;
import java.util.Date;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class SQLiteCacheProvider implements CacheProvider {
    private final Object databaseLock = new Object();
    private Connection connection;
    private String databaseFileLocation;

    public SQLiteCacheProvider(String databaseFileLocation) {
        this.databaseFileLocation = databaseFileLocation;
    }

    @Override
    public CompletableFuture<Boolean> setup() {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (databaseLock) {
                try {
                try {
                    Class.forName("org.sqlite.JDBC");
                } catch (ClassNotFoundException e) {
                    ConnectionGuard.reportError("SQLite driver load", e);
                }
                connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFileLocation);
                try (Statement pragma = connection.createStatement()) {
                    pragma.execute("PRAGMA busy_timeout=5000");
                }
                connection.setAutoCommit(false);
                try (Statement statement = connection.createStatement()) {
                    statement.execute("CREATE TABLE IF NOT EXISTS connectionguard_vpn_cache (address TEXT, vpn BOOLEAN, cached_on INTEGER)");
                    statement.execute("CREATE TABLE IF NOT EXISTS connectionguard_geo_cache (address TEXT, country_name TEXT, city_name TEXT, isp_name TEXT, cached_on INTEGER)");
                    addColumnIfMissing(statement, "connectionguard_geo_cache", "asn", "TEXT");
                    addColumnIfMissing(statement, "connectionguard_geo_cache", "organization", "TEXT");
                    addColumnIfMissing(statement, "connectionguard_geo_cache", "hosting", "INTEGER");
                    addColumnIfMissing(statement, "connectionguard_vpn_cache", "anonymizer", "INTEGER");
                    statement.execute("DELETE FROM connectionguard_vpn_cache WHERE rowid NOT IN (SELECT MAX(rowid) FROM connectionguard_vpn_cache GROUP BY address)");
                    statement.execute("DELETE FROM connectionguard_geo_cache WHERE rowid NOT IN (SELECT MAX(rowid) FROM connectionguard_geo_cache GROUP BY address)");
                    // Older releases created non-unique indexes with these names. IF NOT EXISTS
                    // cannot upgrade those indexes, so rebuild the plugin-owned indexes atomically.
                    statement.execute("DROP INDEX IF EXISTS vpn_address");
                    statement.execute("DROP INDEX IF EXISTS geo_address");
                    statement.execute("CREATE UNIQUE INDEX vpn_address ON connectionguard_vpn_cache (address)");
                    statement.execute("CREATE UNIQUE INDEX geo_address ON connectionguard_geo_cache (address)");
                    connection.commit();
                } catch (SQLException migrationFailure) {
                    connection.rollback();
                    throw migrationFailure;
                } finally {
                    connection.setAutoCommit(true);
                }
                return true;
            } catch (SQLException e) {
                ConnectionGuard.reportError("SQLite setup", e);
                return false;
            }
            }
        });
    }

    private void addColumnIfMissing(Statement statement, String table, String column, String type) {
        try {
            statement.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
        } catch (SQLException ignored) {
        }
    }

    @Override
    public CompletableFuture<Boolean> disband() {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (databaseLock) {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
                return true;
            } catch (SQLException e) {
                ConnectionGuard.reportError("SQLite shutdown", e);
                return false;
            }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (databaseLock) {
            try {
                try (PreparedStatement preparedStatement = connection.prepareStatement(
                        "SELECT vpn, anonymizer, cached_on FROM connectionguard_vpn_cache WHERE address=?"
                )) {
                    preparedStatement.setString(1, ipAddress);
                    try (ResultSet resultSet = preparedStatement.executeQuery()) {

                    if (resultSet.next()) {
                    boolean isVpn = resultSet.getBoolean("vpn");
                    boolean anonymizer = resultSet.getBoolean("anonymizer");
                    long cachedOn = resultSet.getLong("cached_on");

                    // Check if cache data is expired
                    if ((cachedOn + ConnectionGuard.getVpnCacheExpirationTime() * 60_000L) > new Date().getTime()) {
                        // Data is not expired.
                        return Optional.of(new VpnResult(ipAddress, isVpn).setAnonymizer(anonymizer));
                    } else {
                        // Data is expired and needs to be removed.
                        try (PreparedStatement deleteEntryStatement = connection.prepareStatement(
                                "DELETE FROM connectionguard_vpn_cache WHERE address=?"
                        )) {
                            deleteEntryStatement.setString(1, ipAddress);
                            deleteEntryStatement.executeUpdate();
                        }
                        return Optional.empty();
                    }
                } else {
                    return Optional.empty();
                }
                    }
                }
            } catch (SQLException e) {
                ConnectionGuard.reportError("SQLite VPN cache read", e);
                return Optional.empty();
            }
            }
        });
    }

    @Override
    public CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            synchronized (databaseLock) {
            try {
                try (PreparedStatement preparedStatement = connection.prepareStatement(
                        "SELECT country_name, city_name, isp_name, asn, organization, hosting, cached_on FROM connectionguard_geo_cache WHERE address=?"
                )) {
                    preparedStatement.setString(1, ipAddress);
                    try (ResultSet resultSet = preparedStatement.executeQuery()) {

                    if (resultSet.next()) {
                    String countryName = resultSet.getString("country_name");
                    String cityName = resultSet.getString("city_name");
                    String ispName = resultSet.getString("isp_name");
                    String asn = resultSet.getString("asn");
                    String organization = resultSet.getString("organization");
                    int hostingValue = resultSet.getInt("hosting");
                    Boolean hosting = resultSet.wasNull() ? null : hostingValue != 0;
                    long cachedOn = resultSet.getLong("cached_on");

                    // Check if cache data is expired
                    if ((cachedOn + ConnectionGuard.getGeoCacheExpirationTime() * 60_000L) > new Date().getTime()) {
                        // Data is not expired.
                        return Optional.of(new GeoResult(ipAddress, countryName, cityName, ispName, asn, organization, hosting));
                    } else {
                        // Data is expired and needs to be removed.
                        try (PreparedStatement removeEntryStatement = connection.prepareStatement(
                                "DELETE FROM connectionguard_geo_cache WHERE address=?"
                        )) {
                            removeEntryStatement.setString(1, ipAddress);
                            removeEntryStatement.executeUpdate();
                        }
                        return Optional.empty();
                    }
                } else {
                    return Optional.empty();
                }
                    }
                }
            } catch (SQLException e) {
                ConnectionGuard.reportError("SQLite geo cache read", e);
                return Optional.empty();
            }
            }
        });
    }

    @Override
    public CompletableFuture<Void> addVpnResult(VpnResult vpnResult) {
        return CompletableFuture.runAsync(() -> {
            synchronized (databaseLock) {
            try {
                try (PreparedStatement preparedStatement = connection.prepareStatement(
                        "INSERT INTO connectionguard_vpn_cache (address, vpn, anonymizer, cached_on) VALUES (?, ?, ?, ?) "
                                + "ON CONFLICT(address) DO UPDATE SET vpn=excluded.vpn, "
                                + "anonymizer=excluded.anonymizer, cached_on=excluded.cached_on"
                )) {

                preparedStatement.setString(1, vpnResult.getIpAddress());
                preparedStatement.setBoolean(2, vpnResult.isVpn());
                preparedStatement.setBoolean(3, vpnResult.isAnonymizer());
                preparedStatement.setLong(4, new Date().getTime());

                preparedStatement.executeUpdate();
                }
            } catch (SQLException e) {
                ConnectionGuard.reportError("SQLite VPN cache write", e);
            }
            }
        });
    }

    @Override
    public CompletableFuture<Void> addGeoResult(GeoResult geoResult) {
        return CompletableFuture.runAsync(() -> {
            synchronized (databaseLock) {
            try {
                try (PreparedStatement preparedStatement = connection.prepareStatement(
                        "INSERT INTO connectionguard_geo_cache (address, country_name, city_name, isp_name, asn, organization, hosting, cached_on) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(address) DO UPDATE SET "
                                + "country_name=excluded.country_name, city_name=excluded.city_name, "
                                + "isp_name=excluded.isp_name, asn=excluded.asn, "
                                + "organization=excluded.organization, hosting=excluded.hosting, "
                                + "cached_on=excluded.cached_on"
                )) {

                preparedStatement.setString(1, geoResult.getIpAddress());
                preparedStatement.setString(2, geoResult.getCountryName());
                preparedStatement.setString(3, geoResult.getCityName());
                preparedStatement.setString(4, geoResult.getIspName());
                preparedStatement.setString(5, geoResult.getAsn());
                preparedStatement.setString(6, geoResult.getOrganization());
                if (geoResult.getHosting() == null) {
                    preparedStatement.setNull(7, Types.INTEGER);
                } else {
                    preparedStatement.setInt(7, geoResult.getHosting() ? 1 : 0);
                }
                preparedStatement.setLong(8, new Date().getTime());

                preparedStatement.executeUpdate();
                }
            } catch (SQLException e) {
                ConnectionGuard.reportError("SQLite geo cache write", e);
            }
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> removeVpnResult(String ipAddress) {
        return CompletableFuture.supplyAsync(
                () -> removeByAddress("connectionguard_vpn_cache", ipAddress)
        );
    }

    @Override
    public CompletableFuture<Boolean> removeGeoResult(String ipAddress) {
        return CompletableFuture.supplyAsync(
                () -> removeByAddress("connectionguard_geo_cache", ipAddress)
        );
    }

    @Override
    public CompletableFuture<Boolean> removeAllVpnResults() {
        return CompletableFuture.supplyAsync(
                () -> clearTable("connectionguard_vpn_cache")
        );
    }

    @Override
    public CompletableFuture<Boolean> removeAllGeoResults() {
        return CompletableFuture.supplyAsync(
                () -> clearTable("connectionguard_geo_cache")
        );
    }

    private boolean removeByAddress(String tableName, String ipAddress) {
        synchronized (databaseLock) {
            String sql = "DELETE FROM " + tableName + " WHERE address=?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, ipAddress);
                statement.executeUpdate();
                return true;
            } catch (SQLException e) {
                ConnectionGuard.reportError("SQLite cache entry remove", e);
                return false;
            }
        }
    }

    private boolean clearTable(String tableName) {
        synchronized (databaseLock) {
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.executeUpdate("DELETE FROM " + tableName);
                }
                return true;
            } catch (SQLException e) {
                ConnectionGuard.reportError("SQLite cache clear", e);
                return false;
            }
        }
    }
}
