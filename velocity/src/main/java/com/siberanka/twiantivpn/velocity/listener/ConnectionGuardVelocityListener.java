package com.siberanka.twiantivpn.velocity.listener;

import com.siberanka.twiantivpn.core.asteroid.AsteroidRegistryHook;
import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.geo.GeoResult;
import com.siberanka.twiantivpn.core.isp.IspBlockResult;
import com.siberanka.twiantivpn.core.luckperms.CGLuckPermsHelper;
import com.siberanka.twiantivpn.core.vpn.VpnResult;
import com.siberanka.twiantivpn.core.webhook.CGWebHookHelper;
import com.siberanka.twiantivpn.velocity.ConnectionGuardVelocityPlugin;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ConnectionGuardVelocityListener {
    @Subscribe
    public EventTask onPreLogin(PreLoginEvent loginEvent) {
        String ipAddress = loginEvent.getConnection().getRemoteAddress().getHostString();
        String playerUuid = (loginEvent.getUniqueId() != null) ? loginEvent.getUniqueId().toString() : "";
        String playerUsername = loginEvent.getUsername();

        Optional<String> blockedUsernamePart = ConnectionGuard.getBlockedUsernamePart(playerUsername);
        if (blockedUsernamePart.isPresent()) {
            return EventTask.async(() -> handleUsernameBlock(loginEvent, ipAddress, playerUsername, blockedUsernamePart.get()));
        }

        if (shouldBypassAsteroid(loginEvent)) {
            return EventTask.async(() -> {
            });
        }

        CompletableFuture<VpnResult> vpnResultFuture;
        CompletableFuture<Optional<GeoResult>> geoResultOptionalFuture;

        // Check if ip address is in exemption lists
        if (
                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.vpn.exemptions").contains(ipAddress)
                        || ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.vpn.exemptions").contains(playerUuid)
                        || ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.vpn.exemptions").contains(playerUsername)
        ) {
            vpnResultFuture = CompletableFuture.completedFuture(new VpnResult(ipAddress, false));
        } else {
            vpnResultFuture = ConnectionGuard.getVpnResult(ipAddress);
        }

        if (
                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.geo.exemptions").contains(ipAddress)
                        || ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.geo.exemptions").contains(playerUuid)
                        || ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getStringList("behavior.geo.exemptions").contains(playerUsername)
        ) {
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
                if (CGLuckPermsHelper.hasPermission(loginEvent.getUniqueId(), "connectionguard.exemption.vpn").join()) {
                    vpnResult = new VpnResult(ipAddress, false);
                }
            }

            if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.use-permission-exemption")) {
                if (CGLuckPermsHelper.hasPermission(loginEvent.getUniqueId(), "connectionguard.exemption.geo").join()) {
                    geoResultOptional = Optional.empty();
                }
            }



            if (vpnResult.isVpn()) {
                // Check if staff should be notified
                if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.notify-staff")) {
                    Component notifyMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                            ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.vpn-notify")
                                    .replace("%IP%", vpnResult.getIpAddress())
                                    .replace("%NAME%", playerUsername)
                    );
                    broadcastMessage(notifyMessage, "connectionguard.notify.vpn");
                }

                // Check if command should be executed on flag
                if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.execute-command.enabled")) {
                    ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getCommandManager().executeAsync(
                            ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getConsoleCommandSource(),
                            ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.vpn.execute-command.command")
                                    .replace("%NAME%", playerUsername)
                                    .replace("%IP%", ipAddress)
                    );
                }

                // Check if WebHook should be executed
                if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.send-webhook.enabled")) {
                    String webhookMessage = ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.vpn-webhook")
                            .replace("%NAME%", playerUsername)
                            .replace("%IP%", ipAddress);
                    String webhookUrl = ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.vpn.send-webhook.url");

                    CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
                }

                // Check if player should be kicked
                if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.vpn.kick-player")) {
                    Component kickMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                            ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.vpn-block")
                                    .replace("%IP%", vpnResult.getIpAddress())
                                    .replace("%NAME%", playerUsername)
                    );

                    // loginEvent.setResult(ResultedEvent.ComponentResult.denied(kickMessage));
                    loginEvent.setResult(PreLoginEvent.PreLoginComponentResult.denied(kickMessage));
                    return;
                }
            }

            if (geoResultOptional.isPresent()) {
                GeoResult geoResult = geoResultOptional.get();
                Optional<IspBlockResult> ispBlockResult = ConnectionGuard.getIspBlockResult(geoResult);
                if (ispBlockResult.isPresent()) {
                    handleIspBlock(loginEvent, ipAddress, playerUsername, ispBlockResult.get());
                    return;
                }

                boolean isGeoFlagged = false;

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

                if (isGeoFlagged) {
                    // Check if staff should be notified
                    if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.notify-staff")) {
                        Component notifyMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.geo-notify")
                                        .replace("%IP%", geoResult.getIpAddress())
                                        .replace("%COUNTRY%", geoResult.getCountryName())
                                        .replace("%CITY%", geoResult.getCityName())
                                        .replace("%ISP%", geoResult.getIspName())
                                        .replace("%NAME%", playerUsername)
                        );
                        broadcastMessage(notifyMessage, "connectionguard.notify.geo");
                    }

                    // Check if command should be executed on flag
                    if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.execute-command.enabled")) {
                        ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getCommandManager().executeAsync(
                                ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getConsoleCommandSource(),
                                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.geo.execute-command.command")
                                        .replace("%NAME%", playerUsername)
                                        .replace("%IP%", ipAddress)
                                        .replace("%COUNTRY%", geoResult.getCountryName())
                        );
                    }

                    // Check if WebHook should be executed
                    if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.send-webhook.enabled")) {
                        String webhookMessage = ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.geo-webhook")
                                .replace("%NAME%", playerUsername)
                                .replace("%IP%", ipAddress)
                                .replace("%COUNTRY%", geoResult.getCountryName())
                                .replace("%CITY%", geoResult.getCityName())
                                .replace("%ISP%", geoResult.getIspName());
                        String webhookUrl = ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.geo.send-webhook.url");

                        CGWebHookHelper.sendWebHook(webhookUrl, webhookMessage);
                    }

                    // Check if player should be kicked
                    if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.geo.kick-player")) {
                        Component kickMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.geo-block")
                                        .replace("%IP%", geoResult.getIpAddress())
                                        .replace("%COUNTRY%", geoResult.getCountryName())
                                        .replace("%CITY%", geoResult.getCityName())
                                        .replace("%ISP%", geoResult.getIspName())
                                        .replace("%NAME%", playerUsername)
                        );

                        // loginEvent.setResult(ResultedEvent.ComponentResult.denied(kickMessage));
                        loginEvent.setResult(PreLoginEvent.PreLoginComponentResult.denied(kickMessage));
                        return;
                    }
                }

                // loginEvent.setResult(ResultedEvent.ComponentResult.allowed());
                // loginEvent.setResult(PreLoginEvent.PreLoginComponentResult.allowed());
            }
            } catch (Exception exception) {
                ConnectionGuard.getLogger().info("Login check failed: " + exception.getMessage());
            }
        });
    }

    private void broadcastMessage(Component message, String permission) {
        for (Player player : ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getAllPlayers()) {
            if (player.hasPermission(permission)) {
                player.sendMessage(message);
            }
        }
    }

    private void handleUsernameBlock(PreLoginEvent loginEvent, String ipAddress, String playerUsername, String matchedPart) {
        if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.username.notify-staff")) {
            Component notifyMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                    ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.username-notify")
                            .replace("%IP%", ipAddress)
                            .replace("%NAME%", playerUsername)
                            .replace("%MATCH%", matchedPart)
            );
            broadcastMessage(notifyMessage, "connectionguard.notify.username");
        }
        if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.username.execute-command.enabled")) {
            ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getCommandManager().executeAsync(
                    ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getConsoleCommandSource(),
                    ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.username.execute-command.command")
                            .replace("%NAME%", playerUsername)
                            .replace("%IP%", ipAddress)
                            .replace("%MATCH%", matchedPart)
            );
        }
        if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.username.send-webhook.enabled")) {
            String webhookMessage = ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.username-webhook")
                    .replace("%NAME%", playerUsername)
                    .replace("%IP%", ipAddress)
                    .replace("%MATCH%", matchedPart);
            CGWebHookHelper.sendWebHook(ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.username.send-webhook.url"), webhookMessage);
        }
        if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.username.kick-player")) {
            Component kickMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                    ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.username-block")
                            .replace("%IP%", ipAddress)
                            .replace("%NAME%", playerUsername)
                            .replace("%MATCH%", matchedPart)
            );
            loginEvent.setResult(PreLoginEvent.PreLoginComponentResult.denied(kickMessage));
        }
    }

    private void handleIspBlock(PreLoginEvent loginEvent, String ipAddress, String playerUsername, IspBlockResult ispBlockResult) {
        GeoResult geoResult = ispBlockResult.getGeoResult();
        if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.isp.notify-staff")) {
            Component notifyMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                    ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.isp-notify")
                            .replace("%IP%", ipAddress)
                            .replace("%NAME%", playerUsername)
                            .replace("%ISP%", geoResult.getIspName())
                            .replace("%ASN%", geoResult.getAsn())
                            .replace("%MATCH%", ispBlockResult.getMatchedValue())
            );
            broadcastMessage(notifyMessage, "connectionguard.notify.isp");
        }
        if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.isp.execute-command.enabled")) {
            ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getCommandManager().executeAsync(
                    ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getConsoleCommandSource(),
                    ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.isp.execute-command.command")
                            .replace("%NAME%", playerUsername)
                            .replace("%IP%", ipAddress)
                            .replace("%ISP%", geoResult.getIspName())
                            .replace("%ASN%", geoResult.getAsn())
                            .replace("%MATCH%", ispBlockResult.getMatchedValue())
            );
        }
        if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.isp.send-webhook.enabled")) {
            String webhookMessage = ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.isp-webhook")
                    .replace("%NAME%", playerUsername)
                    .replace("%IP%", ipAddress)
                    .replace("%ISP%", geoResult.getIspName())
                    .replace("%ASN%", geoResult.getAsn())
                    .replace("%MATCH%", ispBlockResult.getMatchedValue());
            CGWebHookHelper.sendWebHook(ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getString("behavior.isp.send-webhook.url"), webhookMessage);
        }
        if (ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getConfig().getBoolean("behavior.isp.kick-player")) {
            Component kickMessage = LegacyComponentSerializer.legacyAmpersand().deserialize(
                    ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.isp-block")
                            .replace("%IP%", ipAddress)
                            .replace("%NAME%", playerUsername)
                            .replace("%ISP%", geoResult.getIspName())
                            .replace("%ASN%", geoResult.getAsn())
                            .replace("%MATCH%", ispBlockResult.getMatchedValue())
            );
            loginEvent.setResult(PreLoginEvent.PreLoginComponentResult.denied(kickMessage));
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
