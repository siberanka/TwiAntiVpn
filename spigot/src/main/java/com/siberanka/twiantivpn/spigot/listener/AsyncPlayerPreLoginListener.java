package com.siberanka.twiantivpn.spigot.listener;

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
import com.siberanka.twiantivpn.spigot.ConnectionGuardSpigotPlugin;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

public class AsyncPlayerPreLoginListener implements Listener {
    private final Map<AsyncPlayerPreLoginEvent, Set<CheckModule>> deferredEvents =
            Collections.synchronizedMap(new WeakHashMap<>());

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onAsyncPreLoginBeforeAntiBot(AsyncPlayerPreLoginEvent preLoginEvent) {
        AdaptiveLoginOrderService.ModulePlan plan =
                AdaptiveLoginOrderService.getInstance().snapshotModulePlan();
        if (plan.isRunBeforePlatform()) {
            handlePreLogin(preLoginEvent, plan.getBeforePlatformModules(), true);
        } else {
            deferredEvents.put(preLoginEvent, plan.getAfterPlatformModules());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onAsyncPreLoginAfterAntiBot(AsyncPlayerPreLoginEvent preLoginEvent) {
        Set<CheckModule> modules = deferredEvents.remove(preLoginEvent);
        if (modules != null
                && !modules.isEmpty()
                && preLoginEvent.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            handlePreLogin(preLoginEvent, modules, false);
        }
    }

    private void handlePreLogin(AsyncPlayerPreLoginEvent preLoginEvent, Set<CheckModule> modules, boolean preSonarPhase) {
        String ipAddress = preLoginEvent.getAddress().getHostAddress();
        if (modules.contains(CheckModule.USERNAME_FILTER)) {
            Optional<String> blockedUsernamePart = ConnectionGuard.getBlockedUsernamePart(preLoginEvent.getName());
            if (blockedUsernamePart.isPresent()) {
                handleUsernameBlock(preLoginEvent, ipAddress, blockedUsernamePart.get(), preSonarPhase);
                return;
            }
        }

        if (shouldBypassAsteroid(preLoginEvent)) {
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
                ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(ipAddress)
                || ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(preLoginEvent.getUniqueId().toString())
                || ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.vpn.exemptions").contains(preLoginEvent.getName())
        )) {
            vpnResultFuture = CompletableFuture.completedFuture(new VpnResult(ipAddress, false));
            hasVpnExemptionPermissionFuture = CompletableFuture.completedFuture(false);
        } else {
            vpnResultFuture = ConnectionGuard.getVpnResult(
                    ipAddress,
                    modules.contains(CheckModule.PROXY_BLOCKLIST),
                    selectedVpnProviders
            );

            if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.use-permission-exemption")) {
                hasVpnExemptionPermissionFuture = CGLuckPermsHelper.hasPermission(preLoginEvent.getUniqueId(), "twiantivpn.exemption.vpn");
            } else {
                hasVpnExemptionPermissionFuture = CompletableFuture.completedFuture(false);
            }
        }

        if (!checkGeo || (
                ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(ipAddress)
                || ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(preLoginEvent.getUniqueId().toString())
                || ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.geo.exemptions").contains(preLoginEvent.getName())
        )) {
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
            boolean emitActions = ConnectionGuard.shouldEmitActions("vpn", ipAddress);
            // Check if staff should be notified
            if (emitActions && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.notify-staff")) {
                String notifyMessage = message("messages.vpn-notify",
                        "%IP%", vpnResult.getIpAddress(),
                        "%NAME%", preLoginEvent.getName());
                ConnectionGuardSpigotPlugin.getInstance().getServer().broadcast(notifyMessage, "twiantivpn.notify.vpn");
            }

            // Check if command should be executed on flag
            if (emitActions && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.execute-command.enabled")) {
                Bukkit.getScheduler().runTask(ConnectionGuardSpigotPlugin.getInstance(), new Runnable() {
                    @Override
                    public void run() {
                        Bukkit.dispatchCommand(
                                Bukkit.getConsoleSender(),
                                ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.vpn.execute-command.command")
                                        .replace("%NAME%", CommandValueSanitizer.sanitize(preLoginEvent.getName()))
                                        .replace("%IP%", CommandValueSanitizer.sanitize(ipAddress))
                        );
                    }
                });
            }

            // Check if WebHook should be executed
            if (emitActions && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.send-webhook.enabled")) {
                String webhookMessage = plainMessage("messages.vpn-webhook",
                        "%NAME%", preLoginEvent.getName(),
                        "%IP%", ipAddress);
                String webhookUrl = ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.vpn.send-webhook.url");

                CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
            }

            // Check if player should be kicked
            if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.vpn.kick-player")) {
                String kickMessage = message("messages.vpn-block",
                        "%IP%", vpnResult.getIpAddress(),
                        "%NAME%", preLoginEvent.getName());

                preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage);
                recordPreSonarBlock(preSonarPhase);
                return;
            }
        }

        Optional<GeoResult> geoResultOptional = geoResultOptionalFuture.join();
        if (geoResultOptional.isPresent() && !hasGeoExemptionPermission) {
            GeoResult geoResult = geoResultOptional.get();
            if (modules.contains(CheckModule.ISP_BLOCK)) {
                Optional<IspBlockResult> ispBlockResult = ConnectionGuard.getIspBlockResult(geoResult);
                if (ispBlockResult.isPresent()) {
                    handleIspBlock(preLoginEvent, ipAddress, ispBlockResult.get(), preSonarPhase);
                    return;
                }
            }

            boolean isGeoFlagged = false;

            if (modules.contains(CheckModule.GEO_BLOCK)) {
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
            }

            if (isGeoFlagged) {
                boolean emitActions = ConnectionGuard.shouldEmitActions("geo", ipAddress);
                // Check if staff should be notified
                if (emitActions && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.notify-staff")) {
                    String notifyMessage = message("messages.geo-notify",
                            "%IP%", geoResult.getIpAddress(),
                            "%COUNTRY%", geoResult.getCountryName(),
                            "%CITY%", geoResult.getCityName(),
                            "%ISP%", geoResult.getIspName(),
                            "%NAME%", preLoginEvent.getName());
                    Bukkit.broadcast(notifyMessage, "twiantivpn.notify.geo");
                }

                // Check if command should be executed on flag
                if (emitActions && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.execute-command.enabled")) {
                    Bukkit.getScheduler().runTask(ConnectionGuardSpigotPlugin.getInstance(), new Runnable() {
                        @Override
                        public void run() {
                            Bukkit.dispatchCommand(
                                    Bukkit.getConsoleSender(),
                                    ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.geo.execute-command.command")
                                            .replace("%NAME%", CommandValueSanitizer.sanitize(preLoginEvent.getName()))
                                            .replace("%IP%", CommandValueSanitizer.sanitize(ipAddress))
                            );
                        }
                    });
                }

                // Check if WebHook should be executed
                if (emitActions && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.send-webhook.enabled")) {
                    String webhookMessage = plainMessage("messages.geo-webhook",
                            "%NAME%", preLoginEvent.getName(),
                            "%IP%", ipAddress,
                            "%COUNTRY%", geoResult.getCountryName(),
                            "%CITY%", geoResult.getCityName(),
                            "%ISP%", geoResult.getIspName());
                    String webhookUrl = ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.geo.send-webhook.url");

                    CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
                }

                // Check if player should be kicked
                if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.geo.kick-player")) {
                    String kickMessage = message("messages.geo-block",
                            "%IP%", geoResult.getIpAddress(),
                            "%COUNTRY%", geoResult.getCountryName(),
                            "%CITY%", geoResult.getCityName(),
                            "%ISP%", geoResult.getIspName(),
                            "%NAME%", preLoginEvent.getName());

                    preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage);
                    recordPreSonarBlock(preSonarPhase);
                }
            }
        }
    }

    private String message(String path, String... placeholders) {
        return MessageFormatter.toLegacyText(
                ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString(path),
                MessageFormatter.placeholdersWithKickLayout(prefix(), kickPrefix(), kickContact(), placeholders)
        );
    }

    private String plainMessage(String path, String... placeholders) {
        return MessageFormatter.toPlainText(
                ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString(path),
                MessageFormatter.placeholdersWithKickLayout(prefix(), kickPrefix(), kickContact(), placeholders)
        );
    }

    private String prefix() {
        return ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.prefix", "&bTwiAntiVpn &7|");
    }

    private String kickPrefix() {
        return ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.kick-prefix", "&b&lTwiAntiVpn");
    }

    private String kickContact() {
        return ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString(
                "messages.kick-contact",
                "&#35D3FFstore.example.net &8| &#6F7DFFdiscord.gg/invite"
        );
    }

    private void handleUsernameBlock(AsyncPlayerPreLoginEvent preLoginEvent,
                                     String ipAddress,
                                     String matchedPart,
                                     boolean preSonarPhase) {
        boolean emitActions = ConnectionGuard.shouldEmitActions("username", ipAddress);
        if (emitActions && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.username.notify-staff")) {
            String notifyMessage = message("messages.username-notify",
                    "%IP%", ipAddress,
                    "%NAME%", preLoginEvent.getName(),
                    "%MATCH%", matchedPart);
            Bukkit.broadcast(notifyMessage, "twiantivpn.notify.username");
        }
        if (emitActions && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.username.execute-command.enabled")) {
            Bukkit.getScheduler().runTask(ConnectionGuardSpigotPlugin.getInstance(), () -> Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(),
                    ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.username.execute-command.command")
                            .replace("%NAME%", CommandValueSanitizer.sanitize(preLoginEvent.getName()))
                            .replace("%IP%", CommandValueSanitizer.sanitize(ipAddress))
                            .replace("%MATCH%", CommandValueSanitizer.sanitize(matchedPart))
            ));
        }
        if (emitActions && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.username.send-webhook.enabled")) {
            String webhookMessage = plainMessage("messages.username-webhook",
                    "%NAME%", preLoginEvent.getName(),
                    "%IP%", ipAddress,
                    "%MATCH%", matchedPart);
            CGWebHookHelper.sendWebHook(ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.username.send-webhook.url"), webhookMessage);
        }
        if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.username.kick-player")) {
            String kickMessage = message("messages.username-block",
                    "%IP%", ipAddress,
                    "%NAME%", preLoginEvent.getName(),
                    "%MATCH%", matchedPart);
            preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage);
            recordPreSonarBlock(preSonarPhase);
        }
    }

    private void handleIspBlock(AsyncPlayerPreLoginEvent preLoginEvent,
                                String ipAddress,
                                IspBlockResult ispBlockResult,
                                boolean preSonarPhase) {
        GeoResult geoResult = ispBlockResult.getGeoResult();
        boolean emitActions = ConnectionGuard.shouldEmitActions("isp", ipAddress);
        if (emitActions && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.isp.notify-staff")) {
            String notifyMessage = message("messages.isp-notify",
                    "%IP%", ipAddress,
                    "%NAME%", preLoginEvent.getName(),
                    "%ISP%", geoResult.getIspName(),
                    "%ASN%", geoResult.getAsn(),
                    "%MATCH%", ispBlockResult.getMatchedValue());
            Bukkit.broadcast(notifyMessage, "twiantivpn.notify.isp");
        }
        if (emitActions && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.isp.execute-command.enabled")) {
            Bukkit.getScheduler().runTask(ConnectionGuardSpigotPlugin.getInstance(), () -> Bukkit.dispatchCommand(
                    Bukkit.getConsoleSender(),
                    ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.isp.execute-command.command")
                            .replace("%NAME%", CommandValueSanitizer.sanitize(preLoginEvent.getName()))
                            .replace("%IP%", CommandValueSanitizer.sanitize(ipAddress))
                            .replace("%ISP%", CommandValueSanitizer.sanitize(geoResult.getIspName()))
                            .replace("%ASN%", CommandValueSanitizer.sanitize(geoResult.getAsn()))
                            .replace("%MATCH%", CommandValueSanitizer.sanitize(ispBlockResult.getMatchedValue()))
            ));
        }
        if (emitActions && ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.isp.send-webhook.enabled")) {
            String webhookMessage = plainMessage("messages.isp-webhook",
                    "%NAME%", preLoginEvent.getName(),
                    "%IP%", ipAddress,
                    "%ISP%", geoResult.getIspName(),
                    "%ASN%", geoResult.getAsn(),
                    "%MATCH%", ispBlockResult.getMatchedValue());
            CGWebHookHelper.sendWebHook(ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.isp.send-webhook.url"), webhookMessage);
        }
        if (ConnectionGuardSpigotPlugin.getInstance().getConfig().getBoolean("behavior.isp.kick-player")) {
            String kickMessage = message("messages.isp-block",
                    "%IP%", ipAddress,
                    "%NAME%", preLoginEvent.getName(),
                    "%ISP%", geoResult.getIspName(),
                    "%ASN%", geoResult.getAsn(),
                    "%MATCH%", ispBlockResult.getMatchedValue());
            preLoginEvent.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, kickMessage);
            recordPreSonarBlock(preSonarPhase);
        }
    }

    private void recordPreSonarBlock(boolean preSonarPhase) {
        if (preSonarPhase) {
            AdaptiveLoginOrderService.getInstance().recordPreSonarBlock();
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
