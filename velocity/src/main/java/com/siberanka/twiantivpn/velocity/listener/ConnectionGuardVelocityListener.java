package com.siberanka.twiantivpn.velocity.listener;

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
import com.siberanka.twiantivpn.velocity.ConnectionGuardVelocityPlugin;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.net.InetAddress;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;

public class ConnectionGuardVelocityListener {
    private final Map<PreLoginEvent, Set<CheckModule>> deferredEvents =
            Collections.synchronizedMap(new WeakHashMap<>());

    @Subscribe(order = PostOrder.FIRST, priority = Short.MAX_VALUE)
    public EventTask onPreLoginBeforeAntiBot(PreLoginEvent loginEvent) {
        AdaptiveLoginOrderService.ModulePlan plan =
                AdaptiveLoginOrderService.getInstance().snapshotModulePlan();
        if (plan.isRunBeforePlatform()) {
            if (!plan.getAfterPlatformModules().isEmpty()) {
                deferredEvents.put(loginEvent, plan.getAfterPlatformModules());
            }
            return handlePreLogin(loginEvent, plan.getBeforePlatformModules(), true);
        }
        deferredEvents.put(loginEvent, plan.getAfterPlatformModules());
        return EventTask.resumeWhenComplete(CompletableFuture.completedFuture(null));
    }

    @Subscribe(order = PostOrder.LAST, priority = Short.MIN_VALUE)
    public EventTask onPreLoginAfterAntiBot(PreLoginEvent loginEvent) {
        Set<CheckModule> modules = deferredEvents.remove(loginEvent);
        if (modules != null && !modules.isEmpty() && loginEvent.getResult().isAllowed()) {
            return handlePreLogin(loginEvent, modules, false);
        }
        return EventTask.resumeWhenComplete(CompletableFuture.completedFuture(null));
    }

    private EventTask handlePreLogin(PreLoginEvent loginEvent, Set<CheckModule> modules, boolean preSonarPhase) {
        String ipAddress = getIpAddress(loginEvent);
        String playerUuid = (loginEvent.getUniqueId() != null) ? loginEvent.getUniqueId().toString() : "";
        String playerUsername = loginEvent.getUsername();

        if (ConnectionGuard.isRuntimeWhitelistedIp(ipAddress)) {
            return EventTask.resumeWhenComplete(CompletableFuture.completedFuture(null));
        }

        if (modules.contains(CheckModule.USERNAME_FILTER)) {
            Optional<String> blockedUsernamePart = ConnectionGuard.getBlockedUsernamePart(playerUsername);
            if (blockedUsernamePart.isPresent()) {
                return EventTask.async(() -> handleUsernameBlock(
                        loginEvent,
                        ipAddress,
                        playerUsername,
                        blockedUsernamePart.get(),
                        preSonarPhase
                ));
            }
        }

        if (shouldBypassAsteroid(loginEvent)) {
            return EventTask.async(() -> {
            });
        }

        CompletableFuture<VpnResult> vpnResultFuture;
        CompletableFuture<Optional<GeoResult>> geoResultOptionalFuture;

        Set<String> selectedVpnProviders = CheckModule.providerNames(modules);
        boolean checkVpn = modules.contains(CheckModule.PROXY_BLOCKLIST)
                || !selectedVpnProviders.isEmpty();
        boolean checkGeo = modules.contains(CheckModule.GEO_BLOCK)
                || modules.contains(CheckModule.ISP_BLOCK);

        // Check if ip address is in exemption lists
        if (!checkVpn || (
                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.vpn.exemptions").contains(ipAddress)
                        || ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.vpn.exemptions").contains(playerUuid)
                        || ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.vpn.exemptions").contains(playerUsername)
        )) {
            vpnResultFuture = CompletableFuture.completedFuture(new VpnResult(ipAddress, false));
        } else {
            vpnResultFuture = ConnectionGuard.getVpnResult(
                    ipAddress,
                    modules.contains(CheckModule.PROXY_BLOCKLIST),
                    selectedVpnProviders
            );
        }

        if (!checkGeo || (
                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.geo.exemptions").contains(ipAddress)
                        || ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.geo.exemptions").contains(playerUuid)
                        || ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.geo.exemptions").contains(playerUsername)
        )) {
            geoResultOptionalFuture = CompletableFuture.completedFuture(Optional.empty());
        } else {
            geoResultOptionalFuture = ConnectionGuard.getGeoResult(ipAddress);
        }

        return EventTask.async(() -> {
            try {
            VpnResult vpnResult = vpnResultFuture.join();
            Optional<GeoResult> geoResultOptional = geoResultOptionalFuture.join();

            // Check if player has a permission exemption
            if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.use-permission-exemption")) {
                if (CGLuckPermsHelper.hasPermission(loginEvent.getUniqueId(), "twiantivpn.exemption.vpn").join()) {
                    vpnResult = new VpnResult(ipAddress, false);
                }
            }

            if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.use-permission-exemption")) {
                if (CGLuckPermsHelper.hasPermission(loginEvent.getUniqueId(), "twiantivpn.exemption.geo").join()) {
                    geoResultOptional = Optional.empty();
                }
            }



            if (vpnResult.isVpn()) {
                boolean emitActions = ConnectionGuard.shouldEmitActions("vpn", ipAddress);
                // Check if staff should be notified
                if (emitActions && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.notify-staff")) {
                    Component notifyMessage = component("messages.vpn-notify",
                            "%IP%", vpnResult.getIpAddress(),
                            "%NAME%", playerUsername);
                    broadcastMessage(notifyMessage, "twiantivpn.notify.vpn");
                }

                // Check if command should be executed on flag
                if (emitActions && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.execute-command.enabled")) {
                    ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getCommandManager().executeAsync(
                            ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getConsoleCommandSource(),
                            ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.vpn.execute-command.command")
                                    .replace("%NAME%", CommandValueSanitizer.sanitize(playerUsername))
                                    .replace("%IP%", CommandValueSanitizer.sanitize(ipAddress))
                    );
                }

                // Check if WebHook should be executed
                if (emitActions && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.send-webhook.enabled")) {
                    String webhookMessage = plainMessage("messages.vpn-webhook",
                            "%NAME%", playerUsername,
                            "%IP%", ipAddress);
                    String webhookUrl = ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.vpn.send-webhook.url");

                    CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
                }

                // Check if player should be kicked
                if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.kick-player")) {
                    Component kickMessage = component("messages.vpn-block",
                            "%IP%", vpnResult.getIpAddress(),
                            "%NAME%", playerUsername);

                    // loginEvent.setResult(ResultedEvent.ComponentResult.denied(kickMessage));
                    loginEvent.setResult(PreLoginEvent.PreLoginComponentResult.denied(kickMessage));
                    recordPreSonarBlock(preSonarPhase);
                    return;
                }
            }

            if (geoResultOptional.isPresent()) {
                GeoResult geoResult = geoResultOptional.get();
                if (modules.contains(CheckModule.ISP_BLOCK)) {
                    Optional<IspBlockResult> ispBlockResult = ConnectionGuard.getIspBlockResult(geoResult);
                    if (ispBlockResult.isPresent()) {
                        handleIspBlock(loginEvent, ipAddress, playerUsername, ispBlockResult.get(), preSonarPhase);
                        return;
                    }
                }

                boolean isGeoFlagged = false;

                if (modules.contains(CheckModule.GEO_BLOCK)) {
                    switch (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.geo.type").toLowerCase()) {
                    case "blacklist":
                        if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.geo.list").contains(geoResult.getCountryName()))
                            isGeoFlagged = true;
                        break;
                    case "whitelist":
                        if (!ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.geo.list").contains(geoResult.getCountryName()))
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
                    if (emitActions && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.notify-staff")) {
                        Component notifyMessage = component("messages.geo-notify",
                                "%IP%", geoResult.getIpAddress(),
                                "%COUNTRY%", geoResult.getCountryName(),
                                "%CITY%", geoResult.getCityName(),
                                "%ISP%", geoResult.getIspName(),
                                "%NAME%", playerUsername);
                        broadcastMessage(notifyMessage, "twiantivpn.notify.geo");
                    }

                    // Check if command should be executed on flag
                    if (emitActions && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.execute-command.enabled")) {
                        ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getCommandManager().executeAsync(
                                ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getConsoleCommandSource(),
                                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.geo.execute-command.command")
                                        .replace("%NAME%", CommandValueSanitizer.sanitize(playerUsername))
                                        .replace("%IP%", CommandValueSanitizer.sanitize(ipAddress))
                                        .replace("%COUNTRY%", CommandValueSanitizer.sanitize(geoResult.getCountryName()))
                        );
                    }

                    // Check if WebHook should be executed
                    if (emitActions && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.send-webhook.enabled")) {
                        String webhookMessage = plainMessage("messages.geo-webhook",
                                "%NAME%", playerUsername,
                                "%IP%", ipAddress,
                                "%COUNTRY%", geoResult.getCountryName(),
                                "%CITY%", geoResult.getCityName(),
                                "%ISP%", geoResult.getIspName());
                        String webhookUrl = ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.geo.send-webhook.url");

                        CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
                    }

                    // Check if player should be kicked
                    if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.kick-player")) {
                        Component kickMessage = component("messages.geo-block",
                                "%IP%", geoResult.getIpAddress(),
                                "%COUNTRY%", geoResult.getCountryName(),
                                "%CITY%", geoResult.getCityName(),
                                "%ISP%", geoResult.getIspName(),
                                "%NAME%", playerUsername);

                        // loginEvent.setResult(ResultedEvent.ComponentResult.denied(kickMessage));
                        loginEvent.setResult(PreLoginEvent.PreLoginComponentResult.denied(kickMessage));
                        recordPreSonarBlock(preSonarPhase);
                        return;
                    }
                }

                // loginEvent.setResult(ResultedEvent.ComponentResult.allowed());
                // loginEvent.setResult(PreLoginEvent.PreLoginComponentResult.allowed());
            }
            } catch (Exception exception) {
                ConnectionGuard.reportError("Velocity login check", exception);
            }
        });
    }

    private String getIpAddress(PreLoginEvent loginEvent) {
        InetAddress address = loginEvent.getConnection().getRemoteAddress().getAddress();
        return address == null
                ? loginEvent.getConnection().getRemoteAddress().getHostString()
                : address.getHostAddress();
    }

    private void broadcastMessage(Component message, String permission) {
        for (Player player : ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getAllPlayers()) {
            if (player.hasPermission(permission)) {
                player.sendMessage(message);
            }
        }
    }

    private Component component(String path, String... placeholders) {
        return LegacyComponentSerializer.legacySection().deserialize(message(path, placeholders));
    }

    private String message(String path, String... placeholders) {
        return MessageFormatter.toLegacyText(
                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString(path),
                MessageFormatter.placeholdersWithKickLayout(prefix(), kickPrefix(), kickContact(), placeholders)
        );
    }

    private String plainMessage(String path, String... placeholders) {
        return MessageFormatter.toPlainText(
                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString(path),
                MessageFormatter.placeholdersWithKickLayout(prefix(), kickPrefix(), kickContact(), placeholders)
        );
    }

    private String prefix() {
        return ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.prefix", "&bTwiAntiVpn &7|");
    }

    private String kickPrefix() {
        return ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString(
                "messages.kick-prefix",
                "&b&lTwiAntiVpn"
        );
    }

    private String kickContact() {
        return ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString(
                "messages.kick-contact",
                "&#35D3FFstore.example.net &8| &#6F7DFFdiscord.gg/invite"
        );
    }

    private void handleUsernameBlock(PreLoginEvent loginEvent,
                                     String ipAddress,
                                     String playerUsername,
                                     String matchedPart,
                                     boolean preSonarPhase) {
        boolean emitActions = ConnectionGuard.shouldEmitActions("username", ipAddress);
        if (emitActions && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.username.notify-staff")) {
            Component notifyMessage = component("messages.username-notify",
                    "%IP%", ipAddress,
                    "%NAME%", playerUsername,
                    "%MATCH%", matchedPart);
            broadcastMessage(notifyMessage, "twiantivpn.notify.username");
        }
        if (emitActions && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.username.execute-command.enabled")) {
            ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getCommandManager().executeAsync(
                    ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getConsoleCommandSource(),
                    ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.username.execute-command.command")
                            .replace("%NAME%", CommandValueSanitizer.sanitize(playerUsername))
                            .replace("%IP%", CommandValueSanitizer.sanitize(ipAddress))
                            .replace("%MATCH%", CommandValueSanitizer.sanitize(matchedPart))
            );
        }
        if (emitActions && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.username.send-webhook.enabled")) {
            String webhookMessage = plainMessage("messages.username-webhook",
                    "%NAME%", playerUsername,
                    "%IP%", ipAddress,
                    "%MATCH%", matchedPart);
            CGWebHookHelper.sendWebHook(ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.username.send-webhook.url"), webhookMessage);
        }
        if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.username.kick-player")) {
            Component kickMessage = component("messages.username-block",
                    "%IP%", ipAddress,
                    "%NAME%", playerUsername,
                    "%MATCH%", matchedPart);
            loginEvent.setResult(PreLoginEvent.PreLoginComponentResult.denied(kickMessage));
            recordPreSonarBlock(preSonarPhase);
        }
    }

    private void handleIspBlock(PreLoginEvent loginEvent,
                                String ipAddress,
                                String playerUsername,
                                IspBlockResult ispBlockResult,
                                boolean preSonarPhase) {
        GeoResult geoResult = ispBlockResult.getGeoResult();
        boolean emitActions = ConnectionGuard.shouldEmitActions("isp", ipAddress);
        if (emitActions && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.isp.notify-staff")) {
            Component notifyMessage = component("messages.isp-notify",
                    "%IP%", ipAddress,
                    "%NAME%", playerUsername,
                    "%ISP%", geoResult.getIspName(),
                    "%ASN%", geoResult.getAsn(),
                    "%MATCH%", ispBlockResult.getMatchedValue());
            broadcastMessage(notifyMessage, "twiantivpn.notify.isp");
        }
        if (emitActions && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.isp.execute-command.enabled")) {
            ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getCommandManager().executeAsync(
                    ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getConsoleCommandSource(),
                    ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.isp.execute-command.command")
                            .replace("%NAME%", CommandValueSanitizer.sanitize(playerUsername))
                            .replace("%IP%", CommandValueSanitizer.sanitize(ipAddress))
                            .replace("%ISP%", CommandValueSanitizer.sanitize(geoResult.getIspName()))
                            .replace("%ASN%", CommandValueSanitizer.sanitize(geoResult.getAsn()))
                            .replace("%MATCH%", CommandValueSanitizer.sanitize(ispBlockResult.getMatchedValue()))
            );
        }
        if (emitActions && ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.isp.send-webhook.enabled")) {
            String webhookMessage = plainMessage("messages.isp-webhook",
                    "%NAME%", playerUsername,
                    "%IP%", ipAddress,
                    "%ISP%", geoResult.getIspName(),
                    "%ASN%", geoResult.getAsn(),
                    "%MATCH%", ispBlockResult.getMatchedValue());
            CGWebHookHelper.sendWebHook(ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.isp.send-webhook.url"), webhookMessage);
        }
        if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.isp.kick-player")) {
            Component kickMessage = component("messages.isp-block",
                    "%IP%", ipAddress,
                    "%NAME%", playerUsername,
                    "%ISP%", geoResult.getIspName(),
                    "%ASN%", geoResult.getAsn(),
                    "%MATCH%", ispBlockResult.getMatchedValue());
            loginEvent.setResult(PreLoginEvent.PreLoginComponentResult.denied(kickMessage));
            recordPreSonarBlock(preSonarPhase);
        }
    }

    private void recordPreSonarBlock(boolean preSonarPhase) {
        if (preSonarPhase) {
            AdaptiveLoginOrderService.getInstance().recordPreSonarBlock();
        }
    }

    private boolean shouldBypassAsteroid(PreLoginEvent loginEvent) {
        if (!ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("asteroid-proxy.enabled")) {
            return false;
        }
        boolean asteroidInstalled = false;
        for (String pluginName : ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("asteroid-proxy.plugin-names")) {
            if (ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getPluginManager().getPlugin(pluginName.toLowerCase()).isPresent()) {
                asteroidInstalled = true;
                break;
            }
        }
        return asteroidInstalled && loginEvent.getUniqueId() != null && AsteroidRegistryHook.isFakePlayer(loginEvent.getUniqueId());
    }
}
