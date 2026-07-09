package com.siberanka.twiantivpn.velocity;

import net.byteflux.libby.Library;
import net.byteflux.libby.VelocityLibraryManager;
import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.cache.NoCacheProvider;
import com.siberanka.twiantivpn.core.cache.RedisCacheProvider;
import com.siberanka.twiantivpn.core.cache.SQLiteCacheProvider;
import com.siberanka.twiantivpn.core.geo.GeoProvider;
import com.siberanka.twiantivpn.core.geo.IpApiGeoProvider;
import com.siberanka.twiantivpn.core.geo.ProxyCheckGeoProvider;
import com.siberanka.twiantivpn.core.integration.SonarApiEarlyCheckHook;
import com.siberanka.twiantivpn.core.integration.AdaptiveLoginOrderService;
import com.siberanka.twiantivpn.core.message.MessageFormatter;
import com.siberanka.twiantivpn.core.vpn.*;
import com.siberanka.twiantivpn.core.vpn.custom.CustomVpnProvider;
import com.siberanka.twiantivpn.velocity.commands.ConnectionGuardVelocityCommand;
import com.siberanka.twiantivpn.velocity.listener.ConnectionGuardVelocityListener;
import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;

@Plugin(
        id="twiantivpn",
        name="TwiAntiVpn",
        version="2026.07.09.17",
        url="https://github.com/siberanka",
        authors = {"gerolndnr", "siberanka"},
        dependencies = {
                @Dependency(id = "sonar", optional = true)
        }
)
public class ConnectionGuardVelocityPlugin {
    private static final String OKHTTP_VERSION = "4.12.0";
    private static final String OKIO_VERSION = "3.6.0";
    private static final String KOTLIN_VERSION = "1.9.10";
    private static final String GSON_VERSION = "2.11.0";
    private static final String BSTATS_VERSION = "3.0.2";
    private static final String SQLITE_VERSION = "3.46.0.0";
    private static final String JEDIS_VERSION = "5.0.0";
    private static final String SLF4J_VERSION = "1.7.36";
    private static final String COMMONS_POOL_VERSION = "2.11.1";
    private static final String JSON_VERSION = "20230618";

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    // Config has to be in an external class, because the YAML library is loaded at runtime.
    private CGVelocityConfig cgVelocityConfig;
    private static ConnectionGuardVelocityPlugin connectionGuardVelocityPlugin;
    private HashMap<String, VpnProvider> vpnProviderMap;

    @Inject
    public ConnectionGuardVelocityPlugin(ProxyServer proxyServer, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.vpnProviderMap = new HashMap<>();

        connectionGuardVelocityPlugin = this;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent initializeEvent) {
        // 1. Set logger
        ConnectionGuard.setLogger(java.util.logging.Logger.getLogger(logger.getName()));

        // 2. Download libraries used for vpn and geo checks and config
        VelocityLibraryManager<ConnectionGuardVelocityPlugin> libraryManager = new VelocityLibraryManager<>(logger, dataDirectory, proxyServer.getPluginManager(), this);
        Library boostedYamlLibrary = Library.builder()
                .groupId("dev.dejvokep")
                .artifactId("boosted-yaml")
                .version("1.3.6")
                .relocate("dev.defvokep.boostedyaml", "com.siberanka.twiantivpn.libs.dev.defvokep.boostedyaml")
                .build();
        Library httpLibrary = Library.builder()
                .groupId("com.squareup.okhttp3")
                .artifactId("okhttp")
                .version(OKHTTP_VERSION)
                .build();
        Library gsonLibrary = Library.builder()
                .groupId("com.google.code.gson")
                .artifactId("gson")
                .version(GSON_VERSION)
                .relocate("com{}google{}gson", "com{}siberanka{}twiantivpn{}libs{}com{}google{}gson")
                .build();
        Library bstatsLibrary = Library.builder()
                // Weird replaceAll is necessary, because the gradle shadow relocate method will
                // rewrite org.bstats to com.siberanka.twiantivpn.libs.org.bstats
                // here, but not for libraries like gson.
                .groupId("org#bstats".replaceAll("#", "."))
                .artifactId("bstats-velocity")
                .version(BSTATS_VERSION)
                .relocate("org{}bstats", "com{}siberanka{}twiantivpn{}libs{}org{}bstats")
                .build();

        libraryManager.addMavenCentral();
        libraryManager.loadLibrary(boostedYamlLibrary);
        loadHttpRuntimeLibraries(libraryManager);
        libraryManager.loadLibrary(httpLibrary);
        libraryManager.loadLibrary(gsonLibrary);
        loadBStatsRuntimeLibraries(libraryManager);
        libraryManager.loadLibrary(bstatsLibrary);

        // 3. Create and load configs
        cgVelocityConfig = new CGVelocityConfig(dataDirectory);
        cgVelocityConfig.load();

        // 4. Register specified cache provider
        switch (cgVelocityConfig.getConfig().getString("provider.cache.type").toLowerCase()) {
            case "sqlite":
                Library sqliteLibrary = Library.builder()
                        .groupId("org.xerial")
                        .artifactId("sqlite-jdbc")
                        .version(SQLITE_VERSION)
                        .build();
                loadSQLiteRuntimeLibraries(libraryManager);
                libraryManager.loadLibrary(sqliteLibrary);
                ConnectionGuard.setCacheProvider(new SQLiteCacheProvider(new File(dataDirectory.toFile(), "cache.db").getAbsolutePath()));
                break;
            case "redis":
                Library jedisLibrary = Library.builder()
                        .groupId("redis.clients")
                        .artifactId("jedis")
                        .version(JEDIS_VERSION)
                        .relocate("com{}google{}gson", "com{}siberanka{}twiantivpn{}libs{}com{}google{}gson")
                        .build();
                loadRedisRuntimeLibraries(libraryManager);
                libraryManager.loadLibrary(jedisLibrary);
                ConnectionGuard.setCacheProvider(
                        new RedisCacheProvider(
                                getCgVelocityConfig().getConfig().getString("provider.cache.redis.hostname"),
                                getCgVelocityConfig().getConfig().getInt("provider.cache.redis.port"),
                                getCgVelocityConfig().getConfig().getString("provider.cache.redis.username"),
                                getCgVelocityConfig().getConfig().getString("provider.cache.redis.password")
                        )
                );
                break;
            case "disabled":
                ConnectionGuard.setCacheProvider(new NoCacheProvider());
                break;
            default:
                logger.error("The specified cache provider is invalid. Please use SQLite,Redis or disable the cache.");
                return;
        }

        ConnectionGuard.getCacheProvider().setup();

        // 5. Add every enabled vpn provider and geo provider
        vpnProviderMap.put("proxycheck", new ProxyCheckVpnProvider(getCgVelocityConfig().getConfig().getString("provider.vpn.proxycheck.api-key")));
        vpnProviderMap.put("ip-api", new IpApiVpnProvider());
        vpnProviderMap.put("iphub", new IpHubVpnProvider(getCgVelocityConfig().getConfig().getString("provider.vpn.iphub.api-key")));
        vpnProviderMap.put("vpnapi", new VpnApiVpnProvider(getCgVelocityConfig().getConfig().getString("provider.vpn.vpnapi.api-key")));

        ArrayList<VpnProvider> vpnProviders = new ArrayList<>();

        for (Object keyObject : getCgVelocityConfig().getConfig().getSection("provider.vpn").getKeys()) {
            String key = keyObject.toString();
            if (getCgVelocityConfig().getConfig().getBoolean("provider.vpn." + key + ".enabled")) {
                if (vpnProviderMap.get(key) != null) {
                    vpnProviders.add(vpnProviderMap.get(key));
                } else {
                    vpnProviders.add(
                            new CustomVpnProvider(
                                    getCgVelocityConfig().getConfig().getString("provider.vpn." + key + ".request-type"),
                                    getCgVelocityConfig().getConfig().getString("provider.vpn." + key + ".request-url"),
                                    getCgVelocityConfig().getConfig().getStringList("provider.vpn." + key + ".request-header"),
                                    getCgVelocityConfig().getConfig().getString("provider.vpn." + key + ".request-body-type"),
                                    getCgVelocityConfig().getConfig().getString("provider.vpn." + key + ".request-body"),
                                    getCgVelocityConfig().getConfig().getString("provider.vpn." + key + ".response-type"),
                                    getCgVelocityConfig().getConfig().getString("provider.vpn." + key + ".response-format.is-vpn-field.field-name"),
                                    getCgVelocityConfig().getConfig().getString("provider.vpn." + key + ".response-format.is-vpn-field.field-type"),
                                    getCgVelocityConfig().getConfig().getString("provider.vpn." + key + ".response-format.is-vpn-field.string-options.is-vpn-string"),
                                    getCgVelocityConfig().getConfig().getString("provider.vpn." + key + ".response-format.vpn-provider-field.field-name")
                            )
                    );
                }
                ConnectionGuard.getLogger().info("Registered vpn detection provider '" + key + "'.");
            }
        }

        ConnectionGuard.setVpnProviders(vpnProviders);

        configureGeoProviders();

        // 6. Set required positive vpn flags and cache expiration
        ConnectionGuard.setRequiredPositiveFlags(cgVelocityConfig.getConfig().getInt("required-positive-flags"));
        ConnectionGuard.setVpnCacheExpirationTime(cgVelocityConfig.getConfig().getInt("provider.cache.expiration.vpn"));
        ConnectionGuard.setGeoCacheExpirationTime(cgVelocityConfig.getConfig().getInt("provider.cache.expiration.geo"));
        configureSecurityFilters();
        configureProxyBlocklist();
        configureSonarEarlyHook();

        // 7. Register velocity listener and commands
        proxyServer.getEventManager().register(this, new ConnectionGuardVelocityListener());

        CommandMeta commandMeta = proxyServer.getCommandManager().metaBuilder("twiantivpn")
                .aliases("twiavpn", "tavpn", "antivpn")
                .plugin(this)
                .build();
        SimpleCommand simpleCommand = new ConnectionGuardVelocityCommand();
        proxyServer.getCommandManager().register(commandMeta, simpleCommand);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent shutdownEvent) {
        SonarApiEarlyCheckHook.uninstall(ConnectionGuard.getLogger());
        ConnectionGuard.shutdownProxyBlocklist();
        if (ConnectionGuard.getCacheProvider() != null) {
            ConnectionGuard.getCacheProvider().disband();
        }
    }

    public void configureProxyBlocklist() {
        ConnectionGuard.configureProxyBlocklist(
                cgVelocityConfig.getConfig().getBoolean("proxy-blocklist.enabled"),
                cgVelocityConfig.getConfig().getStringList("proxy-blocklist.urls"),
                cgVelocityConfig.getConfig().getInt("proxy-blocklist.refresh-interval"),
                cgVelocityConfig.getConfig().getInt("proxy-blocklist.max-entries"),
                cgVelocityConfig.getConfig().getInt("proxy-blocklist.max-line-length"),
                cgVelocityConfig.getConfig().getInt("proxy-blocklist.request-timeout-seconds")
        );
    }

    public void configureSonarEarlyHook() {
        AdaptiveLoginOrderService.getInstance().configure(
                shouldRunBeforeAntiBot(),
                cgVelocityConfig.getConfig().getBoolean("login-check.adaptive-sonar.enabled", true),
                cgVelocityConfig.getConfig().getInt("login-check.adaptive-sonar.recovery-delay-seconds", 30),
                ConnectionGuard.getLogger(),
                cgVelocityConfig.getLanguageConfig().getString("messages.adaptive-sonar-attack-log", ""),
                cgVelocityConfig.getLanguageConfig().getString("messages.adaptive-sonar-recovery-log", ""),
                cgVelocityConfig.getLanguageConfig().getString("messages.adaptive-sonar-normal-log", "")
        );
        if (!shouldRunBeforeAntiBot() || !proxyServer.getPluginManager().getPlugin("sonar").isPresent()) {
            SonarApiEarlyCheckHook.uninstall(ConnectionGuard.getLogger());
            return;
        }
        SonarApiEarlyCheckHook.install(
                ConnectionGuard.getLogger(),
                (ipAddress, username) -> cgVelocityConfig.getConfig().getStringList("behavior.vpn.exemptions").contains(ipAddress)
                        || cgVelocityConfig.getConfig().getStringList("behavior.vpn.exemptions").contains(username),
                result -> {
                    String path = result.getType().equals("username") ? "messages.username-block" : "messages.vpn-block";
                    return MessageFormatter.toPlainText(
                            cgVelocityConfig.getLanguageConfig().getString(path),
                            MessageFormatter.placeholdersWithKickLayout(
                                    cgVelocityConfig.getLanguageConfig().getString("messages.prefix", "&bTwiAntiVpn &7|"),
                                    cgVelocityConfig.getLanguageConfig().getString("messages.kick-prefix", "&b&lTwiAntiVpn"),
                                    cgVelocityConfig.getLanguageConfig().getString(
                                            "messages.kick-contact",
                                            "store.example.net    discord.gg/invite"
                                    ),
                                    "%IP%", result.getIpAddress(),
                                    "%NAME%", result.getUsername(),
                                    "%MATCH%", result.getMatch()
                            )
                    );
                }
        );
    }

    private boolean shouldRunBeforeAntiBot() {
        String order = cgVelocityConfig.getConfig().getString("login-check.order", "BEFORE_ANTIBOT");
        return !order.equalsIgnoreCase("AFTER_ANTIBOT");
    }

    public void configureSecurityFilters() {
        ConnectionGuard.configureUsernameFilter(
                cgVelocityConfig.getConfig().getBoolean("username-filter.enabled"),
                getScalarStringList("username-filter.blocked-contains")
        );
        ConnectionGuard.configureIspBlocker(
                cgVelocityConfig.getConfig().getBoolean("provider.isp-block.enabled"),
                getScalarStringList("provider.isp-block.asns"),
                getScalarStringList("provider.isp-block.isp-names")
        );
    }

    public void configureGeoProviders() {
        ArrayList<GeoProvider> geoProviders = new ArrayList<>();
        java.util.List<String> services = cgVelocityConfig.getConfig().getStringList("provider.geo.services");
        if (services.isEmpty()) {
            services.add(cgVelocityConfig.getConfig().getString("provider.geo.service", "IP-API"));
        }
        for (String service : services) {
            if (service == null) {
                continue;
            }
            switch (service.toLowerCase()) {
                case "ip-api":
                    geoProviders.add(new IpApiGeoProvider());
                    break;
                case "proxycheck":
                    geoProviders.add(new ProxyCheckGeoProvider(getCgVelocityConfig().getConfig().getString("provider.vpn.proxycheck.api-key")));
                    break;
                default:
                    logger.info("The specified geo provider is invalid: " + service);
            }
        }
        ConnectionGuard.setGeoProviders(geoProviders);
    }

    private void loadHttpRuntimeLibraries(VelocityLibraryManager<ConnectionGuardVelocityPlugin> libraryManager) {
        libraryManager.loadLibrary(library("org.jetbrains", "annotations", "13.0"));
        libraryManager.loadLibrary(library("org.jetbrains.kotlin", "kotlin-stdlib-common", KOTLIN_VERSION));
        libraryManager.loadLibrary(library("org.jetbrains.kotlin", "kotlin-stdlib", KOTLIN_VERSION));
        libraryManager.loadLibrary(library("org.jetbrains.kotlin", "kotlin-stdlib-jdk7", KOTLIN_VERSION));
        libraryManager.loadLibrary(library("org.jetbrains.kotlin", "kotlin-stdlib-jdk8", KOTLIN_VERSION));
        libraryManager.loadLibrary(library("com.squareup.okio", "okio-jvm", OKIO_VERSION));
    }

    private void loadBStatsRuntimeLibraries(VelocityLibraryManager<ConnectionGuardVelocityPlugin> libraryManager) {
        libraryManager.loadLibrary(
                Library.builder()
                        .groupId("org#bstats".replaceAll("#", "."))
                        .artifactId("bstats-base")
                        .version(BSTATS_VERSION)
                        .relocate("org{}bstats", "com{}siberanka{}twiantivpn{}libs{}org{}bstats")
                        .build()
        );
    }

    private void loadSQLiteRuntimeLibraries(VelocityLibraryManager<ConnectionGuardVelocityPlugin> libraryManager) {
        libraryManager.loadLibrary(library("org.slf4j", "slf4j-api", SLF4J_VERSION));
    }

    private void loadRedisRuntimeLibraries(VelocityLibraryManager<ConnectionGuardVelocityPlugin> libraryManager) {
        libraryManager.loadLibrary(library("org.slf4j", "slf4j-api", SLF4J_VERSION));
        libraryManager.loadLibrary(library("org.apache.commons", "commons-pool2", COMMONS_POOL_VERSION));
        libraryManager.loadLibrary(library("org.json", "json", JSON_VERSION));
    }

    private Library library(String groupId, String artifactId, String version) {
        return Library.builder()
                .groupId(groupId)
                .artifactId(artifactId)
                .version(version)
                .build();
    }

    private java.util.List<String> getScalarStringList(String path) {
        java.util.List<?> values = cgVelocityConfig.getConfig().getList(path);
        if (values == null) {
            return cgVelocityConfig.getConfig().getStringList(path);
        }
        java.util.List<String> result = new java.util.ArrayList<>();
        for (Object value : values) {
            if (value == null || value instanceof Iterable || value instanceof java.util.Map) {
                continue;
            }
            String stringValue = String.valueOf(value).trim();
            if (!stringValue.isEmpty()) {
                result.add(stringValue);
            }
        }
        return result;
    }

    public Logger getLogger() {
        return logger;
    }

    public CGVelocityConfig getCgVelocityConfig() {
        return cgVelocityConfig;
    }

    public ProxyServer getProxyServer() {
        return proxyServer;
    }

    public static ConnectionGuardVelocityPlugin getInstance() {
        return connectionGuardVelocityPlugin;
    }
}
