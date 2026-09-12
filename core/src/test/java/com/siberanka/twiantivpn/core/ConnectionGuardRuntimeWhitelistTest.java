package com.siberanka.twiantivpn.core;

import com.siberanka.twiantivpn.core.vpn.VpnProvider;
import com.siberanka.twiantivpn.core.vpn.VpnResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionGuardRuntimeWhitelistTest {
    @AfterEach
    void resetProviders() {
        ConnectionGuard.setVpnProviders(Collections.emptyMap());
    }

    @Test
    void storesOnlyValidIpLiterals() {
        assertFalse(ConnectionGuard.addRuntimeWhitelistedIp("not-an-ip"));
        assertFalse(ConnectionGuard.isRuntimeWhitelistedIp("not-an-ip"));

        assertTrue(ConnectionGuard.addRuntimeWhitelistedIp("198.51.100.42"));
        assertTrue(ConnectionGuard.isRuntimeWhitelistedIp("198.51.100.42"));
        assertFalse(ConnectionGuard.addRuntimeWhitelistedIp("198.51.100.42"));
    }

    @Test
    void matchesEquivalentIpv6Representations() {
        assertTrue(ConnectionGuard.addRuntimeWhitelistedIp("2001:0db8:0:0:0:0:0:77"));
        assertTrue(ConnectionGuard.isRuntimeWhitelistedIp("2001:db8::77"));
    }

    @Test
    void bypassesVpnProvidersForWhitelistedIps() {
        AtomicInteger providerCalls = new AtomicInteger();
        VpnProvider provider = ipAddress -> {
            providerCalls.incrementAndGet();
            return CompletableFuture.completedFuture(
                    Optional.of(new VpnResult(ipAddress, true))
            );
        };
        ConnectionGuard.setVpnProviders(Collections.singletonMap("test", provider));

        String ipAddress = "192.0.2.222";
        assertTrue(ConnectionGuard.addRuntimeWhitelistedIp(ipAddress));
        VpnResult result = ConnectionGuard.getVpnResult(
                ipAddress,
                true,
                Collections.singleton("test")
        ).join();

        assertFalse(result.isVpn());
        assertFalse(ConnectionGuard.getGeoResult(ipAddress).join().isPresent());
        assertEquals(0, providerCalls.get());
    }
}
