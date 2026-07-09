package com.siberanka.twiantivpn.bungee.listener;

import com.siberanka.twiantivpn.bungee.ConnectionGuardBungeePlugin;
import com.siberanka.twiantivpn.core.asteroid.AsteroidRegistryHook;
import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.geo.GeoResult;
import com.siberanka.twiantivpn.core.isp.IspBlockResult;
import com.siberanka.twiantivpn.core.luckperms.CGLuckPermsHelper;
import com.siberanka.twiantivpn.core.vpn.VpnResult;
import com.siberanka.twiantivpn.core.webhook.CGWebHookHelper;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ConnectionGuardBungeeListener implements Listener {
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLoginBeforeAntiBot(LoginEvent loginEvent) {
        if (shouldRunBeforeAntiBot()) {
            handleLogin(loginEvent);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLoginAfterAntiBot(LoginEvent loginEvent) {
        if (!shouldRunBeforeAntiBot() && !loginEvent.isCancelled()) {
            handleLogin(loginEvent);
        }
    }

    private void handleLogin(LoginEvent loginEvent) {
        loginEvent.registerIntent(ConnectionGuardBungeePlugin.getInstance());

        String ipAddress = loginEvent.getConnection().getAddress().getAddress().getHostAddress();
        Optional<String> blockedUsernamePart = ConnectionGuard.getBlockedUsernamePart(loginEvent.getConnection().getName());
        if (blockedUsernamePart.isPresent()) {
            handleUsernameBlock(loginEvent, ipAddress, blockedUsernamePart.get());
            loginEvent.completeIntent(ConnectionGuardBungeePlugin.getInstance());
            return;
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
        if (
                ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(ipAddress)
                        || ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(loginEvent.getConnection().getUniqueId().toString())
                        || ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(loginEvent.getConnection().getName())
        ) {
            vpnResultFuture = CompletableFuture.completedFuture(new VpnResult(ipAddress, false));
            hasVpnExemptionPermissionFuture = CompletableFuture.completedFuture(false);
        } else {
            vpnResultFuture = ConnectionGuard.getVpnResult(ipAddress);

            if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.use-permission-exemption")) {
                hasVpnExemptionPermissionFuture = CGLuckPermsHelper.hasPermission(loginEvent.getConnection().getUniqueId(), "connectionguard.exemption.vpn");
            } else {
                hasVpnExemptionPermissionFuture = CompletableFuture.completedFuture(false);
            }
        }

        if (
                ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(ipAddress)
                        || ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(loginEvent.getConnection().getUniqueId().toString())
                        || ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(loginEvent.getConnection().getName())
        ) {
            geoResultOptionalFuture = CompletableFuture.completedFuture(Optional.empty());
            hasGeoExemptionPermissionFuture = CompletableFuture.completedFuture(false);
        } else {
            geoResultOptionalFuture = ConnectionGuard.getGeoResult(ipAddress);

            if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.use-permission-exemption")) {
                hasGeoExemptionPermissionFuture = CGLuckPermsHelper.hasPermission(loginEvent.getConnection().getUniqueId(), "connectionguard.exemption.geo");
            } else {
                hasGeoExemptionPermissionFuture = CompletableFuture.completedFuture(false);
            }
        }

        CompletableFuture.allOf(vpnResultFuture, geoResultOptionalFuture, hasVpnExemptionPermissionFuture, hasGeoExemptionPermissionFuture).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                ConnectionGuard.getLogger().info("Login check failed: " + throwable.getMessage());
                loginEvent.completeIntent(ConnectionGuardBungeePlugin.getInstance());
                return;
            }

            VpnResult vpnResult = vpnResultFuture.join();
            Boolean hasVpnExemption = hasVpnExemptionPermissionFuture.join();
            Boolean hasGeoExemption = hasGeoExemptionPermissionFuture.join();

            if (vpnResult.isVpn() && !hasVpnExemption) {
                // Check if staff should be notified
                if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.notify-staff")) {
                    String notifyMessage = ChatColor.translateAlternateColorCodes(
                            '&',
                            ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.vpn-notify")
                                    .replace("%IP%", vpnResult.getIpAddress())
                                    .replace("%NAME%", loginEvent.getConnection().getName())
                    );
                    broadcastMessage(notifyMessage, "connectionguard.notify.vpn");
                }

                // Check if command should be executed on flag
                if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.execute-command.enabled")) {
                    ConnectionGuardBungeePlugin.getInstance().getProxy().getPluginManager().dispatchCommand(
                            ConnectionGuardBungeePlugin.getInstance().getProxy().getConsole(),
                            ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.vpn.execute-command.command")
                                    .replace("%NAME%", loginEvent.getConnection().getName())
                                    .replace("%IP%", ipAddress));
                }

                // Check if WebHook should be executed
                if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.send-webhook.enabled")) {
                    String webhookMessage = ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.vpn-webhook")
                            .replace("%NAME%", loginEvent.getConnection().getName())
                            .replace("%IP%", ipAddress);
                    String webhookUrl = ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.vpn.send-webhook.url");

                    CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
                }

                // Check if player should be kicked
                if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.vpn.kick-player")) {
                    String kickMessage = ChatColor.translateAlternateColorCodes(
                            '&',
                            ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.vpn-block")
                                    .replace("%IP%", vpnResult.getIpAddress())
                                    .replace("%NAME%", loginEvent.getConnection().getName())
                    );

                    loginEvent.setCancelReason(new TextComponent(kickMessage));
                    loginEvent.setCancelled(true);

                    loginEvent.completeIntent(ConnectionGuardBungeePlugin.getInstance());
                    return;
                }
            }

            Optional<GeoResult> geoResultOptional = geoResultOptionalFuture.join();
            if (geoResultOptional.isPresent() && !hasGeoExemption) {
                GeoResult geoResult = geoResultOptional.get();
                Optional<IspBlockResult> ispBlockResult = ConnectionGuard.getIspBlockResult(geoResult);
                if (ispBlockResult.isPresent()) {
                    handleIspBlock(loginEvent, ipAddress, ispBlockResult.get());
                    loginEvent.completeIntent(ConnectionGuardBungeePlugin.getInstance());
                    return;
                }

                boolean isGeoFlagged = false;

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

                if (isGeoFlagged) {
                    // Check if staff should be notified
                    if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.notify-staff")) {
                        String notifyMessage = ChatColor.translateAlternateColorCodes(
                                '&',
                                ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.geo-notify")
                                        .replace("%IP%", geoResult.getIpAddress())
                                        .replace("%COUNTRY%", geoResult.getCountryName())
                                        .replace("%CITY%", geoResult.getCityName())
                                        .replace("%ISP%", geoResult.getIspName())
                                        .replace("%NAME%", loginEvent.getConnection().getName())
                        );
                        broadcastMessage(notifyMessage, "connectionguard.notify.geo");
                    }

                    // Check if command should be executed on flag
                    if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.execute-command.enabled")) {
                        ConnectionGuardBungeePlugin.getInstance().getProxy().getPluginManager().dispatchCommand(
                                ConnectionGuardBungeePlugin.getInstance().getProxy().getConsole(),
                                ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.geo.execute-command.command")
                                        .replace("%NAME%", loginEvent.getConnection().getName())
                                        .replace("%IP%", ipAddress));
                    }

                    // Check if WebHook should be executed
                    if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.send-webhook.enabled")) {
                        String webhookMessage = ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.geo-webhook")
                                .replace("%NAME%", loginEvent.getConnection().getName())
                                .replace("%IP%", ipAddress)
                                .replace("%COUNTRY%", geoResult.getCountryName())
                                .replace("%CITY%", geoResult.getCityName())
                                .replace("%ISP%", geoResult.getIspName());
                        String webhookUrl = ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.geo.send-webhook.url");

                        CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
                    }

                    // Check if player should be kicked
                    if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.geo.kick-player")) {
                        String kickMessage = ChatColor.translateAlternateColorCodes(
                                '&',
                                ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.geo-block")
                                        .replace("%IP%", geoResult.getIpAddress())
                                        .replace("%COUNTRY%", geoResult.getCountryName())
                                        .replace("%CITY%", geoResult.getCityName())
                                        .replace("%ISP%", geoResult.getIspName())
                                        .replace("%NAME%", loginEvent.getConnection().getName())
                        );

                        loginEvent.setCancelReason(new TextComponent(kickMessage));
                        loginEvent.setCancelled(true);
                        loginEvent.completeIntent(ConnectionGuardBungeePlugin.getInstance());
                        return;
                    }
                }
            }

            loginEvent.completeIntent(ConnectionGuardBungeePlugin.getInstance());
        });

    }

    private boolean shouldRunBeforeAntiBot() {
        String order = ConnectionGuardBungeePlugin.getInstance().getConfig().getString("login-check.order");
        return order == null || !order.equalsIgnoreCase("AFTER_ANTIBOT");
    }

    private void broadcastMessage(String message, String permission) {
        for (ProxiedPlayer proxiedPlayer : ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayers()) {
            if (proxiedPlayer.hasPermission(permission)) {
                proxiedPlayer.sendMessage(TextComponent.fromLegacyText(message));
            }
        }
    }

    private void handleUsernameBlock(LoginEvent loginEvent, String ipAddress, String matchedPart) {
        if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.username.notify-staff")) {
            String notifyMessage = ChatColor.translateAlternateColorCodes(
                    '&',
                    ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.username-notify")
                            .replace("%IP%", ipAddress)
                            .replace("%NAME%", loginEvent.getConnection().getName())
                            .replace("%MATCH%", matchedPart)
            );
            broadcastMessage(notifyMessage, "connectionguard.notify.username");
        }
        if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.username.execute-command.enabled")) {
            ConnectionGuardBungeePlugin.getInstance().getProxy().getPluginManager().dispatchCommand(
                    ConnectionGuardBungeePlugin.getInstance().getProxy().getConsole(),
                    ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.username.execute-command.command")
                            .replace("%NAME%", loginEvent.getConnection().getName())
                            .replace("%IP%", ipAddress)
                            .replace("%MATCH%", matchedPart)
            );
        }
        if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.username.send-webhook.enabled")) {
            String webhookMessage = ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.username-webhook")
                    .replace("%NAME%", loginEvent.getConnection().getName())
                    .replace("%IP%", ipAddress)
                    .replace("%MATCH%", matchedPart);
            CGWebHookHelper.sendWebHook(ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.username.send-webhook.url"), webhookMessage);
        }
        if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.username.kick-player")) {
            String kickMessage = ChatColor.translateAlternateColorCodes(
                    '&',
                    ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.username-block")
                            .replace("%IP%", ipAddress)
                            .replace("%NAME%", loginEvent.getConnection().getName())
                            .replace("%MATCH%", matchedPart)
            );
            loginEvent.setCancelReason(new TextComponent(kickMessage));
            loginEvent.setCancelled(true);
        }
    }

    private void handleIspBlock(LoginEvent loginEvent, String ipAddress, IspBlockResult ispBlockResult) {
        GeoResult geoResult = ispBlockResult.getGeoResult();
        if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.isp.notify-staff")) {
            String notifyMessage = ChatColor.translateAlternateColorCodes(
                    '&',
                    ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.isp-notify")
                            .replace("%IP%", ipAddress)
                            .replace("%NAME%", loginEvent.getConnection().getName())
                            .replace("%ISP%", geoResult.getIspName())
                            .replace("%ASN%", geoResult.getAsn())
                            .replace("%MATCH%", ispBlockResult.getMatchedValue())
            );
            broadcastMessage(notifyMessage, "connectionguard.notify.isp");
        }
        if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.isp.execute-command.enabled")) {
            ConnectionGuardBungeePlugin.getInstance().getProxy().getPluginManager().dispatchCommand(
                    ConnectionGuardBungeePlugin.getInstance().getProxy().getConsole(),
                    ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.isp.execute-command.command")
                            .replace("%NAME%", loginEvent.getConnection().getName())
                            .replace("%IP%", ipAddress)
                            .replace("%ISP%", geoResult.getIspName())
                            .replace("%ASN%", geoResult.getAsn())
                            .replace("%MATCH%", ispBlockResult.getMatchedValue())
            );
        }
        if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.isp.send-webhook.enabled")) {
            String webhookMessage = ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.isp-webhook")
                    .replace("%NAME%", loginEvent.getConnection().getName())
                    .replace("%IP%", ipAddress)
                    .replace("%ISP%", geoResult.getIspName())
                    .replace("%ASN%", geoResult.getAsn())
                    .replace("%MATCH%", ispBlockResult.getMatchedValue());
            CGWebHookHelper.sendWebHook(ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.isp.send-webhook.url"), webhookMessage);
        }
        if (ConnectionGuardBungeePlugin.getInstance().getConfig().getBoolean("behavior.isp.kick-player")) {
            String kickMessage = ChatColor.translateAlternateColorCodes(
                    '&',
                    ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.isp-block")
                            .replace("%IP%", ipAddress)
                            .replace("%NAME%", loginEvent.getConnection().getName())
                            .replace("%ISP%", geoResult.getIspName())
                            .replace("%ASN%", geoResult.getAsn())
                            .replace("%MATCH%", ispBlockResult.getMatchedValue())
            );
            loginEvent.setCancelReason(new TextComponent(kickMessage));
            loginEvent.setCancelled(true);
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
