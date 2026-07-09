package com.siberanka.twiantivpn.velocity.commands;

import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.geo.GeoResult;
import com.siberanka.twiantivpn.core.message.MessageFormatter;
import com.siberanka.twiantivpn.core.net.IpAddressUtil;
import com.siberanka.twiantivpn.core.vpn.VpnResult;
import com.siberanka.twiantivpn.velocity.ConnectionGuardVelocityPlugin;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ConnectionGuardVelocityCommand implements SimpleCommand {
    @Override
    public void execute(Invocation invocation) {
        CommandSource commandSender = invocation.source();
        String[] args = invocation.arguments();

        Component noPermissionMessage = component("command.no-permission");

        if (args.length == 0) {
            if (!commandSender.hasPermission("twiantivpn.command.help")) {
                commandSender.sendMessage(noPermissionMessage);
                return;
            }
            sendHelpMessage(commandSender);
            return;
        }
        if (args.length == 1) {
            switch (args[0].toLowerCase()) {
                case "help":
                    if (!commandSender.hasPermission("twiantivpn.command.help")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return;
                    }
                    sendHelpMessage(commandSender);
                    return;
                case "reload":
                    if (!commandSender.hasPermission("twiantivpn.command.reload")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return;
                    }
                    reloadPlugin(commandSender);
                    return;
                case "clear":
                    if (!commandSender.hasPermission("twiantivpn.command.clear")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return;
                    }
                    clearCache(commandSender);
                    return;
                default:
                    sendUnknownSubcommandMessage(commandSender);
                    return;
            }
        }

        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "clear":
                    if (!commandSender.hasPermission("twiantivpn.command.clear")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return;
                    }
                    clearCache(commandSender, args[1]);
                    return;
                case "info":
                    if (!commandSender.hasPermission("twiantivpn.command.info")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return;
                    }
                    sendInformationMessage(commandSender, args[1]);
                    return;
                default:
                    sendUnknownSubcommandMessage(commandSender);
                    return;
            }
        }
        sendUnknownSubcommandMessage(commandSender);
    }

    private void sendUnknownSubcommandMessage(CommandSource commandSender) {
        commandSender.sendMessage(component("command.unknown-subcommand"));

        return;
    }

    private boolean sendInformationMessage(CommandSource commandSender, String entry) {
        CompletableFuture.runAsync(() -> {
            String ipAddress;
            String queriedInput;

            if (ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getPlayer(entry).isPresent()) {
                Player player = ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getPlayer(entry).get();
                ipAddress = player.getRemoteAddress().getAddress().getHostAddress();
                queriedInput = player.getUsername();
            } else {
                try {
                    Optional<Player> playerOptional = ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getPlayer(UUID.fromString(entry));
                    if (!playerOptional.isPresent()) {
                        sendInvalidArgumentMessage(commandSender);
                        return;
                    }
                    Player player = playerOptional.get();
                    ipAddress = player.getRemoteAddress().getAddress().getHostAddress();
                    queriedInput = player.getUsername();
                } catch (Exception e) {
                    Optional<String> parsedIpAddress = IpAddressUtil.toHostAddress(entry);
                    if (parsedIpAddress.isPresent()) {
                        ipAddress = parsedIpAddress.get();
                        queriedInput = ipAddress;
                    } else {
                        sendInvalidArgumentMessage(commandSender);
                        return;
                    }
                }
            }

            VpnResult vpnResult = ConnectionGuard.getVpnResult(ipAddress).join();
            Optional<GeoResult> geoResultOptional = ConnectionGuard.getGeoResult(ipAddress).join();

            GeoResult geoResult;

            if (geoResultOptional.isPresent()) {
                geoResult = geoResultOptional.get();
            } else {
                geoResult = new GeoResult(ipAddress, "-", "-", "-");
            }

            String isVpn = text("messages.info.not-vpn");

            if (vpnResult.isVpn()) {
                isVpn = text("messages.info.is-vpn");
            }

            for (String line : ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getStringList("messages.info.text")) {
                commandSender.sendMessage(
                        componentFromRaw(line,
                                "%INPUT%", queriedInput,
                                "%COUNTRY%", geoResult.getCountryName(),
                                "%CITY%", geoResult.getCityName(),
                                "%ISP%", geoResult.getIspName(),
                                "%ASN%", geoResult.getAsn(),
                                "%IS_VPN%", isVpn,
                                "%IP%", ipAddress)
                );
            }
        });

        return true;
    }

    private boolean clearCache(CommandSource commandSender, String entry) {
        // Async, because InetAddress.getByName could affect the main thread (used to determine, if it is a valid hostname/ip address)
        CompletableFuture.runAsync(() -> {
            String ipAddress;
            String queriedInput;

            if (ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getPlayer(entry).isPresent()) {
                Player player = ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getPlayer(entry).get();
                ipAddress = player.getRemoteAddress().getAddress().getHostAddress();
                queriedInput = player.getUsername();
            } else {
                try {
                    Optional<Player> playerOptional = ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getPlayer(UUID.fromString(entry));
                    if (!playerOptional.isPresent()) {
                        sendInvalidArgumentMessage(commandSender);
                        return;
                    }
                    Player player = playerOptional.get();
                    ipAddress = player.getRemoteAddress().getAddress().getHostAddress();
                    queriedInput = player.getUsername();
                } catch (Exception e) {
                    Optional<String> parsedIpAddress = IpAddressUtil.toHostAddress(entry);
                    if (parsedIpAddress.isPresent()) {
                        ipAddress = parsedIpAddress.get();
                        queriedInput = ipAddress;
                    } else {
                        sendInvalidArgumentMessage(commandSender);
                        return;
                    }
                }
            }

            ConnectionGuard.getCacheProvider().removeGeoResult(ipAddress);
            ConnectionGuard.getCacheProvider().removeVpnResult(ipAddress);
            commandSender.sendMessage(
                    component("command.clear.clear-specific", "%ENTRY%", queriedInput)
            );
        });

        return true;
    }

    private void sendInvalidArgumentMessage(CommandSource commandSender) {
        commandSender.sendMessage(component("command.invalid-argument"));
    }

    private boolean clearCache(CommandSource commandSender) {
        ConnectionGuard.getCacheProvider().removeAllVpnResults();
        ConnectionGuard.getCacheProvider().removeAllGeoResults();
        commandSender.sendMessage(component("command.clear.clear-all"));
        return true;
    }

    private boolean sendHelpMessage(CommandSource commandSender) {
        for (String line : ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getStringList("messages.help")) {
            commandSender.sendMessage(componentFromRaw(line));
        }

        return true;
    }

    private boolean reloadPlugin(CommandSource commandSender) {
        ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().load();
        ConnectionGuardVelocityPlugin.getInstance().configureGeoProviders();
        ConnectionGuardVelocityPlugin.getInstance().configureSecurityFilters();
        ConnectionGuardVelocityPlugin.getInstance().configureProxyBlocklist();
        ConnectionGuardVelocityPlugin.getInstance().configureSonarEarlyHook();

        commandSender.sendMessage(component("command.config-reload"));
        return true;
    }

    private Component component(String path, String... placeholders) {
        return componentFromRaw(
                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString(path),
                placeholders
        );
    }

    private Component componentFromRaw(String raw, String... placeholders) {
        return LegacyComponentSerializer.legacySection().deserialize(format(raw, placeholders));
    }

    private String text(String path, String... placeholders) {
        return format(
                ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString(path),
                placeholders
        );
    }

    private String format(String raw, String... placeholders) {
        return MessageFormatter.toLegacyText(
                raw,
                MessageFormatter.placeholdersWithPrefix(prefix(), placeholders)
        );
    }

    private String prefix() {
        return ConnectionGuardVelocityPlugin.getInstance().getCgVelocityConfig().getLanguageConfig().getString("messages.prefix", "&bTwiAntiVpn &7|");
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> proposals = new ArrayList<>();
            String[] strings = invocation.arguments();
            CommandSource commandSender = invocation.source();

            if (strings.length == 1) {
                if (commandSender.hasPermission("twiantivpn.command.help"))
                    proposals.add("help");
                if (commandSender.hasPermission("twiantivpn.command.info"))
                    proposals.add("info");
                if (commandSender.hasPermission("twiantivpn.command.clear"))
                    proposals.add("clear");
                if (commandSender.hasPermission("twiantivpn.command.reload"))
                    proposals.add("reload");
            }
            if (strings.length == 2) {
                if (strings[0].equalsIgnoreCase("info")) {
                    proposals.add("1.1.1.1");
                    for (Player player : ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getAllPlayers()) {
                        proposals.add(player.getUsername());
                    }
                }
                if (strings[0].equalsIgnoreCase("clear")) {
                    proposals.add("1.1.1.1");
                    for (Player player : ConnectionGuardVelocityPlugin.getInstance().getProxyServer().getAllPlayers()) {
                        proposals.add(player.getUsername());
                    }
                }
            }
            return proposals;
        });
    }
}
