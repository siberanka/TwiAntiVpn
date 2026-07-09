package com.siberanka.twiantivpn.core.integration;

import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

public final class AdaptiveLoginOrderService {
    private static final int DEFAULT_RECOVERY_DELAY_SECONDS = 30;
    private static final int MIN_RECOVERY_DELAY_SECONDS = 5;
    private static final int MAX_RECOVERY_DELAY_SECONDS = 600;
    private static final AdaptiveLoginOrderService INSTANCE =
            new AdaptiveLoginOrderService(System::nanoTime);

    private final LongSupplier nanoTime;
    private final Object stateLock = new Object();

    private volatile boolean configuredBeforeAntiBot = true;
    private volatile boolean adaptiveEnabled = true;
    private volatile boolean sonarAvailable;
    private volatile State state = State.NORMAL;
    private volatile long recoveryDelayNanos =
            TimeUnit.SECONDS.toNanos(DEFAULT_RECOVERY_DELAY_SECONDS);
    private volatile long recoveryDeadlineNanos;
    private volatile Logger logger;
    private volatile String attackLogMessage;
    private volatile String recoveryLogMessage;
    private volatile String normalLogMessage;
    private volatile Set<CheckModule> configuredBeforeModules =
            Collections.singleton(CheckModule.PROXY_BLOCKLIST);

    AdaptiveLoginOrderService(LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    public static AdaptiveLoginOrderService getInstance() {
        return INSTANCE;
    }

    public void configure(boolean beforeAntiBot,
                          boolean adaptiveEnabled,
                          int recoveryDelaySeconds,
                          Logger logger,
                          String attackLogMessage,
                          String recoveryLogMessage,
                          String normalLogMessage,
                          Set<CheckModule> beforeModules) {
        int boundedRecoveryDelay = boundRecoveryDelay(recoveryDelaySeconds);
        synchronized (stateLock) {
            this.configuredBeforeAntiBot = beforeAntiBot;
            this.adaptiveEnabled = adaptiveEnabled;
            this.recoveryDelayNanos = TimeUnit.SECONDS.toNanos(boundedRecoveryDelay);
            this.logger = logger;
            this.attackLogMessage = attackLogMessage;
            this.recoveryLogMessage = recoveryLogMessage;
            this.normalLogMessage = normalLogMessage;
            this.configuredBeforeModules = CheckModule.immutableCopy(beforeModules);
            if (!beforeAntiBot || !adaptiveEnabled) {
                state = State.NORMAL;
                recoveryDeadlineNanos = 0L;
            }
        }
    }

    public void setSonarStatus(boolean available, boolean underAttack) {
        synchronized (stateLock) {
            sonarAvailable = available;
            if (!available || !isAdaptiveActive()) {
                return;
            }
            if (underAttack) {
                enterAttackModeLocked();
            } else if (state == State.ATTACK) {
                enterRecoveryModeLocked();
            }
        }
    }

    public void onSonarAttackDetected() {
        synchronized (stateLock) {
            sonarAvailable = true;
            if (isAdaptiveActive()) {
                enterAttackModeLocked();
            }
        }
    }

    public void onSonarAttackMitigated() {
        synchronized (stateLock) {
            if (isAdaptiveActive() && state == State.ATTACK) {
                enterRecoveryModeLocked();
            }
        }
    }

    public boolean shouldRunBeforeAntiBot() {
        if (!configuredBeforeAntiBot) {
            return false;
        }
        if (!adaptiveEnabled || !sonarAvailable) {
            return true;
        }
        if (state == State.ATTACK) {
            return false;
        }
        if (state != State.RECOVERY) {
            return true;
        }

        synchronized (stateLock) {
            if (state == State.RECOVERY
                    && nanoTime.getAsLong() - recoveryDeadlineNanos >= 0L) {
                state = State.NORMAL;
                recoveryDeadlineNanos = 0L;
                log(normalLogMessage);
            }
            return state == State.NORMAL;
        }
    }

    public boolean isDeferringToSonar() {
        return configuredBeforeAntiBot
                && adaptiveEnabled
                && sonarAvailable
                && !shouldRunBeforeAntiBot();
    }

    public ModulePlan snapshotModulePlan() {
        if (!configuredBeforeAntiBot) {
            return ModulePlan.afterAll();
        }
        if (!adaptiveEnabled || !sonarAvailable) {
            return ModulePlan.beforeAll();
        }
        if (!shouldRunBeforeAntiBot()) {
            return ModulePlan.afterAll();
        }

        // Sonar's API event is asynchronous. Re-evaluate every policy after Sonar
        // using provider-level single-flight results so a delayed early hook cannot
        // create a fail-open window or duplicate outbound requests.
        return new ModulePlan(
                configuredBeforeModules,
                EnumSet.allOf(CheckModule.class),
                false
        );
    }

    public Set<CheckModule> sonarEarlyModules() {
        if (!configuredBeforeAntiBot) {
            return Collections.emptySet();
        }
        if (!adaptiveEnabled) {
            return CheckModule.immutableCopy(EnumSet.allOf(CheckModule.class));
        }
        if (!sonarAvailable || !shouldRunBeforeAntiBot()) {
            return Collections.emptySet();
        }
        return configuredBeforeModules;
    }

    private boolean isAdaptiveActive() {
        return configuredBeforeAntiBot && adaptiveEnabled;
    }

    private void enterAttackModeLocked() {
        if (state != State.ATTACK) {
            state = State.ATTACK;
            recoveryDeadlineNanos = 0L;
            log(attackLogMessage);
        }
    }

    private void enterRecoveryModeLocked() {
        state = State.RECOVERY;
        recoveryDeadlineNanos = saturatingAdd(nanoTime.getAsLong(), recoveryDelayNanos);
        log(recoveryLogMessage);
    }

    private int boundRecoveryDelay(int configuredSeconds) {
        if (configuredSeconds <= 0) {
            return DEFAULT_RECOVERY_DELAY_SECONDS;
        }
        return Math.max(MIN_RECOVERY_DELAY_SECONDS,
                Math.min(MAX_RECOVERY_DELAY_SECONDS, configuredSeconds));
    }

    private long saturatingAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private void log(String message) {
        Logger currentLogger = logger;
        if (currentLogger != null && message != null && !message.trim().isEmpty()) {
            currentLogger.info("TwiAntiVpn | Adaptive login order | " + message);
        }
    }

    private enum State {
        NORMAL,
        ATTACK,
        RECOVERY
    }

    public static final class ModulePlan {
        private final Set<CheckModule> beforePlatformModules;
        private final Set<CheckModule> afterPlatformModules;
        private final boolean runBeforePlatform;

        private ModulePlan(Set<CheckModule> beforePlatformModules,
                           Set<CheckModule> afterPlatformModules,
                           boolean runBeforePlatform) {
            this.beforePlatformModules = CheckModule.immutableCopy(beforePlatformModules);
            this.afterPlatformModules = CheckModule.immutableCopy(afterPlatformModules);
            this.runBeforePlatform = runBeforePlatform;
        }

        private static ModulePlan beforeAll() {
            return new ModulePlan(
                    EnumSet.allOf(CheckModule.class),
                    Collections.emptySet(),
                    true
            );
        }

        private static ModulePlan afterAll() {
            return new ModulePlan(
                    Collections.emptySet(),
                    EnumSet.allOf(CheckModule.class),
                    false
            );
        }

        public Set<CheckModule> getBeforePlatformModules() {
            return beforePlatformModules;
        }

        public Set<CheckModule> getAfterPlatformModules() {
            return afterPlatformModules;
        }

        public boolean isRunBeforePlatform() {
            return runBeforePlatform;
        }
    }
}
