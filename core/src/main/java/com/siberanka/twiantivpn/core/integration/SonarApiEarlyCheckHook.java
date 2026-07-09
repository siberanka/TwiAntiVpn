package com.siberanka.twiantivpn.core.integration;

import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.vpn.VpnResult;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.logging.Logger;

public final class SonarApiEarlyCheckHook {
    private static final AtomicReference<Registration> REGISTRATION = new AtomicReference<>();

    private SonarApiEarlyCheckHook() {
    }

    public static void install(Logger logger,
                               BiPredicate<String, String> vpnExemption,
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
                            handleEvent(args[0], vpnExemption, disconnectMessage, logger);
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
            if (!AdaptiveLoginOrderService.getInstance().shouldRunBeforeAntiBot()) {
                return;
            }
            Object user = event.getClass().getMethod("getUser").invoke(event);
            String username = String.valueOf(user.getClass().getMethod("getUsername").invoke(user));
            InetAddress inetAddress = (InetAddress) user.getClass().getMethod("getInetAddress").invoke(user);
            String ipAddress = inetAddress.getHostAddress();

            Optional<String> blockedUsernamePart = ConnectionGuard.getBlockedUsernamePart(username);
            if (blockedUsernamePart.isPresent()) {
                disconnect(user, Result.username(ipAddress, username, blockedUsernamePart.get()), disconnectMessage);
                return;
            }

            if (vpnExemption != null && vpnExemption.test(ipAddress, username)) {
                return;
            }

            CompletableFuture<VpnResult> vpnFuture = ConnectionGuard.getVpnResult(ipAddress);
            vpnFuture.whenComplete((vpnResult, throwable) -> {
                if (throwable != null) {
                    if (logger != null) {
                        logger.info("TwiAntiVpn | Sonar early VPN check failed: " + throwable.getMessage());
                    }
                    return;
                }
                if (vpnResult != null && vpnResult.isVpn()) {
                    disconnect(user, Result.vpn(ipAddress, username), disconnectMessage);
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

    public static final class Result {
        private final String type;
        private final String ipAddress;
        private final String username;
        private final String match;

        private Result(String type, String ipAddress, String username, String match) {
            this.type = type;
            this.ipAddress = ipAddress;
            this.username = username;
            this.match = match;
        }

        public static Result vpn(String ipAddress, String username) {
            return new Result("vpn", ipAddress, username, "");
        }

        public static Result username(String ipAddress, String username, String match) {
            return new Result("username", ipAddress, username, match);
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
