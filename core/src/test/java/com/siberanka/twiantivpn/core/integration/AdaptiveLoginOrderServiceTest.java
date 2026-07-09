package com.siberanka.twiantivpn.core.integration;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Collections;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdaptiveLoginOrderServiceTest {
    @Test
    void keepsConfiguredFixedOrders() {
        AtomicLong clock = new AtomicLong();
        AdaptiveLoginOrderService service = new AdaptiveLoginOrderService(clock::get);

        service.configure(false, true, 30, true, 60, 15,
                null, null, null, null, null, Collections.emptySet());
        service.setSonarStatus(true, false);
        assertFalse(service.shouldRunBeforeAntiBot());

        service.configure(true, false, 30, true, 60, 15,
                null, null, null, null, null, Collections.emptySet());
        service.onSonarAttackDetected();
        assertTrue(service.shouldRunBeforeAntiBot());
    }

    @Test
    void defersDuringAttackAndRecoveryWindow() {
        AtomicLong clock = new AtomicLong();
        AdaptiveLoginOrderService service = new AdaptiveLoginOrderService(clock::get);
        service.configure(true, true, 30, true, 60, 15,
                null, null, null, null, null,
                Collections.singleton(CheckModule.PROXY_BLOCKLIST));
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
        service.configure(true, true, 30, true, 60, 15,
                null, null, null, null, null,
                Collections.singleton(CheckModule.PROXY_BLOCKLIST));
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
        service.configure(true, true, 30, true, 60, 15,
                null, null, null, null, null,
                Collections.singleton(CheckModule.PROXY_BLOCKLIST));
        service.setSonarStatus(false, false);

        assertTrue(service.shouldRunBeforeAntiBot());
    }

    @Test
    void splitsConfiguredModulesOnlyDuringNormalAdaptiveTraffic() {
        AdaptiveLoginOrderService service = new AdaptiveLoginOrderService(() -> 0L);
        EnumSet<CheckModule> early = EnumSet.of(
                CheckModule.PROXY_BLOCKLIST,
                CheckModule.VPN_IP_API
        );
        service.configure(true, true, 30, true, 60, 15,
                null, null, null, null, null, early);
        service.setSonarStatus(true, false);

        assertTrue(service.sonarEarlyModules().containsAll(early));
        AdaptiveLoginOrderService.ModulePlan normalPlan = service.snapshotModulePlan();
        assertFalse(normalPlan.isRunBeforePlatform());
        assertTrue(normalPlan.getAfterPlatformModules().contains(CheckModule.PROXY_BLOCKLIST));
        assertTrue(normalPlan.getAfterPlatformModules().contains(CheckModule.VPN_IP_API));
        assertTrue(normalPlan.getAfterPlatformModules().contains(CheckModule.VPN_PROXYCHECK));

        service.onSonarAttackDetected();
        assertTrue(service.sonarEarlyModules().isEmpty());
        AdaptiveLoginOrderService.ModulePlan attackPlan = service.snapshotModulePlan();
        assertFalse(attackPlan.isRunBeforePlatform());
        assertTrue(attackPlan.getAfterPlatformModules().containsAll(
                EnumSet.allOf(CheckModule.class)
        ));
    }

    @Test
    void adaptiveDisabledRunsEveryModuleBeforePlatform() {
        AdaptiveLoginOrderService service = new AdaptiveLoginOrderService(() -> 0L);
        service.configure(
                true,
                false,
                30,
                true,
                60,
                15,
                null,
                null,
                null,
                null,
                null,
                Collections.singleton(CheckModule.PROXY_BLOCKLIST)
        );
        service.setSonarStatus(true, true);

        AdaptiveLoginOrderService.ModulePlan plan = service.snapshotModulePlan();
        assertTrue(plan.isRunBeforePlatform());
        assertTrue(plan.getBeforePlatformModules().containsAll(
                EnumSet.allOf(CheckModule.class)
        ));
    }

    @Test
    void localPreSonarBlocksTriggerAttackRoutingAndRecovery() {
        AtomicLong clock = new AtomicLong();
        AdaptiveLoginOrderService service = new AdaptiveLoginOrderService(clock::get);
        service.configure(true, true, 10, true, 60, 3,
                null, null, null, null, null,
                Collections.singleton(CheckModule.PROXY_BLOCKLIST));
        service.setSonarStatus(true, false);

        service.recordPreSonarBlock();
        service.recordPreSonarBlock();
        assertTrue(service.shouldRunBeforeAntiBot());

        service.recordPreSonarBlock();
        assertFalse(service.shouldRunBeforeAntiBot());
        assertTrue(service.sonarEarlyModules().isEmpty());
        assertTrue(service.snapshotModulePlan().getAfterPlatformModules().containsAll(
                EnumSet.allOf(CheckModule.class)
        ));

        clock.addAndGet(TimeUnit.SECONDS.toNanos(59));
        assertFalse(service.shouldRunBeforeAntiBot());

        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        assertFalse(service.shouldRunBeforeAntiBot());

        clock.addAndGet(TimeUnit.SECONDS.toNanos(10));
        assertTrue(service.shouldRunBeforeAntiBot());
    }

    @Test
    void oldLocalPreSonarBlocksDoNotTriggerOutsideWindow() {
        AtomicLong clock = new AtomicLong();
        AdaptiveLoginOrderService service = new AdaptiveLoginOrderService(clock::get);
        service.configure(true, true, 10, true, 60, 3,
                null, null, null, null, null,
                Collections.singleton(CheckModule.PROXY_BLOCKLIST));
        service.setSonarStatus(true, false);

        service.recordPreSonarBlock();
        service.recordPreSonarBlock();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(60));
        service.recordPreSonarBlock();

        assertTrue(service.shouldRunBeforeAntiBot());
    }
}
