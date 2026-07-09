package com.siberanka.twiantivpn.bungee.commands;

import com.siberanka.twiantivpn.bungee.ConnectionGuardBungeePlugin;
import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.geo.GeoResult;
import com.siberanka.twiantivpn.core.integration.AdaptiveLoginOrderService;
import com.siberanka.twiantivpn.core.isp.IspBlockResult;
import com.siberanka.twiantivpn.core.message.MessageFormatter;
import com.siberanka.twiantivpn.core.net.IpAddressUtil;
import com.siberanka.twiantivpn.core.vpn.VpnResult;
import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConnectionGuardBungeeCommand extends Command implements TabExecutor {
    private final AtomicBoolean testRunning = new AtomicBoolean();
    public ConnectionGuardBungeeCommand() {
        super("twiantivpn", "twiantivpn.command", "twiavpn", "tavpn", "antivpn");
    }

    @Override
    public void execute(CommandSender commandSender, String[] args) {
        String noPermissionMessage = message("command.no-permission");

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
                case "test":
                    if (!commandSender.hasPermission("twiantivpn.command.test")) {
                        commandSender.sendMessage(noPermissionMessage);
                        return;
                    }
                    if (args[1].equalsIgnoreCase("attack")) {
                        sendAttackStatus(commandSender);
                    } else {
                        testConnection(commandSender, args[1], null);
                    }
                    return;
                default:
                    sendUnknownSubcommandMessage(commandSender);
                    return;
            }
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("test")
                && args[1].equalsIgnoreCase("attack")
                && commandSender.hasPermission("twiantivpn.command.test")) {
            if (args[2].equalsIgnoreCase("on")) {
                AdaptiveLoginOrderService.getInstance().onSonarAttackDetected();
            } else if (args[2].equalsIgnoreCase("off")) {
                AdaptiveLoginOrderService.getInstance().onSonarAttackMitigated();
            } else {
                sendUnknownSubcommandMessage(commandSender);
                return;
            }
            sendAttackStatus(commandSender);
            return;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("test")
                && commandSender.hasPermission("twiantivpn.command.test")) {
            testConnection(commandSender, args[1], args[2]);
            return;
        }
        sendUnknownSubcommandMessage(commandSender);
    }

    private void sendAttackStatus(CommandSender sender) {
        AdaptiveLoginOrderService service = AdaptiveLoginOrderService.getInstance();
        AdaptiveLoginOrderService.ModulePlan plan = service.snapshotModulePlan();
        sender.sendMessage(message("command.test.attack-status",
                "%MODE%", service.isDeferringToSonar() ? message("command.test.sonar-first") : message("command.test.antivpn-first"),
                "%BEFORE%", plan.getBeforePlatformModules().toString(),
                "%AFTER%", plan.getAfterPlatformModules().toString()));
    }

    private void testConnection(CommandSender sender, String input, String username) {
        Optional<String> parsed = IpAddressUtil.toHostAddress(input);
        if (!parsed.isPresent()) {
            sendInvalidArgumentMessage(sender);
            return;
        }
        if (!testRunning.compareAndSet(false, true)) {
            sender.sendMessage(message("command.test.busy"));
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                String ip = parsed.get();
                if (username != null && ConnectionGuard.getBlockedUsernamePart(username).isPresent()) {
                    sender.sendMessage(message("command.test.connection-result",
                            "%IP%", ip, "%RESULT%", message("command.test.blocked"),
                            "%REASON%", message("command.test.reason-username")));
                    return;
                }
                VpnResult vpn = ConnectionGuard.getVpnResult(ip).join();
                Optional<GeoResult> geo = ConnectionGuard.getGeoResult(ip).join();
                String reason = message("command.test.reason-none");
                boolean blocked = vpn.isVpn();
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
                ConnectionGuard.reportError("Bungee connection test command", throwable);
                sender.sendMessage(message("command.test.failed"));
            } finally {
                testRunning.set(false);
            }
        });
    }

    private boolean isGeoBlocked(GeoResult geo) {
        String type = ConnectionGuardBungeePlugin.getInstance().getConfig().getString("behavior.geo.type", "BLACKLIST");
        List<String> list = ConnectionGuardBungeePlugin.getInstance().getConfig().getStringList("behavior.geo.list");
        return type.equalsIgnoreCase("WHITELIST")
                ? !list.contains(geo.getCountryName())
                : list.contains(geo.getCountryName());
    }

    private void sendUnknownSubcommandMessage(CommandSender commandSender) {
        commandSender.sendMessage(message("command.unknown-subcommand"));

        return;
    }

    private boolean sendInformationMessage(CommandSender commandSender, String entry) {
        CompletableFuture.runAsync(() -> {
            String ipAddress;
            String queriedInput;

            if (ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayer(entry) != null) {
                ProxiedPlayer player = ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayer(entry);
                ipAddress = player.getAddress().getAddress().getHostAddress();
                queriedInput = player.getName();
            } else {
                try {
                    ProxiedPlayer player = ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayer(UUID.fromString(entry));
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

            for (String line : ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getStringList("messages.info.text")) {
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

            if (ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayer(entry) != null) {
                ProxiedPlayer player = ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayer(entry);
                ipAddress = player.getAddress().getAddress().getHostAddress();
                queriedInput = player.getName();
            } else {
                try {
                    ProxiedPlayer player = ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayer(UUID.fromString(entry));
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
        for (String line : ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getStringList("messages.help")) {
            commandSender.sendMessage(format(line));
        }

        return true;
    }

    private boolean reloadPlugin(CommandSender commandSender) {
        ConnectionGuardBungeePlugin.getInstance().reloadAllConfigs();
        commandSender.sendMessage(message("command.config-reload"));
        return true;
    }

    private String message(String path, String... placeholders) {
        return format(ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString(path), placeholders);
    }

    private String format(String raw, String... placeholders) {
        return MessageFormatter.toLegacyText(
                raw,
                MessageFormatter.placeholdersWithPrefix(prefix(), placeholders)
        );
    }

    private String prefix() {
        return ConnectionGuardBungeePlugin.getInstance().getLanguageConfig().getString("messages.prefix", "&bTwiAntiVpn &7|");
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender commandSender, String[] strings) {
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
        }
        if (strings.length == 2) {
            if (strings[0].equalsIgnoreCase("info")) {
                proposals.add("1.1.1.1");
                for (ProxiedPlayer player : ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayers()) {
                    proposals.add(player.getName());
                }
            }
            if (strings[0].equalsIgnoreCase("clear")) {
                proposals.add("1.1.1.1");
                for (ProxiedPlayer player : ConnectionGuardBungeePlugin.getInstance().getProxy().getPlayers()) {
                    proposals.add(player.getName());
                }
            }
            if (strings[0].equalsIgnoreCase("test")) {
                proposals.add("attack");
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
