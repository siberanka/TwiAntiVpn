package com.siberanka.twiantivpn.bungee.listener;

import com.siberanka.twiantivpn.bungee.ConnectionGuardBungeePlugin;
import com.siberanka.twiantivpn.core.asteroid.AsteroidRegistryHook;
import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.geo.GeoResult;
import com.siberanka.twiantivpn.core.integration.AdaptiveLoginOrderService;
import com.siberanka.twiantivpn.core.integration.CheckModule;
import com.siberanka.twiantivpn.core.isp.IspBlockResult;
import com.siberanka.twiantivpn.core.luckperms.CGLuckPermsHelper;
import com.siberanka.twiantivpn.core.message.MessageFormatter;
import com.siberanka.twiantivpn.core.security.CommandValueSanitizer;
import com.siberanka.twiantivpn.core.vpn.VpnResult;
import com.siberanka.twiantivpn.core.webhook.CGWebHookHelper;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

public class ConnectionGuardBungeeListener implements Listener {
    private final Map<LoginEvent, Set<CheckModule>> deferredEvents =
            Collections.synchronizedMap(new WeakHashMap<>());

    @EventHandler(priority = Byte.MIN_VALUE)
    public void onLoginBeforeAntiBot(LoginEvent loginEvent) {
        AdaptiveLoginOrderService.ModulePlan plan =
                AdaptiveLoginOrderService.getInstance().snapshotModulePlan();
        if (plan.isRunBeforePlatform()) {
            if (!plan.getAfterPlatformModules().isEmpty()) {
                deferredEvents.put(loginEvent, plan.getAfterPlatformModules());
            }
            handleLogin(loginEvent, plan.getBeforePlatformModules(), true);
        } else {
            deferredEvents.put(loginEvent, plan.getAfterPlatformModules());
        }
    }

    @EventHandler(priority = Byte.MAX_VALUE)
    public void onLoginAfterAntiBot(LoginEvent loginEvent) {
        Set<CheckModule> modules = deferredEvents.remove(loginEvent);
        if (modules != null && !modules.isEmpty() && !loginEvent.isCancelled()) {
            handleLogin(loginEvent, modules, false);
        }
    }

    private void handleLogin(LoginEvent loginEvent, Set<CheckModule> modules, boolean preSonarPhase) {
        loginEvent.registerIntent(ConnectionGuardBungeePlugin.getInstance());

        String ipAddress = loginEvent.getConnection().getAddress().getAddress().getHostAddress();
        if (ConnectionGuard.isRuntimeWhitelistedIp(ipAddress)) {
            loginEvent.completeIntent(ConnectionGuardBungeePlugin.getInstance());
            return;
        }
        if (modules.contains(CheckModule.USERNAME_FILTER)) {
            Optional<String> blockedUsernamePart = ConnectionGuard.getBlockedUsernamePart(loginEvent.getConnection().getName());
            if (blockedUsernamePart.isPresent()) {
                handleUsernameBlock(loginEvent, ipAddress, blockedUsernamePart.get(), preSonarPhase);
                loginEvent.completeIntent(ConnectionGuardBungeePlugin.getInstance());
                return;
            }
        }

        if (shouldBypassAsteroid(loginEvent)) {
            loginEvent.completeIntent(ConnectionGuardBungeePlugin.getInstance());
            return;
        }

        CompletableFuture<VpnResult> vpnResultFuture;
        CompletableFuture<Optional<GeoResult>> geoResultOptionalFuture;
        CompletableFuture<Boolean> hasVpnExemptionPermissionFuture;
        CompletableFuture<Boolean> hasGeoExemptionPermissionFuture;

        // Check if ip address is in exemption lists
        Set<String> selectedVpnProviders = CheckModule.providerNames(modules);
        boolean checkVpn = modules.contains(CheckModule.PROXY_BLOCKLIST)
                || !selectedVpnProviders.isEmpty();
        boolean checkGeo = modules.contains(CheckModule.GEO_BLOCK)
                || modules.contains(CheckModule.ISP_BLOCK);

        if (!checkVpn || (
                ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(ipAddress)
                        || ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(loginEvent.getConnection().getUniqueId().toString())
                        || ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(loginEvent.getConnection().getName())
        )) {
            vpnResultFuture = CompletableFuture.completedFuture(new VpnResult(ipAddress, false));
            hasVpnExemptionPermissionFuture = CompletableFuture.completedFuture(false);
        } else {
            vpnResultFuture = ConnectionGuard.getVpnResult(
                    ipAddress,
                    modules.contains(CheckModule.PROXY_BLOCKLIST),
                    selectedVpnProviders
            );

            if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.use-permission-exemption")) {
                hasVpnExemptionPermissionFuture = CGLuckPermsHelper.hasPermission(loginEvent.getConnection().getUniqueId(), "twiantivpn.exemption.vpn");
            } else {
                hasVpnExemptionPermissionFuture = CompletableFuture.completedFuture(false);
            }
        }

        if (!checkGeo || (
                ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(ipAddress)
                        || ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(loginEvent.getConnection().getUniqueId().toString())
                        || ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(loginEvent.getConnection().getName())
        )) {
            geoResultOptionalFuture = CompletableFuture.completedFuture(Optional.empty());
            hasGeoExemptionPermissionFuture = CompletableFuture.completedFuture(false);
        } else {
            geoResultOptionalFuture = ConnectionGuard.getGeoResult(ipAddress);

            if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.use-permission-exemption")) {
                hasGeoExemptionPermissionFuture = CGLuckPermsHelper.hasPermission(loginEvent.getConnection().getUniqueId(), "twiantivpn.exemption.geo");
            } else {
                hasGeoExemptionPermissionFuture = CompletableFuture.completedFuture(false);
            }
        }

        CompletableFuture.allOf(vpnResultFuture, geoResultOptionalFuture, hasVpnExemptionPermissionFuture, hasGeoExemptionPermissionFuture).whenComplete((ignored, throwable) -> {
            try {
            if (throwable != null) {
                ConnectionGuard.reportError("Bungee login check", throwable);
                return;
            }

            VpnResult vpnResult = vpnResultFuture.join();
            Boolean hasVpnExemption = hasVpnExemptionPermissionFuture.join();
            Boolean hasGeoExemption = hasGeoExemptionPermissionFuture.join();

            if (vpnResult.isVpn() && !hasVpnExemption) {
                boolean emitActions = ConnectionGuard.shouldEmitActions("vpn", ipAddress);
                // Check if staff should be notified
                if (emitActions && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.notify-staff")) {
                    String notifyMessage = message("messages.vpn-notify",
                            "%IP%", vpnResult.getIpAddress(),
                            "%NAME%", loginEvent.getConnection().getName());
                    broadcastMessage(notifyMessage, "twiantivpn.notify.vpn");
                }

                // Check if command should be executed on flag
                if (emitActions && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.execute-command.enabled")) {
                    ConnectionGuardBungeePlugin.getInstance().getProxy().getPluginManager().dispatchCommand(
                            ConnectionGuardBungeePlugin.getInstance().getProxy().getConsole(),
                            ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.vpn.execute-command.command")
                                    .replace("%NAME%", CommandValueSanitizer.sanitize(loginEvent.getConnection().getName()))
                                    .replace("%IP%", CommandValueSanitizer.sanitize(ipAddress)));
                }

                // Check if WebHook should be executed
                if (emitActions && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.send-webhook.enabled")) {
                    String webhookMessage = plainMessage("messages.vpn-webhook",
                            "%NAME%", loginEvent.getConnection().getName(),
                            "%IP%", ipAddress);
                    String webhookUrl = ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.vpn.send-webhook.url");

                    CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
                }

                // Check if player should be kicked
                if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.kick-player")) {
                    String kickMessage = message("messages.vpn-block",
                            "%IP%", vpnResult.getIpAddress(),
                            "%NAME%", loginEvent.getConnection().getName());

                    loginEvent.setCancelReason(TextComponent.fromLegacyText(kickMessage));
                    loginEvent.setCancelled(true);
                    recordPreSonarBlock(preSonarPhase);

                    return;
                }
            }

            Optional<GeoResult> geoResultOptional = geoResultOptionalFuture.join();
            if (geoResultOptional.isPresent() && !hasGeoExemption) {
                GeoResult geoResult = geoResultOptional.get();
                if (modules.contains(CheckModule.ISP_BLOCK)) {
                    Optional<IspBlockResult> ispBlockResult = ConnectionGuard.getIspBlockResult(geoResult);
                    if (ispBlockResult.isPresent()) {
                        handleIspBlock(loginEvent, ipAddress, ispBlockResult.get(), preSonarPhase);
                        return;
                    }
                }

                boolean isGeoFlagged = false;

                if (modules.contains(CheckModule.GEO_BLOCK)) {
                    switch (ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.geo.type").toLowerCase()) {
                    case "blacklist":
                        if (ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.geo.list").contains(geoResult.getCountryName()))
                            isGeoFlagged = true;
                        break;
                    case "whitelist":
                        if (!ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.geo.list").contains(geoResult.getCountryName()))
                            isGeoFlagged = true;
                        break;
                    default:
                        ConnectionGuard.getLogger().info("Invalid geo behavior type. Please use BLACKLIST or WHITELIST.");
                        break;
                    }
                }

                if (isGeoFlagged) {
                    boolean emitActions = ConnectionGuard.shouldEmitActions("geo", ipAddress);
                    // Check if staff should be notified
                    if (emitActions && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.notify-staff")) {
                        String notifyMessage = message("messages.geo-notify",
                                "%IP%", geoResult.getIpAddress(),
                                "%COUNTRY%", geoResult.getCountryName(),
                                "%CITY%", geoResult.getCityName(),
                                "%ISP%", geoResult.getIspName(),
                                "%NAME%", loginEvent.getConnection().getName());
                        broadcastMessage(notifyMessage, "twiantivpn.notify.geo");
                    }

                    // Check if command should be executed on flag
                    if (emitActions && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.execute-command.enabled")) {
                        ConnectionGuardBungeePlugin.getInstance().getProxy().getPluginManager().dispatchCommand(
                                ConnectionGuardBungeePlugin.getInstance().getProxy().getConsole(),
                                ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.geo.execute-command.command")
                                        .replace("%NAME%", CommandValueSanitizer.sanitize(loginEvent.getConnection().getName()))
                                        .replace("%IP%", CommandValueSanitizer.sanitize(ipAddress)));
                    }

                    // Check if WebHook should be executed
                    if (emitActions && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.send-webhook.enabled")) {
                        String webhookMessage = plainMessage("messages.geo-webhook",
                                "%NAME%", loginEvent.getConnection().getName(),
                                "%IP%", ipAddress,
                                "%COUNTRY%", geoResult.getCountryName(),
                                "%CITY%", geoResult.getCityName(),
                                "%ISP%", geoResult.getIspName());
                        String webhookUrl = ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.geo.send-webhook.url");

                        CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
                    }

                    // Check if player should be kicked
                    if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.kick-player")) {
                        String kickMessage = message("messages.geo-block",
                                "%IP%", geoResult.getIpAddress(),
                                "%COUNTRY%", geoResult.getCountryName(),
                                "%CITY%", geoResult.getCityName(),
                                "%ISP%", geoResult.getIspName(),
                                "%NAME%", loginEvent.getConnection().getName());

                        loginEvent.setCancelReason(TextComponent.fromLegacyText(kickMessage));
                        loginEvent.setCancelled(true);
                        recordPreSonarBlock(preSonarPhase);
                        return;
                    }
                }
            }

            } catch (Throwable callbackFailure) {
                ConnectionGuard.reportError("Bungee login callback", callbackFailure);
            } finally {
                loginEvent.completeIntent(ConnectionGuardBungeePlugin.getInstance());
            }
        });

    }

    private void broadcastMessage(String message, String permission) {
        for (ProxiedPlayer proxiedPlayer : ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayers()) {
            if (proxiedPlayer.hasPermission(permission)) {
                proxiedPlayer.sendMessage(TextComponent.fromLegacyText(message));
            }
        }
    }

    private String message(String path, String... placeholders) {
        return MessageFormatter.toLegacyText(
                ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString(path),
                MessageFormatter.placeholdersWithKickLayout(prefix(), kickPrefix(), kickContact(), placeholders)
        );
    }

    private String plainMessage(String path, String... placeholders) {
        return MessageFormatter.toPlainText(
                ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString(path),
                MessageFormatter.placeholdersWithKickLayout(prefix(), kickPrefix(), kickContact(), placeholders)
        );
    }

    private String prefix() {
        return ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.prefix", "&bTwiAntiVpn &7|");
    }

    private String kickPrefix() {
        return ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.kick-prefix", "&b&lTwiAntiVpn");
    }

    private String kickContact() {
        return ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString(
                "messages.kick-contact",
                "&#35D3FFstore.example.net &8| &#6F7DFFdiscord.gg/invite"
        );
    }

    private void handleUsernameBlock(LoginEvent loginEvent,
                                     String ipAddress,
                                     String matchedPart,
                                     boolean preSonarPhase) {
        boolean emitActions = ConnectionGuard.shouldEmitActions("username", ipAddress);
        if (emitActions && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.username.notify-staff")) {
            String notifyMessage = message("messages.username-notify",
                    "%IP%", ipAddress,
                    "%NAME%", loginEvent.getConnection().getName(),
                    "%MATCH%", matchedPart);
            broadcastMessage(notifyMessage, "twiantivpn.notify.username");
        }
        if (emitActions && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.username.execute-command.enabled")) {
            ConnectionGuardBungeePlugin.getInstance().getProxy().getPluginManager().dispatchCommand(
                    ConnectionGuardBungeePlugin.getInstance().getProxy().getConsole(),
                    ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.username.execute-command.command")
                            .replace("%NAME%", CommandValueSanitizer.sanitize(loginEvent.getConnection().getName()))
                            .replace("%IP%", CommandValueSanitizer.sanitize(ipAddress))
                            .replace("%MATCH%", CommandValueSanitizer.sanitize(matchedPart))
            );
        }
        if (emitActions && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.username.send-webhook.enabled")) {
            String webhookMessage = plainMessage("messages.username-webhook",
                    "%NAME%", loginEvent.getConnection().getName(),
                    "%IP%", ipAddress,
                    "%MATCH%", matchedPart);
            CGWebHookHelper.sendWebHook(ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.username.send-webhook.url"), webhookMessage);
        }
        if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.username.kick-player")) {
            String kickMessage = message("messages.username-block",
                    "%IP%", ipAddress,
                    "%NAME%", loginEvent.getConnection().getName(),
                    "%MATCH%", matchedPart);
            loginEvent.setCancelReason(TextComponent.fromLegacyText(kickMessage));
            loginEvent.setCancelled(true);
            recordPreSonarBlock(preSonarPhase);
        }
    }

    private void handleIspBlock(LoginEvent loginEvent,
                                String ipAddress,
                                IspBlockResult ispBlockResult,
                                boolean preSonarPhase) {
        GeoResult geoResult = ispBlockResult.getGeoResult();
        boolean emitActions = ConnectionGuard.shouldEmitActions("isp", ipAddress);
        if (emitActions && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.isp.notify-staff")) {
            String notifyMessage = message("messages.isp-notify",
                    "%IP%", ipAddress,
                    "%NAME%", loginEvent.getConnection().getName(),
                    "%ISP%", geoResult.getIspName(),
                    "%ASN%", geoResult.getAsn(),
                    "%MATCH%", ispBlockResult.getMatchedValue());
            broadcastMessage(notifyMessage, "twiantivpn.notify.isp");
        }
        if (emitActions && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.isp.execute-command.enabled")) {
            ConnectionGuardBungeePlugin.getInstance().getProxy().getPluginManager().dispatchCommand(
                    ConnectionGuardBungeePlugin.getInstance().getProxy().getConsole(),
                    ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.isp.execute-command.command")
                            .replace("%NAME%", CommandValueSanitizer.sanitize(loginEvent.getConnection().getName()))
                            .replace("%IP%", CommandValueSanitizer.sanitize(ipAddress))
                            .replace("%ISP%", CommandValueSanitizer.sanitize(geoResult.getIspName()))
                            .replace("%ASN%", CommandValueSanitizer.sanitize(geoResult.getAsn()))
                            .replace("%MATCH%", CommandValueSanitizer.sanitize(ispBlockResult.getMatchedValue()))
            );
        }
        if (emitActions && ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.isp.send-webhook.enabled")) {
            String webhookMessage = plainMessage("messages.isp-webhook",
                    "%NAME%", loginEvent.getConnection().getName(),
                    "%IP%", ipAddress,
                    "%ISP%", geoResult.getIspName(),
                    "%ASN%", geoResult.getAsn(),
                    "%MATCH%", ispBlockResult.getMatchedValue());
            CGWebHookHelper.sendWebHook(ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.isp.send-webhook.url"), webhookMessage);
        }
        if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.isp.kick-player")) {
            String kickMessage = message("messages.isp-block",
                    "%IP%", ipAddress,
                    "%NAME%", loginEvent.getConnection().getName(),
                    "%ISP%", geoResult.getIspName(),
                    "%ASN%", geoResult.getAsn(),
                    "%MATCH%", ispBlockResult.getMatchedValue());
            loginEvent.setCancelReason(TextComponent.fromLegacyText(kickMessage));
            loginEvent.setCancelled(true);
            recordPreSonarBlock(preSonarPhase);
        }
    }

    private void recordPreSonarBlock(boolean preSonarPhase) {
        if (preSonarPhase) {
            AdaptiveLoginOrderService.getInstance().recordPreSonarBlock();
        }
    }

    private boolean shouldBypassAsteroid(LoginEvent loginEvent) {
        if (!ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("asteroid-proxy.enabled")) {
            return false;
        }
        boolean asteroidInstalled = false;
        for (String pluginName : ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("asteroid-proxy.plugin-names")) {
            if (ConnectionGuardBungeePlugin.getInstance().getProxy().getPluginManager().getPlugin(pluginName) != null) {
                asteroidInstalled = true;
                break;
            }
        }
        return asteroidInstalled && AsteroidRegistryHook.isFakePlayer(loginEvent.getConnection().getUniqueId());
    }
}
