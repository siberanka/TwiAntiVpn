package com.siberanka.twiantivpn.core.integration;

import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.geo.GeoResult;
import com.siberanka.twiantivpn.core.isp.IspBlockResult;
import com.siberanka.twiantivpn.core.vpn.VpnResult;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Logger;

public final class SonarApiEarlyCheckHook {
    private static final AtomicReference<Registration> REGISTRATION = new AtomicReference<>();

    private SonarApiEarlyCheckHook() {
    }

    public static void install(Logger logger,
                               BiPredicate<String, String> vpnExemption,
                               BiPredicate<String, String> geoExemption,
                               Predicate<GeoResult> geoBlocked,
                               Function<Result, String> disconnectMessage) {
        uninstall(logger);
        try {
            Class<?> sonarClass = Class.forName("xyz.jonesdev.sonar.api.Sonar");
            Class<?> listenerClass = Class.forName("xyz.jonesdev.sonar.api.event.SonarEventListener");
            Object sonar = sonarClass.getMethod("get").invoke(null);
            Object eventManager = sonar.getClass().getMethod("getEventManager").invoke(sonar);
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
            REGISTRATION.set(new Registration(eventManager, listener, listenerArray.getClass()));
            if (logger != null) {
                logger.info("TwiAntiVpn | Sonar early VPN hook enabled.");
            }
        } catch (ClassNotFoundException ignored) {
            // Sonar is not installed on this platform.
        } catch (Throwable throwable) {
            if (logger != null) {
                logger.info("TwiAntiVpn | Could not enable Sonar early VPN hook: " + throwable.getMessage());
            }
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
            if (logger != null) {
                logger.info("TwiAntiVpn | Could not unregister Sonar early VPN hook: " + throwable.getMessage());
            }
        }
    }

    private static void handleEvent(Object event,
                                    BiPredicate<String, String> vpnExemption,
                                    BiPredicate<String, String> geoExemption,
                                    Predicate<GeoResult> geoBlocked,
                                    Function<Result, String> disconnectMessage,
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

            if (modules.contains(CheckModule.USERNAME_FILTER)) {
                Optional<String> blockedUsernamePart = ConnectionGuard.getBlockedUsernamePart(username);
                if (blockedUsernamePart.isPresent()) {
                    disconnectAndRecord(user, Result.username(ipAddress, username, blockedUsernamePart.get()), disconnectMessage);
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
                return;
            }

            CompletableFuture.allOf(vpnFuture, geoFuture).whenComplete((ignored, throwable) -> {
                if (throwable != null) {
                    if (logger != null) {
                        logger.info("TwiAntiVpn | Sonar early check failed: " + throwable.getMessage());
                    }
                    return;
                }
                VpnResult vpnResult = vpnFuture.join();
                if (vpnResult != null && vpnResult.isVpn()) {
                    disconnectAndRecord(user, Result.vpn(ipAddress, username), disconnectMessage);
                    return;
                }

                Optional<GeoResult> geoResultOptional = geoFuture.join();
                if (!geoResultOptional.isPresent()) {
                    return;
                }
                GeoResult geoResult = geoResultOptional.get();
                if (modules.contains(CheckModule.ISP_BLOCK)) {
                    Optional<IspBlockResult> ispBlockResult =
                            ConnectionGuard.getIspBlockResult(geoResult);
                    if (ispBlockResult.isPresent()) {
                        disconnectAndRecord(
                                user,
                                Result.isp(ipAddress, username, ispBlockResult.get()),
                                disconnectMessage
                        );
                        return;
                    }
                }
                if (modules.contains(CheckModule.GEO_BLOCK)
                        && geoBlocked != null
                        && geoBlocked.test(geoResult)) {
                    disconnectAndRecord(user, Result.geo(ipAddress, username, geoResult), disconnectMessage);
                }
            });
        } catch (Throwable throwable) {
            if (logger != null) {
                logger.info("TwiAntiVpn | Sonar early hook event failed: " + throwable.getMessage());
            }
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

    private static void disconnect(Object user, Result result, Function<Result, String> disconnectMessage) {
        try {
            ClassLoader classLoader = user.getClass().getClassLoader();
            Class<?> componentClass = Class.forName("net.kyori.adventure.text.Component", true, classLoader);
            Method text = componentClass.getMethod("text", String.class);
            String message = disconnectMessage == null ? "Connection blocked by TwiAntiVpn." : disconnectMessage.apply(result);
            Object component = text.invoke(null, message == null ? "Connection blocked by TwiAntiVpn." : message);
            Method disconnect = user.getClass().getMethod("disconnect", componentClass);
            disconnect.invoke(user, component);
        } catch (Throwable ignored) {
            // If Sonar changed its API, fail closed for this hook only and leave Sonar's own flow intact.
        }
    }

    private static void disconnectAndRecord(Object user, Result result, Function<Result, String> disconnectMessage) {
        AdaptiveLoginOrderService.getInstance().recordPreSonarBlock();
        disconnect(user, result, disconnectMessage);
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

    private static final class Registration {
        private final Object eventManager;
        private final Object listener;
        private final Class<?> arrayType;

        private Registration(Object eventManager, Object listener, Class<?> arrayType) {
            this.eventManager = eventManager;
            this.listener = listener;
            this.arrayType = arrayType;
        }
    }
}
