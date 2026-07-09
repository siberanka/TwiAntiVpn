package com.siberanka.twiantivpn.spigot.listener;

import com.siberanka.twiantivpn.core.asteroid.AsteroidRegistryHook;
import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.geo.GeoResult;
import com.siberanka.twiantivpn.core.isp.IspBlockResult;
import com.siberanka.twiantivpn.core.luckperms.CGLuckPermsHelper;
import com.siberanka.twiantivpn.core.vpn.VpnResult;
import com.siberanka.twiantivpn.core.webhook.CGWebHookHelper;
import com.siberanka.twiantivpn.spigot.ConnectionGuardSpigotPlugin;
import net.md_5.bungee.api.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class AsyncPlayerPreLoginListener implements Listener {
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onAsyncPreLoginBeforeAntiBot(AsyncPlayerPreLoginEvent preLoginEvent) {
        if (shouldRunBeforeAntiBot()) {
            handlePreLogin(preLoginEvent);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onAsyncPreLoginAfterAntiBot(AsyncPlayerPreLoginEvent preLoginEvent) {
        if (!shouldRunBeforeAntiBot() && preLoginEvent.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            handlePreLogin(preLoginEvent);
        }
    }

    private void handlePreLogin(AsyncPlayerPreLoginEvent preLoginEvent) {
        String ipAddress = preLoginEvent.getAddress().getHostAddress();
        Optional<String> blockedUsernamePart = ConnectionGuard.getBlockedUsernamePart(preLoginEvent.getName());
        if (blockedUsernamePart.isPresent()) {
            handleUsernameBlock(preLoginEvent, ipAddress, blockedUsernamePart.get());
            return;
        }

        if (shouldBypassAsteroid(preLoginEvent)) {
            return;
        }

        CompletableFuture<VpnResult> vpnResultFuture;
        CompletableFuture<Optional<GeoResult>> geoResultOptionalFuture;
        CompletableFuture<Boolean> hasVpnExemptionPermissionFuture;
        CompletableFuture<Boolean> hasGeoExemptionPermissionFuture;

        // Check if ip address is in exemption lists
        if (
                ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(ipAddress)
                || ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(preLoginEvent.getUniqueId().toString())
                || ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(preLoginEvent.getName())
        ) {
            vpnResultFuture = CompletableFuture.completedFuture(new VpnResult(ipAddress, false));
            hasVpnExemptionPermissionFuture = CompletableFuture.completedFuture(false);
        } else {
            vpnResultFuture = ConnectionGuard.getVpnResult(ipAddress);

            if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.use-permission-exemption")) {
                hasVpnExemptionPermissionFuture = CGLuckPermsHelper.hasPermission(preLoginEvent.getUniqueId(), "twiantivpn.exemption.vpn");
            } else {
                hasVpnExemptionPermissionFuture = CompletableFuture.completedFuture(false);
            }
        }

        if (
                ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(ipAddress)
                || ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(preLoginEvent.getUniqueId().toString())
                || ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(preLoginEvent.getName())
        ) {
            geoResultOptionalFuture = CompletableFuture.completedFuture(Optional.empty());
            hasGeoExemptionPermissionFuture = CompletableFuture.completedFuture(false);
        } else {
            geoResultOptionalFuture = ConnectionGuard.getGeoResult(ipAddress);

            if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.use-permission-exemption")) {
                hasGeoExemptionPermissionFuture = CGLuckPermsHelper.hasPermission(preLoginEvent.getUniqueId(), "twiantivpn.exemption.geo");
            } else {
                hasGeoExemptionPermissionFuture = CompletableFuture.completedFuture(false);
            }
        }

        try {
            CompletableFuture.allOf(vpnResultFuture, geoResultOptionalFuture, hasVpnExemptionPermissionFuture, hasGeoExemptionPermissionFuture).join();
        } catch (Exception exception) {
            ConnectionGuard.getLogger().info("Login check failed: " + exception.getMessage());
            return;
        }

        VpnResult vpnResult = vpnResultFuture.join();
        Boolean hasVpnExemptionPermission = hasVpnExemptionPermissionFuture.join();
        Boolean hasGeoExemptionPermission = hasGeoExemptionPermissionFuture.join();

        if (vpnResult.isVpn() && !hasVpnExemptionPermission) {
            // Check if staff should be notified
            if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.notify-staff")) {
                String notifyMessage = ChatColor.translateAlternateColorCodes(
                        '&',
                        ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.vpn-notify")
                                .replace("%IP%", vpnResult.getIpAddress())
                                .replace("%NAME%", preLoginEvent.getName())
                );
                ConnectionGuardSpigotPlugin.getInstance().getServer().broadcast(notifyMessage, "twiantivpn.notify.vpn");
            }

            // Check if command should be executed on flag
            if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.execute-command.enabled")) {
                Bukkit.getScheduler().runTask(ConnectionGuardSpigotPlugin.getInstance(), new Runnable() {
                    @Override
                    public void run() {
                        Bukkit.dispatchCommand(
                                Bukkit.getConsoleSender(),
                                ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.vpn.execute-command.command")
                                        .replace("%NAME%", preLoginEvent.getName())
                                        .replace("%IP%", ipAddress)
                        );
                    }
                });
            }

            // Check if WebHook should be executed
            if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.send-webhook.enabled")) {
                String webhookMessage = ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.vpn-webhook")
                        .replace("%NAME%", preLoginEvent.getName())
                        .replace("%IP%", ipAddress);
                String webhookUrl = ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.vpn.send-webhook.url");

                CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
            }

            // Check if player should be kicked
            if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.kick-player")) {
                String kickMessage = ChatColor.translateAlternateColorCodes(
                        '&',
                        ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.vpn-block")
                                .replace("%IP%", vpnResult.getIpAddress())
                                .replace("%NAME%", preLoginEvent.getName())
                );

                preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage);
                return;
            }
        }

        Optional<GeoResult> geoResultOptional = geoResultOptionalFuture.join();
        if (geoResultOptional.isPresent() && !hasGeoExemptionPermission) {
            GeoResult geoResult = geoResultOptional.get();
            Optional<IspBlockResult> ispBlockResult = ConnectionGuard.getIspBlockResult(geoResult);
            if (ispBlockResult.isPresent()) {
                handleIspBlock(preLoginEvent, ipAddress, ispBlockResult.get());
                return;
            }

            boolean isGeoFlagged = false;

            switch (ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.geo.type").toLowerCase()) {
                case "blacklist":
                    if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.geo.list").contains(geoResult.getCountryName()))
                        isGeoFlagged = true;
                    break;
                case "whitelist":
                    if (!ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.geo.list").contains(geoResult.getCountryName()))
                        isGeoFlagged = true;
                    break;
                default:
                    ConnectionGuard.getLogger().info("Invalid geo behavior type. Please use BLACKLIST or WHITELIST.");
                    break;
            }

            if (isGeoFlagged) {
                // Check if staff should be notified
                if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.notify-staff")) {
                    String notifyMessage = ChatColor.translateAlternateColorCodes(
                            '&',
                            ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.geo-notify")
                                    .replace("%IP%", geoResult.getIpAddress())
                                    .replace("%COUNTRY%", geoResult.getCountryName())
                                    .replace("%CITY%", geoResult.getCityName())
                                    .replace("%ISP%", geoResult.getIspName())
                                    .replace("%NAME%", preLoginEvent.getName())
                    );
                    Bukkit.broadcast(notifyMessage, "twiantivpn.notify.geo");
                }

                // Check if command should be executed on flag
                if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.execute-command.enabled")) {
                    Bukkit.getScheduler().runTask(ConnectionGuardSpigotPlugin.getInstance(), new Runnable() {
                        @Override
                        public void run() {
                            Bukkit.dispatchCommand(
                                    Bukkit.getConsoleSender(),
                                    ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.geo.execute-command.command")
                                            .replace("%NAME%", preLoginEvent.getName())
                                            .replace("%IP%", ipAddress)
                            );
                        }
                    });
                }

                // Check if WebHook should be executed
                if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.send-webhook.enabled")) {
                    String webhookMessage = ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.geo-webhook")
                            .replace("%NAME%", preLoginEvent.getName())
                            .replace("%IP%", ipAddress)
                            .replace("%COUNTRY%", geoResult.getCountryName())
                            .replace("%CITY%", geoResult.getCityName())
                            .replace("%ISP%", geoResult.getIspName());
                    String webhookUrl = ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.geo.send-webhook.url");

                    CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
                }

                // Check if player should be kicked
                if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.kick-player")) {
                    String kickMessage = ChatColor.translateAlternateColorCodes(
                            '&',
                            ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.geo-block")
                                    .replace("%IP%", geoResult.getIpAddress())
                                    .replace("%COUNTRY%", geoResult.getCountryName())
                                    .replace("%CITY%", geoResult.getCityName())
                                    .replace("%ISP%", geoResult.getIspName())
                                    .replace("%NAME%", preLoginEvent.getName())
                    );

                    preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage);
                }
            }
        }
    }

    private boolean shouldRunBeforeAntiBot() {
        String order = ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("login-check.order", "BEFORE_ANTIBOT");
        return !order.equalsIgnoreCase("AFTER_ANTIBOT");
    }

    private void handleUsernameBlock(AsyncPlayerPreLoginEvent preLoginEvent, String ipAddress, String matchedPart) {
        if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.username.notify-staff")) {
            String notifyMessage = ChatColor.translateAlternateColorCodes(
                    '&',
                    ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.username-notify")
                            .replace("%IP%", ipAddress)
                            .replace("%NAME%", preLoginEvent.getName())
                            .replace("%MATCH%", matchedPart)
            );
            Bukkit.broadcast(notifyMessage, "twiantivpn.notify.username");
        }
        if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.username.execute-command.enabled")) {
            Bukkit.getScheduler().runTask(ConnectionGuardSpigotPlugin.getInstance(), () -> Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(),
                    ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.username.execute-command.command")
                            .replace("%NAME%", preLoginEvent.getName())
                            .replace("%IP%", ipAddress)
                            .replace("%MATCH%", matchedPart)
            ));
        }
        if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.username.send-webhook.enabled")) {
            String webhookMessage = ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.username-webhook")
                    .replace("%NAME%", preLoginEvent.getName())
                    .replace("%IP%", ipAddress)
                    .replace("%MATCH%", matchedPart);
            CGWebHookHelper.sendWebHook(ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.username.send-webhook.url"), webhookMessage);
        }
        if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.username.kick-player")) {
            String kickMessage = ChatColor.translateAlternateColorCodes(
                    '&',
                    ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.username-block")
                            .replace("%IP%", ipAddress)
                            .replace("%NAME%", preLoginEvent.getName())
                            .replace("%MATCH%", matchedPart)
            );
            preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage);
        }
    }

    private void handleIspBlock(AsyncPlayerPreLoginEvent preLoginEvent, String ipAddress, IspBlockResult ispBlockResult) {
        GeoResult geoResult = ispBlockResult.getGeoResult();
        if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.isp.notify-staff")) {
            String notifyMessage = ChatColor.translateAlternateColorCodes(
                    '&',
                    ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.isp-notify")
                            .replace("%IP%", ipAddress)
                            .replace("%NAME%", preLoginEvent.getName())
                            .replace("%ISP%", geoResult.getIspName())
                            .replace("%ASN%", geoResult.getAsn())
                            .replace("%MATCH%", ispBlockResult.getMatchedValue())
            );
            Bukkit.broadcast(notifyMessage, "twiantivpn.notify.isp");
        }
        if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.isp.execute-command.enabled")) {
            Bukkit.getScheduler().runTask(ConnectionGuardSpigotPlugin.getInstance(), () -> Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(),
                    ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.isp.execute-command.command")
                            .replace("%NAME%", preLoginEvent.getName())
                            .replace("%IP%", ipAddress)
                            .replace("%ISP%", geoResult.getIspName())
                            .replace("%ASN%", geoResult.getAsn())
                            .replace("%MATCH%", ispBlockResult.getMatchedValue())
            ));
        }
        if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.isp.send-webhook.enabled")) {
            String webhookMessage = ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.isp-webhook")
                    .replace("%NAME%", preLoginEvent.getName())
                    .replace("%IP%", ipAddress)
                    .replace("%ISP%", geoResult.getIspName())
                    .replace("%ASN%", geoResult.getAsn())
                    .replace("%MATCH%", ispBlockResult.getMatchedValue());
            CGWebHookHelper.sendWebHook(ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.isp.send-webhook.url"), webhookMessage);
        }
        if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.isp.kick-player")) {
            String kickMessage = ChatColor.translateAlternateColorCodes(
                    '&',
                    ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.isp-block")
                            .replace("%IP%", ipAddress)
                            .replace("%NAME%", preLoginEvent.getName())
                            .replace("%ISP%", geoResult.getIspName())
                            .replace("%ASN%", geoResult.getAsn())
                            .replace("%MATCH%", ispBlockResult.getMatchedValue())
            );
            preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage);
        }
    }

    private boolean shouldBypassAsteroid(AsyncPlayerPreLoginEvent preLoginEvent) {
        if (!ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("asteroid-proxy.enabled")) {
            return false;
        }
        boolean asteroidInstalled = false;
        for (String pluginName : ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("asteroid-proxy.plugin-names")) {
            org.bukkit.plugin.Plugin plugin = Bukkit.getPluginManager().getPlugin(pluginName);
            if (plugin != null && plugin.isEnabled()) {
                asteroidInstalled = true;
                break;
            }
        }
        return asteroidInstalled && AsteroidRegistryHook.isFakePlayer(preLoginEvent.getUniqueId());
    }
}
