package com.siberanka.twiantivpn.core.integration;

import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.geo.GeoResult;
import com.siberanka.twiantivpn.core.isp.IspBlockResult;
import com.siberanka.twiantivpn.core.vpn.VpnResult;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;

public final class SonarApiEarlyCheckHook {
    private static final int DEFAULT_CHECK_TIMEOUT_SECONDS = 6;
    private static final int MIN_CHECK_TIMEOUT_SECONDS = 1;
    private static final int MAX_CHECK_TIMEOUT_SECONDS = 7;
    private static final AtomicReference<Registration> REGISTRATION = new AtomicReference<>();

    private SonarApiEarlyCheckHook() {
    }

    public static void install(Logger logger,
                               int checkTimeoutSeconds,
                               BiPredicate<String, String> vpnExemption,
                               BiPredicate<String, String> geoExemption,
                               Predicate<GeoResult> geoBlocked,
                               Function<Result, String> disconnectMessage) {
        uninstall(logger);
        ScheduledThreadPoolExecutor installationExecutor = null;
        try {
            Class<?> sonarClass = Class.forName("xyz.jonesdev.sonar.api.Sonar");
            Class<?> listenerClass = Class.forName("xyz.jonesdev.sonar.api.event.SonarEventListener");
            Object sonar = sonarClass.getMethod("get").invoke(null);
            Object eventManager = sonar.getClass().getMethod("getEventManager").invoke(sonar);
            installationExecutor = createTimeoutExecutor();
            final ScheduledThreadPoolExecutor timeoutExecutor = installationExecutor;
            final Set<PendingCheck> pendingChecks =
                    Collections.newSetFromMap(new ConcurrentHashMap<PendingCheck, Boolean>());
            AdaptiveLoginOrderService.getInstance().setSonarStatus(
                    true,
                    isSonarUnderAttack(sonar)
            );
            Object listener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class[]{listenerClass},
                    (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            switch (method.getName()) {
                                case "equals":
                                    return args != null && args.length == 1 && proxy == args[0];
                                case "hashCode":
                                    return System.identityHashCode(proxy);
                                case "toString":
                                    return "TwiAntiVpnSonarEventListener";
                                default:
                                    return null;
                            }
                        }
                        if ("handle".equals(method.getName()) && args != null && args.length == 1) {
                            handleEvent(
                                    args[0],
                                    vpnExemption,
                                    geoExemption,
                                    geoBlocked,
                                    disconnectMessage,
                                    boundedTimeout(checkTimeoutSeconds),
                                    timeoutExecutor,
                                    pendingChecks,
                                    logger
                            );
                        }
                        return null;
                    }
            );

            Object listenerArray = Array.newInstance(listenerClass, 1);
            Array.set(listenerArray, 0, listener);
            Method register = eventManager.getClass().getMethod("registerListener", listenerArray.getClass());
            register.invoke(eventManager, listenerArray);
            REGISTRATION.set(new Registration(
                    eventManager,
                    listener,
                    listenerArray.getClass(),
                    timeoutExecutor,
                    pendingChecks
            ));
            if (logger != null) {
                logger.info("TwiAntiVpn | Sonar early VPN hook enabled.");
            }
        } catch (ClassNotFoundException ignored) {
            // Sonar is not installed on this platform.
        } catch (Throwable throwable) {
            if (installationExecutor != null) {
                installationExecutor.shutdownNow();
            }
            ConnectionGuard.reportError("Sonar early hook install", throwable);
        }
    }

    public static void uninstall(Logger logger) {
        Registration registration = REGISTRATION.getAndSet(null);
        AdaptiveLoginOrderService.getInstance().setSonarStatus(false, false);
        if (registration == null) {
            return;
        }
        try {
            Object listenerArray = Array.newInstance(registration.listener.getClass().getInterfaces()[0], 1);
            Array.set(listenerArray, 0, registration.listener);
            Method unregister = registration.eventManager.getClass().getMethod("unregisterListener", registration.arrayType);
            unregister.invoke(registration.eventManager, listenerArray);
        } catch (Throwable throwable) {
            ConnectionGuard.reportError("Sonar early hook uninstall", throwable);
        } finally {
            for (PendingCheck pendingCheck : registration.pendingChecks) {
                pendingCheck.abortForShutdown();
            }
            registration.timeoutExecutor.shutdownNow();
        }
    }

    private static void handleEvent(Object event,
                                    BiPredicate<String, String> vpnExemption,
                                    BiPredicate<String, String> geoExemption,
                                    Predicate<GeoResult> geoBlocked,
                                    Function<Result, String> disconnectMessage,
                                    int checkTimeoutSeconds,
                                    ScheduledThreadPoolExecutor timeoutExecutor,
                                    Set<PendingCheck> pendingChecks,
                                    Logger logger) {
        if (event == null || !"UserVerifyJoinEvent".equals(event.getClass().getSimpleName())) {
            if (event != null && "AttackDetectedEvent".equals(event.getClass().getSimpleName())) {
                AdaptiveLoginOrderService.getInstance().onSonarAttackDetected();
            } else if (event != null && "AttackMitigatedEvent".equals(event.getClass().getSimpleName())) {
                AdaptiveLoginOrderService.getInstance().onSonarAttackMitigated();
            }
            return;
        }
        try {
            Set<CheckModule> modules =
                    AdaptiveLoginOrderService.getInstance().sonarEarlyModules();
            if (modules.isEmpty()) {
                return;
            }
            Object user = event.getClass().getMethod("getUser").invoke(event);
            String username = String.valueOf(user.getClass().getMethod("getUsername").invoke(user));
            InetAddress inetAddress = (InetAddress) user.getClass().getMethod("getInetAddress").invoke(user);
            String ipAddress = inetAddress.getHostAddress();
            ConnectionGate gate = ConnectionGate.pause(user);
            if (gate == null) {
                ConnectionGuard.reportError(
                        "Sonar pre-verification gate",
                        new IllegalStateException("Could not pause the Sonar connection before verification")
                );
                closeChannel(user);
                return;
            }
            PendingCheck pendingCheck = new PendingCheck(
                    user,
                    gate,
                    disconnectMessage,
                    checkTimeoutSeconds,
                    timeoutExecutor,
                    pendingChecks
            );

            if (modules.contains(CheckModule.USERNAME_FILTER)) {
                Optional<String> blockedUsernamePart = ConnectionGuard.getBlockedUsernamePart(username);
                if (blockedUsernamePart.isPresent()) {
                    pendingCheck.block(
                            Result.username(ipAddress, username, blockedUsernamePart.get()),
                            true
                    );
                    return;
                }
            }

            Set<String> providerNames = CheckModule.providerNames(modules);
            boolean checkVpn = modules.contains(CheckModule.PROXY_BLOCKLIST)
                    || !providerNames.isEmpty();
            boolean vpnExempt = vpnExemption != null && vpnExemption.test(ipAddress, username);
            CompletableFuture<VpnResult> vpnFuture = checkVpn && !vpnExempt
                    ? ConnectionGuard.getVpnResult(
                            ipAddress,
                            modules.contains(CheckModule.PROXY_BLOCKLIST),
                            providerNames
                    )
                    : CompletableFuture.completedFuture(new VpnResult(ipAddress, false));

            boolean checkGeo = modules.contains(CheckModule.GEO_BLOCK)
                    || modules.contains(CheckModule.ISP_BLOCK);
            boolean geoExempt = geoExemption != null && geoExemption.test(ipAddress, username);
            CompletableFuture<Optional<GeoResult>> geoFuture = checkGeo && !geoExempt
                    ? ConnectionGuard.getGeoResult(ipAddress)
                    : CompletableFuture.completedFuture(Optional.empty());

            if (!checkVpn && !checkGeo) {
                pendingCheck.allow();
                return;
            }

            CompletableFuture.allOf(vpnFuture, geoFuture).whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    ConnectionGuard.reportError("Sonar early check", throwable);
                    pendingCheck.block(Result.checkFailed(ipAddress, username), false);
                    return;
                }
                try {
                    VpnResult vpnResult = vpnFuture.join();
                    if (vpnResult != null && vpnResult.isVpn()) {
                        pendingCheck.block(Result.vpn(ipAddress, username), true);
                        return;
                    }

                    Optional<GeoResult> geoResultOptional = geoFuture.join();
                    if (geoResultOptional.isPresent()) {
                        GeoResult geoResult = geoResultOptional.get();
                        if (modules.contains(CheckModule.ISP_BLOCK)) {
                            Optional<IspBlockResult> ispBlockResult =
                                    ConnectionGuard.getIspBlockResult(geoResult);
                            if (ispBlockResult.isPresent()) {
                                pendingCheck.block(
                                        Result.isp(ipAddress, username, ispBlockResult.get()),
                                        true
                                );
                                return;
                            }
                        }
                        if (modules.contains(CheckModule.GEO_BLOCK)
                                && geoBlocked != null
                                && geoBlocked.test(geoResult)) {
                            pendingCheck.block(Result.geo(ipAddress, username, geoResult), true);
                            return;
                        }
                    }
                    pendingCheck.allow();
                } catch (Throwable completionFailure) {
                    ConnectionGuard.reportError("Sonar early check completion", completionFailure);
                    pendingCheck.block(Result.checkFailed(ipAddress, username), false);
                }
            });
        } catch (Throwable throwable) {
            ConnectionGuard.reportError("Sonar early hook event", throwable);
        }
    }

    private static boolean isSonarUnderAttack(Object sonar) {
        try {
            Object attackTracker = sonar.getClass().getMethod("getAttackTracker").invoke(sonar);
            return attackTracker != null
                    && attackTracker.getClass().getMethod("getCurrentAttack").invoke(attackTracker) != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean disconnect(Object user,
                              Result result,
                              Function<Result, String> disconnectMessage) {
        try {
            String message = disconnectMessage == null ? "" : disconnectMessage.apply(result);
            Method disconnectMethod = findDisconnectMethod(user.getClass());
            Class<?> reasonType = disconnectMethod.getParameterTypes()[0];
            Object reason = createDisconnectReason(reasonType, message == null ? "" : message);
            disconnectMethod.invoke(user, reason);
            return true;
        } catch (Throwable throwable) {
            ConnectionGuard.reportError("Sonar pre-verification disconnect", throwable);
            return false;
        }
    }

    private static Method findDisconnectMethod(Class<?> userClass) throws NoSuchMethodException {
        Method fallback = null;
        for (Method method : userClass.getMethods()) {
            if (!"disconnect".equals(method.getName()) || method.getParameterTypes().length != 1) {
                continue;
            }
            Class<?> reasonType = method.getParameterTypes()[0];
            if (reasonType == String.class
                    || CharSequence.class.isAssignableFrom(reasonType)
                    || reasonType.getName().endsWith(".Component")) {
                return method;
            }
            fallback = method;
        }
        if (fallback != null) {
            return fallback;
        }
        throw new NoSuchMethodException(userClass.getName() + ".disconnect(<reason>)");
    }

    private static Object createDisconnectReason(Class<?> reasonType, String message)
            throws ReflectiveOperationException {
        if (reasonType == String.class || reasonType == CharSequence.class) {
            return message;
        }

        Object directComponent = invokeTextFactory(reasonType, message);
        if (directComponent != null && reasonType.isInstance(directComponent)) {
            return directComponent;
        }

        String packageName = reasonType.getPackage().getName();
        Class<?> componentType = Class.forName(
                packageName + ".Component",
                true,
                reasonType.getClassLoader()
        );
        Object component = invokeTextFactory(componentType, message);
        if (component != null && reasonType.isInstance(component)) {
            return component;
        }
        throw new IllegalArgumentException(
                "Unsupported Sonar disconnect reason type: " + reasonType.getName()
        );
    }

    private static Object invokeTextFactory(Class<?> componentType, String message)
            throws ReflectiveOperationException {
        Method text;
        try {
            text = componentType.getMethod("text", String.class);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
        if (!Modifier.isStatic(text.getModifiers())) {
            return null;
        }
        return text.invoke(null, message);
    }

    private static void closeChannel(Object user) {
        try {
            Object channel = user.getClass().getMethod("channel").invoke(user);
            channel.getClass().getMethod("close").invoke(channel);
        } catch (Throwable throwable) {
            ConnectionGuard.reportError("Sonar pre-verification channel close", throwable);
        }
    }

    private static void executeOnEventLoop(Object user, Runnable action) {
        try {
            Object channel = user.getClass().getMethod("channel").invoke(user);
            Object eventLoop = channel.getClass().getMethod("eventLoop").invoke(channel);
            Object pipeline = channel.getClass().getMethod("pipeline").invoke(channel);
            Object sonarEncoder = pipeline.getClass()
                    .getMethod("get", String.class)
                    .invoke(pipeline, "sonar-packet-encoder");
            if (sonarEncoder != null) {
                eventLoop.getClass().getMethod("execute", Runnable.class).invoke(eventLoop, action);
            } else {
                eventLoop.getClass()
                        .getMethod("schedule", Runnable.class, long.class, TimeUnit.class)
                        .invoke(eventLoop, action, 10L, TimeUnit.MILLISECONDS);
            }
        } catch (Throwable throwable) {
            ConnectionGuard.reportError("Sonar pre-verification event loop", throwable);
            action.run();
        }
    }

    static int boundedTimeout(int configuredSeconds) {
        if (configuredSeconds <= 0) {
            return DEFAULT_CHECK_TIMEOUT_SECONDS;
        }
        return Math.max(MIN_CHECK_TIMEOUT_SECONDS,
                Math.min(MAX_CHECK_TIMEOUT_SECONDS, configuredSeconds));
    }

    private static ScheduledThreadPoolExecutor createTimeoutExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "TwiAntiVpn-SonarGate");
                thread.setDaemon(true);
                return thread;
            }
        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        return executor;
    }

    public static final class Result {
        private final String type;
        private final String ipAddress;
        private final String username;
        private final String match;
        private final GeoResult geoResult;

        private Result(String type,
                       String ipAddress,
                       String username,
                       String match,
                       GeoResult geoResult) {
            this.type = type;
            this.ipAddress = ipAddress;
            this.username = username;
            this.match = match;
            this.geoResult = geoResult;
        }

        public static Result vpn(String ipAddress, String username) {
            return new Result("vpn", ipAddress, username, "", null);
        }

        public static Result username(String ipAddress, String username, String match) {
            return new Result("username", ipAddress, username, match, null);
        }

        public static Result geo(String ipAddress, String username, GeoResult geoResult) {
            return new Result("geo", ipAddress, username, "", geoResult);
        }

        public static Result isp(String ipAddress, String username, IspBlockResult ispBlockResult) {
            return new Result(
                    "isp",
                    ipAddress,
                    username,
                    ispBlockResult.getMatchedValue(),
                    ispBlockResult.getGeoResult()
            );
        }

        public static Result checkFailed(String ipAddress, String username) {
            return new Result("check-failed", ipAddress, username, "", null);
        }

        public String getType() {
            return type;
        }

        public String getIpAddress() {
            return ipAddress;
        }

        public String getUsername() {
            return username;
        }

        public String getMatch() {
            return match;
        }

        public String getCountry() {
            return geoResult == null ? "" : geoResult.getCountryName();
        }

        public String getCity() {
            return geoResult == null ? "" : geoResult.getCityName();
        }

        public String getIsp() {
            return geoResult == null ? "" : geoResult.getIspName();
        }

        public String getAsn() {
            return geoResult == null ? "" : geoResult.getAsn();
        }
    }

    private static final class PendingCheck {
        private final Object user;
        private final ConnectionGate gate;
        private final Function<Result, String> disconnectMessage;
        private final Set<PendingCheck> owner;
        private final AtomicBoolean completed = new AtomicBoolean();
        private final ScheduledFuture<?> timeoutTask;

        private PendingCheck(Object user,
                             ConnectionGate gate,
                             Function<Result, String> disconnectMessage,
                             int timeoutSeconds,
                             ScheduledThreadPoolExecutor timeoutExecutor,
                             Set<PendingCheck> owner) {
            this.user = user;
            this.gate = gate;
            this.disconnectMessage = disconnectMessage;
            this.owner = owner;
            owner.add(this);
            ScheduledFuture<?> scheduledTimeout;
            try {
                scheduledTimeout = timeoutExecutor.schedule(
                        () -> block(Result.checkFailed(gate.ipAddress, gate.username), false),
                        timeoutSeconds,
                        TimeUnit.SECONDS
                );
            } catch (Throwable throwable) {
                ConnectionGuard.reportError("Sonar pre-verification timeout schedule", throwable);
                scheduledTimeout = null;
            }
            this.timeoutTask = scheduledTimeout;
            if (scheduledTimeout == null) {
                block(Result.checkFailed(gate.ipAddress, gate.username), false);
            }
        }

        private void allow() {
            finish(null, false);
        }

        private void block(Result result, boolean recordBlock) {
            finish(result, recordBlock);
        }

        private void abortForShutdown() {
            block(Result.checkFailed(gate.ipAddress, gate.username), false);
        }

        private void finish(Result result, boolean recordBlock) {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            if (timeoutTask != null) {
                timeoutTask.cancel(false);
            }
            owner.remove(this);
            if (result != null && recordBlock) {
                AdaptiveLoginOrderService.getInstance().recordPreSonarBlock();
            }
            executeOnEventLoop(user, () -> {
                if (result == null) {
                    gate.resume();
                    return;
                }
                if (!disconnect(user, result, disconnectMessage)) {
                    closeChannel(user);
                }
            });
        }
    }

    private static final class ConnectionGate {
        private final Object config;
        private final boolean restoreAutoRead;
        private final String ipAddress;
        private final String username;

        private ConnectionGate(Object config,
                               boolean restoreAutoRead,
                               String ipAddress,
                               String username) {
            this.config = config;
            this.restoreAutoRead = restoreAutoRead;
            this.ipAddress = ipAddress;
            this.username = username;
        }

        private static ConnectionGate pause(Object user) {
            try {
                Object channel = user.getClass().getMethod("channel").invoke(user);
                Object config = channel.getClass().getMethod("config").invoke(channel);
                boolean autoRead = (Boolean) config.getClass().getMethod("isAutoRead").invoke(config);
                if (autoRead) {
                    config.getClass().getMethod("setAutoRead", boolean.class).invoke(config, false);
                }
                InetAddress address =
                        (InetAddress) user.getClass().getMethod("getInetAddress").invoke(user);
                String username =
                        String.valueOf(user.getClass().getMethod("getUsername").invoke(user));
                return new ConnectionGate(config, autoRead, address.getHostAddress(), username);
            } catch (Throwable throwable) {
                ConnectionGuard.reportError("Sonar pre-verification pause", throwable);
                return null;
            }
        }

        private void resume() {
            if (!restoreAutoRead) {
                return;
            }
            try {
                config.getClass().getMethod("setAutoRead", boolean.class).invoke(config, true);
            } catch (Throwable throwable) {
                ConnectionGuard.reportError("Sonar pre-verification resume", throwable);
            }
        }
    }

    private static final class Registration {
        private final Object eventManager;
        private final Object listener;
        private final Class<?> arrayType;
        private final ScheduledThreadPoolExecutor timeoutExecutor;
        private final Set<PendingCheck> pendingChecks;

        private Registration(Object eventManager,
                             Object listener,
                             Class<?> arrayType,
                             ScheduledThreadPoolExecutor timeoutExecutor,
                             Set<PendingCheck> pendingChecks) {
            this.eventManager = eventManager;
            this.listener = listener;
            this.arrayType = arrayType;
            this.timeoutExecutor = timeoutExecutor;
            this.pendingChecks = pendingChecks;
        }
    }
}
