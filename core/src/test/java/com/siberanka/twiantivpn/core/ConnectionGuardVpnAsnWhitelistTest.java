package com.siberanka.twiantivpn.core;

import com.siberanka.twiantivpn.core.geo.GeoResult;
import com.siberanka.twiantivpn.core.vpn.VpnProvider;
import com.siberanka.twiantivpn.core.vpn.VpnResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionGuardVpnAsnWhitelistTest {
    private static final List<String> NONE = Collections.emptyList();

    @AfterEach
    void disableWhitelist() {
        ConnectionGuard.configureVpnAsnWhitelist(false, Collections.<String>emptyList());
        ConnectionGuard.setVpnProviders(Collections.<String, VpnProvider>emptyMap());
        ConnectionGuard.shutdownProxyBlocklist();
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

    @Test
    void defaultBuiltInCountriesTrustLocalIspsWithoutConfiguredAsns() {
        ConnectionGuard.configureVpnAsnWhitelist(true, ConnectionGuard.getDefaultTrustedIspCountries(),
                NONE, NONE, true, true);

        assertEquals(168, ConnectionGuard.getTrustedIspAsnCount());
        for (String asn : Arrays.asList("AS9121", "AS8814", "AS9198", "AS8193", "AS47237", "AS20661")) {
            assertTrue(ConnectionGuard.isVpnAsnWhitelisted(
                    "203.0.113.10",
                    new VpnResult("203.0.113.10", true),
                    Optional.of(geo(asn))
            ).join(), asn);
        }
        assertFalse(ConnectionGuard.isVpnAsnWhitelisted(
                "203.0.113.10",
                new VpnResult("203.0.113.10", true),
                Optional.of(geo("AS16276 OVH SAS"))
        ).join());
    }

    @Test
    void hostingAndAnonymizerEvidenceInsideTrustedIspsStillBlocks() {
        ConnectionGuard.configureVpnAsnWhitelist(true, Collections.singletonList("TR"), NONE, NONE, true, true);

        assertFalse(ConnectionGuard.isVpnAsnWhitelisted(
                "203.0.113.10",
                new VpnResult("203.0.113.10", true),
                Optional.of(new GeoResult("203.0.113.10", "TR", "Istanbul", "ISP", "AS9121", "Org", Boolean.TRUE))
        ).join(), "datacenter address inside a residential ASN");
        assertFalse(ConnectionGuard.isVpnAsnWhitelisted(
                "203.0.113.10",
                new VpnResult("203.0.113.10", true).setAnonymizer(true),
                Optional.of(geo("AS9121"))
        ).join(), "Tor exit or named VPN operator");
    }

    @Test
    void anonymizerEvidenceFromAnyPositiveProviderIsKeptInTheAggregate() throws Exception {
        Map<String, VpnProvider> providers = new LinkedHashMap<>();
        providers.put("guess", ip -> CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, true))));
        providers.put("tor", ip -> CompletableFuture.completedFuture(
                Optional.of(new VpnResult(ip, true).setAnonymizer(true))));
        ConnectionGuard.setVpnProviders(providers);
        ConnectionGuard.setRequiredPositiveFlags(1);

        VpnResult both = ConnectionGuard.getVpnResult("203.0.113.21", false,
                new HashSet<>(Arrays.asList("guess", "tor"))).get(5, TimeUnit.SECONDS);
        VpnResult guessOnly = ConnectionGuard.getVpnResult("203.0.113.22", false,
                Collections.singleton("guess")).get(5, TimeUnit.SECONDS);

        assertTrue(both.isVpn());
        assertTrue(both.isAnonymizer());
        assertTrue(guessOnly.isVpn());
        assertFalse(guessOnly.isAnonymizer());
    }

    @Test
    void negativeAggregateNeverCarriesAnonymizerEvidence() throws Exception {
        Map<String, VpnProvider> providers = new LinkedHashMap<>();
        providers.put("tor", ip -> CompletableFuture.completedFuture(
                Optional.of(new VpnResult(ip, true).setAnonymizer(true))));
        providers.put("clean", ip -> CompletableFuture.completedFuture(Optional.of(new VpnResult(ip, false))));
        ConnectionGuard.setVpnProviders(providers);
        ConnectionGuard.setRequiredPositiveFlags(2);
        try {
            VpnResult result = ConnectionGuard.getVpnResult("203.0.113.23", false,
                    new HashSet<>(Arrays.asList("tor", "clean"))).get(5, TimeUnit.SECONDS);
            assertFalse(result.isVpn());
            assertFalse(result.isAnonymizer());
        } finally {
            ConnectionGuard.setRequiredPositiveFlags(1);
        }
    }

    @Test
    void reputationBlocklistHitsAreExemptedButAnonymizerSourcesAreNot() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serve(server, "/tor-exit-nodes.txt", "198.51.100.7\n");
        serve(server, "/abuse/all.txt", "192.0.2.44\n");
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ConnectionGuard.configureProxyBlocklist(true,
                    Arrays.asList(base + "/tor-exit-nodes.txt", base + "/abuse/all.txt"), 60, 1000, 512, 5, 100);
            long deadline = System.currentTimeMillis() + 15000L;
            while (ConnectionGuard.getProxyBlocklistService().size() < 2 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50L);
            }
            ConnectionGuard.configureVpnAsnWhitelist(true, Collections.singletonList("TR"), NONE, NONE, true, true);

            VpnResult torHit = ConnectionGuard.getVpnResult("198.51.100.7", true, Collections.<String>emptySet()).join();
            VpnResult abuseHit = ConnectionGuard.getVpnResult("192.0.2.44", true, Collections.<String>emptySet()).join();

            assertTrue(torHit.isVpn());
            assertTrue(torHit.isAnonymizer());
            assertTrue(abuseHit.isVpn());
            assertFalse(abuseHit.isAnonymizer());
            assertFalse(ConnectionGuard.isVpnAsnWhitelisted("198.51.100.7", torHit, Optional.of(geo("AS9121"))).join());
            assertTrue(ConnectionGuard.isVpnAsnWhitelisted("192.0.2.44", abuseHit, Optional.of(geo("AS9121"))).join());
        } finally {
            server.stop(0);
        }
    }

    private static void serve(HttpServer server, String path, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
    }

    private GeoResult geo(String asn) {
        return new GeoResult("203.0.113.10", "TR", "Istanbul", "ISP", asn, "Organization");
    }
}
