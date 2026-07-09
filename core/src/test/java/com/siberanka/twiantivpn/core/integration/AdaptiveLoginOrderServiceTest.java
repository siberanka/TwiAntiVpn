package com.siberanka.twiantivpn.core.integration;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveLoginOrderServiceTest {
    @Test
    void keepsConfiguredFixedOrders() {
        AtomicLong clock = new AtomicLong();
        AdaptiveLoginOrderService service = new AdaptiveLoginOrderService(clock::get);

        service.configure(false, true, 30, null, null, null, null);
        service.setSonarStatus(true, false);
        assertFalse(service.shouldRunBeforeAntiBot());

        service.configure(true, false, 30, null, null, null, null);
        service.onSonarAttackDetected();
        assertTrue(service.shouldRunBeforeAntiBot());
    }

    @Test
    void defersDuringAttackAndRecoveryWindow() {
        AtomicLong clock = new AtomicLong();
        AdaptiveLoginOrderService service = new AdaptiveLoginOrderService(clock::get);
        service.configure(true, true, 30, null, null, null, null);
        service.setSonarStatus(true, false);

        assertTrue(service.shouldRunBeforeAntiBot());

        service.onSonarAttackDetected();
        assertFalse(service.shouldRunBeforeAntiBot());

        service.onSonarAttackMitigated();
        assertFalse(service.shouldRunBeforeAntiBot());

        clock.addAndGet(TimeUnit.SECONDS.toNanos(29));
        assertFalse(service.shouldRunBeforeAntiBot());

        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertTrue(service.shouldRunBeforeAntiBot());
    }

    @Test
    void newAttackRestartsRecoveryWindow() {
        AtomicLong clock = new AtomicLong();
        AdaptiveLoginOrderService service = new AdaptiveLoginOrderService(clock::get);
        service.configure(true, true, 30, null, null, null, null);
        service.setSonarStatus(true, true);
        service.onSonarAttackMitigated();

        clock.addAndGet(TimeUnit.SECONDS.toNanos(20));
        service.onSonarAttackDetected();
        service.onSonarAttackMitigated();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(20));
        assertFalse(service.shouldRunBeforeAntiBot());

        clock.addAndGet(TimeUnit.SECONDS.toNanos(10));
        assertTrue(service.shouldRunBeforeAntiBot());
    }

    @Test
    void missingSonarDoesNotDelayNormalBeforeMode() {
        AdaptiveLoginOrderService service = new AdaptiveLoginOrderService(() -> 0L);
        service.configure(true, true, 30, null, null, null, null);
        service.setSonarStatus(false, false);

        assertTrue(service.shouldRunBeforeAntiBot());
    }
}
