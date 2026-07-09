package com.siberanka.twiantivpn.core.cache;

import com.siberanka.twiantivpn.core.geo.GeoResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SQLiteCacheProviderTest {
    @TempDir
    Path tempDir;

    @Test
    void upgradesLegacyNonUniqueIndexesBeforeUpsert() throws Exception {
        Path database = tempDir.resolve("cache.db");
        Class.forName("org.sqlite.JDBC");
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE connectionguard_vpn_cache (address TEXT, vpn BOOLEAN, cached_on INTEGER)");
            statement.execute("CREATE TABLE connectionguard_geo_cache (address TEXT, country_name TEXT, city_name TEXT, isp_name TEXT, cached_on INTEGER)");
            statement.execute("CREATE INDEX vpn_address ON connectionguard_vpn_cache (address)");
            statement.execute("CREATE INDEX geo_address ON connectionguard_geo_cache (address)");
            statement.execute("INSERT INTO connectionguard_geo_cache VALUES ('1.1.1.1', 'old', '', '', 1)");
            statement.execute("INSERT INTO connectionguard_geo_cache VALUES ('1.1.1.1', 'newer', '', '', 2)");
        }

        SQLiteCacheProvider provider = new SQLiteCacheProvider(database.toString());
        assertTrue(provider.setup().join());
        provider.addGeoResult(new GeoResult(
                "1.1.1.1", "Turkey", "Istanbul", "Example ISP", "AS64500", "Example Org"
        )).join();

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COUNT(*) AS amount, MAX(country_name) AS country FROM connectionguard_geo_cache WHERE address='1.1.1.1'"
             )) {
            assertTrue(result.next());
            assertEquals(1, result.getInt("amount"));
            assertEquals("Turkey", result.getString("country"));
        } finally {
            assertTrue(provider.disband().join());
        }
    }
}
