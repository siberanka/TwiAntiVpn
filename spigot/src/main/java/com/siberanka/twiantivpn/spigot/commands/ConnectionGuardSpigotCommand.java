package com.siberanka.twiantivpn.spigot.commands;

import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.geo.GeoResult;
import com.siberanka.twiantivpn.core.integration.AdaptiveLoginOrderService;
import com.siberanka.twiantivpn.core.isp.IspBlockResult;
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
import java.util.concurrent.atomic.AtomicBoolean;

public class ConnectionGuardSpigotCommand implements TabExecutor {
    private final AtomicBoolean testRunning = new AtomicBoolean();
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
                case "whitelist":
                    if (!commandSender.hasPermission("twiantivpn.command.whitelist")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return true;
                    }
                    return whitelistIp(commandSender, args[1]);
                case "test":
                    if (!commandSender.hasPermission("twiantivpn.command.test")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return true;
                    }
                    if (args[1].equalsIgnoreCase("attack")) {
                        return sendAttackStatus(commandSender);
                    }
                    return testConnection(commandSender, args[1], null);
                default:
                    return sendUnknownSubcommandMessage(commandSender);
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("test")
                && args[1].equalsIgnoreCase("attack")) {
            if (!commandSender.hasPermission("twiantivpn.command.test")) {
                commandSender.sendMessage(noPermissionMessage);
                return true;
            }
            if (args[2].equalsIgnoreCase("on")) {
                AdaptiveLoginOrderService.getInstance().onSonarAttackDetected();
            } else if (args[2].equalsIgnoreCase("off")) {
                AdaptiveLoginOrderService.getInstance().onSonarAttackMitigated();
            } else {
                return sendUnknownSubcommandMessage(commandSender);
            }
            return sendAttackStatus(commandSender);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("test")
                && commandSender.hasPermission("twiantivpn.command.test")) {
            return testConnection(commandSender, args[1], args[2]);
        }
        return sendUnknownSubcommandMessage(commandSender);
    }

    private boolean sendAttackStatus(CommandSender sender) {
        AdaptiveLoginOrderService service = AdaptiveLoginOrderService.getInstance();
        AdaptiveLoginOrderService.ModulePlan plan = service.snapshotModulePlan();
        sender.sendMessage(message("command.test.attack-status",
                "%MODE%", service.isDeferringToSonar() ? message("command.test.sonar-first") : message("command.test.antivpn-first"),
                "%BEFORE%", plan.getBeforePlatformModules().toString(),
                "%AFTER%", plan.getAfterPlatformModules().toString()));
        return true;
    }

    private boolean testConnection(CommandSender sender, String input, String username) {
        Optional<String> parsed = IpAddressUtil.toHostAddress(input);
        if (!parsed.isPresent()) {
            sendInvalidArgumentMessage(sender);
            return true;
        }
        if (!testRunning.compareAndSet(false, true)) {
            sender.sendMessage(message("command.test.busy"));
            return true;
        }
        CompletableFuture.runAsync(() -> {
            try {
                String ip = parsed.get();
                if (ConnectionGuard.isRuntimeWhitelistedIp(ip)) {
                    sender.sendMessage(message("command.test.connection-result",
                            "%IP%", ip, "%RESULT%", message("command.test.allowed"),
                            "%REASON%", message("command.test.reason-runtime-whitelist")));
                    return;
                }
                if (username != null && ConnectionGuard.getBlockedUsernamePart(username).isPresent()) {
                    sender.sendMessage(message("command.test.connection-result",
                            "%IP%", ip, "%RESULT%", message("command.test.blocked"),
                            "%REASON%", message("command.test.reason-username")));
                    return;
                }
                VpnResult vpn = ConnectionGuard.getVpnResult(ip).join();
                Optional<GeoResult> geo = ConnectionGuard.getGeoResult(ip).join();
                boolean vpnAsnExempt = ConnectionGuard.isVpnAsnWhitelisted(ip, vpn, geo).join();
                String reason = vpnAsnExempt
                        ? message("command.test.reason-vpn-asn-whitelist")
                        : message("command.test.reason-none");
                boolean blocked = vpn.isVpn() && !vpnAsnExempt;
                if (blocked) {
                    reason = message("command.test.reason-vpn");
                } else if (geo.isPresent()) {
                    Optional<IspBlockResult> isp = ConnectionGuard.getIspBlockResult(geo.get());
                    if (isp.isPresent()) {
                        blocked = true;
                        reason = message("command.test.reason-isp");
                    } else if (isGeoBlocked(geo.get())) {
                        blocked = true;
                        reason = message("command.test.reason-geo");
                    }
                }
                sender.sendMessage(message("command.test.connection-result",
                        "%IP%", ip,
                        "%RESULT%", blocked ? message("command.test.blocked") : message("command.test.allowed"),
                        "%REASON%", reason));
            } catch (Throwable throwable) {
                ConnectionGuard.reportError("Spigot connection test command", throwable);
                sender.sendMessage(message("command.test.failed"));
            } finally {
                testRunning.set(false);
            }
        });
        return true;
    }

    private boolean isGeoBlocked(GeoResult geo) {
        String type = ConnectionGuardSpigotPlugin.getInstance().getConfig().getString("behavior.geo.type", "BLACKLIST");
        List<String> list = ConnectionGuardSpigotPlugin.getInstance().getConfig().getStringList("behavior.geo.list");
        return type.equalsIgnoreCase("WHITELIST")
                ? !list.contains(geo.getCountryName())
                : list.contains(geo.getCountryName());
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
            if (vpnResult.isVpn()
                    && !ConnectionGuard.isVpnAsnWhitelisted(
                            ipAddress,
                            vpnResult,
                            geoResultOptional
                    ).join()) {
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

    private boolean whitelistIp(CommandSender commandSender, String input) {
        Optional<String> ipAddress = IpAddressUtil.toHostAddress(input);
        if (!ipAddress.isPresent()) {
            commandSender.sendMessage(message("command.whitelist.invalid-ip"));
            return true;
        }

        String ip = ipAddress.get();
        String messagePath = ConnectionGuard.addRuntimeWhitelistedIp(ip)
                ? "command.whitelist.added"
                : "command.whitelist.already-added";
        commandSender.sendMessage(message(messagePath, "%IP%", ip));
        return true;
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
            if (commandSender.hasPermission("twiantivpn.command.test"))
                proposals.add("test");
            if (commandSender.hasPermission("twiantivpn.command.whitelist"))
                proposals.add("whitelist");
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
            if (strings[0].equalsIgnoreCase("test")) {
                proposals.add("attack");
                proposals.add("1.1.1.1");
            }
            if (strings[0].equalsIgnoreCase("whitelist")) {
                proposals.add("1.1.1.1");
            }
        }
        if (strings.length == 3 && strings[0].equalsIgnoreCase("test")
                && strings[1].equalsIgnoreCase("attack")) {
            proposals.add("on");
            proposals.add("off");
        }
        return proposals;
    }
}
