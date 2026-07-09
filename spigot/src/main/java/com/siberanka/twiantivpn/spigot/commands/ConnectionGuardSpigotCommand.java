package com.siberanka.twiantivpn.spigot.commands;

import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.geo.GeoResult;
import com.siberanka.twiantivpn.core.message.MessageFormatter;
import com.siberanka.twiantivpn.core.net.IpAddressUtil;
import com.siberanka.twiantivpn.core.vpn.VpnResult;
import com.siberanka.twiantivpn.spigot.ConnectionGuardSpigotPlugin;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ConnectionGuardSpigotCommand implements TabExecutor {
    @Override
    public boolean onCommand(CommandSender commandSender, Command command, String s, String[] args) {
        String noPermissionMessage = message("command.no-permission");

        if (args.length == 0) {
            if (!commandSender.hasPermission("twiantivpn.command.help")) {
                commandSender.sendMessage(noPermissionMessage);
                return true;
            }
            return sendHelpMessage(commandSender);
        }
        if (args.length == 1) {
            switch (args[0].toLowerCase()) {
                case "help":
                    if (!commandSender.hasPermission("twiantivpn.command.help")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return true;
                    }
                    return sendHelpMessage(commandSender);
                case "reload":
                    if (!commandSender.hasPermission("twiantivpn.command.reload")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return true;
                    }
                    return reloadPlugin(commandSender);
                case "clear":
                    if (!commandSender.hasPermission("twiantivpn.command.clear")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return true;
                    }
                    return clearCache(commandSender);
                default:
                    return sendUnknownSubcommandMessage(commandSender);
            }
        }

        if (args.length == 2) {
            switch (args[0].toLowerCase()) {
                case "clear":
                    if (!commandSender.hasPermission("twiantivpn.command.clear")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return true;
                    }
                    return clearCache(commandSender, args[1]);
                case "info":
                    if (!commandSender.hasPermission("twiantivpn.command.info")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return true;
                    }
                    return sendInformationMessage(commandSender, args[1]);
                default:
                    return sendUnknownSubcommandMessage(commandSender);
            }
        }
        return sendUnknownSubcommandMessage(commandSender);
    }

    private boolean sendUnknownSubcommandMessage(CommandSender commandSender) {
        commandSender.sendMessage(message("command.unknown-subcommand"));

        return true;
    }

    private boolean sendInformationMessage(CommandSender commandSender, String entry) {
        CompletableFuture.runAsync(() -> {
            String ipAddress;
            String queriedInput;

            if (Bukkit.getPlayer(entry) != null) {
                Player player = Bukkit.getPlayer(entry);
                ipAddress = player.getAddress().getAddress().getHostAddress();
                queriedInput = player.getName();
            } else {
                try {
                    Player player = Bukkit.getPlayer(UUID.fromString(entry));
                    if (player == null) {
                        sendInvalidArgumentMessage(commandSender);
                        return;
                    }
                    ipAddress = player.getAddress().getAddress().getHostAddress();
                    queriedInput = player.getName();
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

            String isVpn = message("messages.info.not-vpn");
            if (vpnResult.isVpn()) {
                isVpn = message("messages.info.is-vpn");
            }

            for (String line : ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getStringList("messages.info.text")) {
                commandSender.sendMessage(
                        format(line,
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

    private boolean clearCache(CommandSender commandSender, String entry) {
        // Async, because InetAddress.getByName could affect the main thread (used to determine, if it is a valid hostname/ip address)
        CompletableFuture.runAsync(() -> {
            String ipAddress;
            String queriedInput;

            if (Bukkit.getPlayer(entry) != null) {
                Player player = Bukkit.getPlayer(entry);
                ipAddress = player.getAddress().getAddress().getHostAddress();
                queriedInput = player.getName();
            } else {
                try {
                    Player player = Bukkit.getPlayer(UUID.fromString(entry));
                    if (player == null) {
                        sendInvalidArgumentMessage(commandSender);
                        return;
                    }
                    ipAddress = player.getAddress().getAddress().getHostAddress();
                    queriedInput = player.getName();
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
                    message("command.clear.clear-specific", "%ENTRY%", queriedInput)
            );
        });

        return true;
    }

    private void sendInvalidArgumentMessage(CommandSender commandSender) {
        commandSender.sendMessage(message("command.invalid-argument"));
    }

    private boolean clearCache(CommandSender commandSender) {
        ConnectionGuard.getCacheProvider().removeAllVpnResults();
        ConnectionGuard.getCacheProvider().removeAllGeoResults();
        commandSender.sendMessage(message("command.clear.clear-all"));
        return true;
    }

    private boolean sendHelpMessage(CommandSender commandSender) {
        for (String line : ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getStringList("messages.help")) {
            commandSender.sendMessage(format(line));
        }

        return true;
    }

    private boolean reloadPlugin(CommandSender commandSender) {
        ConnectionGuardSpigotPlugin.getInstance().reloadAllConfigs();
        commandSender.sendMessage(message("command.config-reload"));
        return true;
    }

    private String message(String path, String... placeholders) {
        return format(ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString(path), placeholders);
    }

    private String format(String raw, String... placeholders) {
        return MessageFormatter.toLegacyText(
                raw,
                MessageFormatter.placeholdersWithPrefix(prefix(), placeholders)
        );
    }

    private String prefix() {
        return ConnectionGuardSpigotPlugin.getInstance().getLanguageConfig().getString("messages.prefix", "&bTwiAntiVpn &7|");
    }

    @Override
    public List<String> onTabComplete(CommandSender commandSender, Command command, String s, String[] strings) {
        List<String> proposals = new ArrayList<>();
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
                for (Player player : Bukkit.getOnlinePlayers()) {
                    proposals.add(player.getName());
                }
            }
            if (strings[0].equalsIgnoreCase("clear")) {
                proposals.add("1.1.1.1");
                for (Player player : Bukkit.getOnlinePlayers()) {
                    proposals.add(player.getName());
                }
            }
        }
        return proposals;
    }
}
