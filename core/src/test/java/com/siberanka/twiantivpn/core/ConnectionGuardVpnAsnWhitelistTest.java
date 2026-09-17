package com.siberanka.twiantivpn.core;

import com.siberanka.twiantivpn.core.geo.GeoResult;
import com.siberanka.twiantivpn.core.vpn.VpnResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionGuardVpnAsnWhitelistTest {
    @AfterEach
    void disableWhitelist() {
        ConnectionGuard.configureVpnAsnWhitelist(false, Collections.emptyList());
    }

    @Test
    void bypassesOnlyPositiveVpnResultsFromAnExactWhitelistedAsn() {
        ConnectionGuard.configureVpnAsnWhitelist(true, Collections.singletonList("AS9121"));
        GeoResult whitelistedGeo = geo("AS9121 TTNet Turk Telekomunikasyon A.S.");

        assertTrue(ConnectionGuard.isVpnAsnWhitelisted(
                "203.0.113.10",
                new VpnResult("203.0.113.10", true),
                Optional.of(whitelistedGeo)
        ).join());
        assertFalse(ConnectionGuard.isVpnAsnWhitelisted(
                "203.0.113.10",
                new VpnResult("203.0.113.10", false),
                Optional.of(whitelistedGeo)
        ).join());
        assertFalse(ConnectionGuard.isVpnAsnWhitelisted(
                "203.0.113.10",
                new VpnResult("203.0.113.10", true),
                Optional.of(geo("AS91210 Different network"))
        ).join());
    }

    @Test
    void disabledWhitelistNeverBypassesVpnResults() {
        ConnectionGuard.configureVpnAsnWhitelist(false, Collections.singletonList("AS9121"));

        assertFalse(ConnectionGuard.isVpnAsnWhitelisted(
                "203.0.113.10",
                new VpnResult("203.0.113.10", true),
                Optional.of(geo("AS9121"))
        ).join());
    }

    private GeoResult geo(String asn) {
        return new GeoResult("203.0.113.10", "TR", "Istanbul", "ISP", asn, "Organization");
    }
}
