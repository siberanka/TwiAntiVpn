package com.siberanka.twiantivpn.core.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionRateLimiterTest {
    @Test
    void suppressesRepeatedSideEffectsButKeepsActionTypesIndependent() {
        ActionRateLimiter limiter = new ActionRateLimiter();
        limiter.configure(5);

        assertTrue(limiter.tryAcquire("vpn", "203.0.113.10"));
        assertFalse(limiter.tryAcquire("vpn", "203.0.113.10"));
        assertTrue(limiter.tryAcquire("isp", "203.0.113.10"));
        assertTrue(limiter.tryAcquire("vpn", "203.0.113.11"));
    }

    @Test
    void reconfigurationClearsPreviousCooldownState() {
        ActionRateLimiter limiter = new ActionRateLimiter();
        limiter.configure(5);
        assertTrue(limiter.tryAcquire("vpn", "203.0.113.10"));

        limiter.configure(10);
        assertTrue(limiter.tryAcquire("vpn", "203.0.113.10"));
    }
}
