package com.siberanka.twiantivpn.core;

import com.siberanka.twiantivpn.core.vpn.VpnProvider;
import com.siberanka.twiantivpn.core.vpn.VpnResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionGuardConcurrencyTest {
    @AfterEach
    void resetProviders() {
        ConnectionGuard.setVpnProviders(Collections.emptyMap());
    }

    @Test
    void coalescesConcurrentChecksForTheSameIpAndProviderSet() throws Exception {
        AtomicInteger providerCalls = new AtomicInteger();
        CountDownLatch providerStarted = new CountDownLatch(1);
        CompletableFuture<Optional<VpnResult>> providerResult = new CompletableFuture<>();
        VpnProvider provider = ipAddress -> {
            providerCalls.incrementAndGet();
            providerStarted.countDown();
            return providerResult;
        };
        Map<String, VpnProvider> providers = new LinkedHashMap<>();
        providers.put("test", provider);
        ConnectionGuard.setVpnProviders(providers);
        ConnectionGuard.setRequiredPositiveFlags(1);

        CompletableFuture<VpnResult> first = ConnectionGuard.getVpnResult(
                "203.0.113.10",
                false,
                Collections.singleton("test")
        );
        CompletableFuture<VpnResult> second = ConnectionGuard.getVpnResult(
                "203.0.113.10",
                false,
                Collections.singleton("test")
        );

        assertSame(first, second);
        assertTrue(providerStarted.await(2, TimeUnit.SECONDS));
        assertEquals(1, providerCalls.get());

        providerResult.complete(Optional.of(new VpnResult("203.0.113.10", true)));
        assertTrue(first.get(2, TimeUnit.SECONDS).isVpn());
    }
}
