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
import com.siberanka.twiantivpn.core.integration.CheckModule;
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
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Set;

@Plugin(
        id="twiantivpn",
        name="TwiAntiVpn",
        version="2026.10.03.1",
        url="https://gitlab.com/siberanka/TwiAntiVpn",
        authors = {"gerolndnr", "siberanka"},
        dependencies = {
                @Dependency(id = "sonar", optional = true)
        }
)
public class ConnectionGuardVelocityPlugin {
    private static final String VERSION = "2026.10.03.1";
    private static final String OKHTTP_VERSION = "4.12.0";
    private static final String OKIO_VERSION = "3.6.0";
    private static final String KOTLIN_VERSION = "1.9.10";
    private static final String GSON_VERSION = "2.11.0";
    private static final String BSTATS_VERSION = "3.0.2";
    private static final String SQLITE_VERSION = "3.46.0.0";
    private static final String JEDIS_VERSION = "5.0.0";
    private static final String SLF4J_VERSION = "1.7.36";
    private static final String COMMONS_POOL_VERSION = "2.11.1";
    private static final String JSON_VERSION = "20260522";

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
        ConnectionGuard.configureErrorReporting(true, dataDirectory, 2048, 60);

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
        if (cgVelocityConfig.getConfig() == null || cgVelocityConfig.getLanguageConfig() == null) {
            return;
        }
        configureErrorReporting();

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
                logger.warn("TwiAntiVpn | The specified cache provider is invalid. Please use SQLite, Redis or Disabled.");
                return;
        }

        if (!ConnectionGuard.initializeCacheProvider()) {
            logger.warn("TwiAntiVpn | Cache initialization failed; using the no-cache fallback.");
            ConnectionGuard.shutdownCacheProvider();
            ConnectionGuard.setCacheProvider(new NoCacheProvider());
        }

        // 5. Add every enabled vpn provider and geo provider
        vpnProviderMap.put("proxycheck", new ProxyCheckVpnProvider(getCgVelocityConfig().getConfig().getString("provider.vpn.proxycheck.api-key")));
        vpnProviderMap.put("ip-api", new IpApiVpnProvider());
        vpnProviderMap.put("iphub", new IpHubVpnProvider(getCgVelocityConfig().getConfig().getString("provider.vpn.iphub.api-key")));
        vpnProviderMap.put("vpnapi", new VpnApiVpnProvider(getCgVelocityConfig().getConfig().getString("provider.vpn.vpnapi.api-key")));

        HashMap<String, VpnProvider> vpnProviders = new HashMap<>();

        for (Object keyObject : getCgVelocityConfig().getConfig().getSection("provider.vpn").getKeys()) {
            String key = keyObject.toString();
            if (getCgVelocityConfig().getConfig().getBoolean("provider.vpn." + key + ".enabled")) {
                if (vpnProviderMap.get(key) != null) {
                    vpnProviders.put(key, vpnProviderMap.get(key));
                } else {
                    vpnProviders.put(
                            key,
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
        ConnectionGuard.checkForUpdates(
                VERSION,
                cgVelocityConfig.getConfig().getBoolean("update-check.enabled", true)
        );
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent shutdownEvent) {
        SonarApiEarlyCheckHook.uninstall(ConnectionGuard.getLogger());
        ConnectionGuard.shutdownProxyBlocklist();
        ConnectionGuard.shutdownCacheProvider();
    }

    public void configureProxyBlocklist() {
        ConnectionGuard.configureProxyBlocklist(
                cgVelocityConfig.getConfig().getBoolean("proxy-blocklist.enabled"),
                cgVelocityConfig.getConfig().getStringList("proxy-blocklist.urls"),
                cgVelocityConfig.getConfig().getInt("proxy-blocklist.refresh-interval"),
                cgVelocityConfig.getConfig().getInt("proxy-blocklist.max-entries"),
                cgVelocityConfig.getConfig().getInt("proxy-blocklist.max-line-length"),
                cgVelocityConfig.getConfig().getInt("proxy-blocklist.request-timeout-seconds"),
                cgVelocityConfig.getConfig().getInt("proxy-blocklist.source-delay-millis", 250)
        );
    }

    public void configureErrorReporting() {
        ConnectionGuard.configureErrorReporting(
                cgVelocityConfig.getConfig().getBoolean("security.error-log.enabled", true),
                dataDirectory,
                cgVelocityConfig.getConfig().getInt("security.error-log.max-size-kb", 2048),
                cgVelocityConfig.getConfig().getInt("security.error-log.console-notice-cooldown-seconds", 60)
        );
    }

    public void configureSonarEarlyHook() {
        AdaptiveLoginOrderService.getInstance().configure(
                shouldRunBeforeAntiBot(),
                cgVelocityConfig.getConfig().getBoolean("login-check.adaptive-sonar.enabled", true),
                cgVelocityConfig.getConfig().getInt("login-check.adaptive-sonar.recovery-delay-seconds", 30),
                adaptiveBoolean("pre-sonar-block-spike.enabled", "local-attack-detection.enabled", true),
                adaptiveInt(
                        "pre-sonar-block-spike.count-blocks-within-seconds",
                        "pre-sonar-block-spike.rolling-window-seconds",
                        "local-attack-detection.window-seconds",
                        60
                ),
                adaptiveInt(
                        "pre-sonar-block-spike.trigger-after-blocked-connections",
                        "pre-sonar-block-spike.blocked-connections-threshold",
                        "local-attack-detection.block-threshold",
                        15
                ),
                ConnectionGuard.getLogger(),
                cgVelocityConfig.getLanguageConfig().getString("messages.adaptive-sonar-attack-log", ""),
                cgVelocityConfig.getLanguageConfig().getString("messages.adaptive-sonar-local-attack-log", ""),
                cgVelocityConfig.getLanguageConfig().getString("messages.adaptive-sonar-recovery-log", ""),
                cgVelocityConfig.getLanguageConfig().getString("messages.adaptive-sonar-normal-log", ""),
                configuredBeforeSonarModules()
        );
        if (!shouldRunBeforeAntiBot() || !proxyServer.getPluginManager().getPlugin("sonar").isPresent()) {
            SonarApiEarlyCheckHook.uninstall(ConnectionGuard.getLogger());
            return;
        }
        SonarApiEarlyCheckHook.install(
                ConnectionGuard.getLogger(),
                cgVelocityConfig.getConfig().getInt(
                        "login-check.adaptive-sonar.pre-sonar-check-timeout-seconds",
                        6
                ),
                (ipAddress, username) -> cgVelocityConfig.getConfig().getStringList("behavior.vpn.exemptions").contains(ipAddress)
                        || cgVelocityConfig.getConfig().getStringList("behavior.vpn.exemptions").contains(username),
                (ipAddress, username) -> cgVelocityConfig.getConfig().getStringList("behavior.geo.exemptions").contains(ipAddress)
                        || cgVelocityConfig.getConfig().getStringList("behavior.geo.exemptions").contains(username),
                this::isGeoBlocked,
                result -> {
                    String path = messagePathForResult(result.getType());
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
                                    "%MATCH%", result.getMatch(),
                                    "%COUNTRY%", result.getCountry(),
                                    "%CITY%", result.getCity(),
                                    "%ISP%", result.getIsp(),
                                    "%ASN%", result.getAsn()
                            )
                    );
                }
        );
    }

    private boolean shouldRunBeforeAntiBot() {
        String order = cgVelocityConfig.getConfig().getString("login-check.order", "BEFORE_ANTIBOT");
        return !order.equalsIgnoreCase("AFTER_ANTIBOT");
    }

    private boolean adaptiveBoolean(String currentPath, String legacyPath, boolean defaultValue) {
        String base = "login-check.adaptive-sonar.";
        String current = base + currentPath;
        if (cgVelocityConfig.getConfig().get(current) != null) {
            return cgVelocityConfig.getConfig().getBoolean(current, defaultValue);
        }
        return cgVelocityConfig.getConfig().getBoolean(base + legacyPath, defaultValue);
    }

    private int adaptiveInt(String currentPath, String legacyPath, int defaultValue) {
        String base = "login-check.adaptive-sonar.";
        String current = base + currentPath;
        if (cgVelocityConfig.getConfig().get(current) != null) {
            return cgVelocityConfig.getConfig().getInt(current, defaultValue);
        }
        return cgVelocityConfig.getConfig().getInt(base + legacyPath, defaultValue);
    }

    private int adaptiveInt(String currentPath, String previousPath, String legacyPath, int defaultValue) {
        String base = "login-check.adaptive-sonar.";
        String current = base + currentPath;
        if (cgVelocityConfig.getConfig().get(current) != null) {
            return cgVelocityConfig.getConfig().getInt(current, defaultValue);
        }
        String previous = base + previousPath;
        if (cgVelocityConfig.getConfig().get(previous) != null) {
            return cgVelocityConfig.getConfig().getInt(previous, defaultValue);
        }
        return cgVelocityConfig.getConfig().getInt(base + legacyPath, defaultValue);
    }

    private boolean isGeoBlocked(com.siberanka.twiantivpn.core.geo.GeoResult geoResult) {
        String type = cgVelocityConfig.getConfig().getString("behavior.geo.type", "BLACKLIST");
        boolean listed = cgVelocityConfig.getConfig().getStringList("behavior.geo.list")
                .contains(geoResult.getCountryName());
        return type.equalsIgnoreCase("WHITELIST") ? !listed : listed;
    }

    private String messagePathForResult(String type) {
        if ("check-failed".equals(type)) {
            return "messages.pre-sonar-check-failed";
        }
        if ("username".equals(type)) {
            return "messages.username-block";
        }
        if ("geo".equals(type)) {
            return "messages.geo-block";
        }
        if ("isp".equals(type)) {
            return "messages.isp-block";
        }
        return "messages.vpn-block";
    }

    private Set<CheckModule> configuredBeforeSonarModules() {
        String base = "login-check.adaptive-sonar.before-sonar.";
        EnumSet<CheckModule> modules = EnumSet.noneOf(CheckModule.class);
        addModule(modules, CheckModule.USERNAME_FILTER, cgVelocityConfig.getConfig().getBoolean(base + "username-filter", false));
        addModule(modules, CheckModule.PROXY_BLOCKLIST, cgVelocityConfig.getConfig().getBoolean(base + "proxy-blocklist", true));
        addModule(modules, CheckModule.VPN_PROXYCHECK, cgVelocityConfig.getConfig().getBoolean(base + "vpn-providers.proxycheck", false));
        addModule(modules, CheckModule.VPN_IP_API, cgVelocityConfig.getConfig().getBoolean(base + "vpn-providers.ip-api", false));
        addModule(modules, CheckModule.VPN_IPHUB, cgVelocityConfig.getConfig().getBoolean(base + "vpn-providers.iphub", false));
        addModule(modules, CheckModule.VPN_VPNAPI, cgVelocityConfig.getConfig().getBoolean(base + "vpn-providers.vpnapi", false));
        addModule(modules, CheckModule.VPN_CUSTOM, cgVelocityConfig.getConfig().getBoolean(base + "vpn-providers.custom", false));
        addModule(modules, CheckModule.GEO_BLOCK, cgVelocityConfig.getConfig().getBoolean(base + "geo-block", false));
        addModule(modules, CheckModule.ISP_BLOCK, cgVelocityConfig.getConfig().getBoolean(base + "isp-block", false));
        keepVpnProviderThresholdAtomic(modules);
        return modules;
    }

    private void keepVpnProviderThresholdAtomic(EnumSet<CheckModule> modules) {
        if (ConnectionGuard.getRequiredPositiveFlags() <= 1) {
            return;
        }
        EnumSet<CheckModule> enabled = EnumSet.noneOf(CheckModule.class);
        addModule(enabled, CheckModule.VPN_PROXYCHECK, cgVelocityConfig.getConfig().getBoolean("provider.vpn.proxycheck.enabled"));
        addModule(enabled, CheckModule.VPN_IP_API, cgVelocityConfig.getConfig().getBoolean("provider.vpn.ip-api.enabled"));
        addModule(enabled, CheckModule.VPN_IPHUB, cgVelocityConfig.getConfig().getBoolean("provider.vpn.iphub.enabled"));
        addModule(enabled, CheckModule.VPN_VPNAPI, cgVelocityConfig.getConfig().getBoolean("provider.vpn.vpnapi.enabled"));
        addModule(enabled, CheckModule.VPN_CUSTOM, cgVelocityConfig.getConfig().getBoolean("provider.vpn.custom.enabled"));
        EnumSet<CheckModule> selected = EnumSet.copyOf(modules);
        selected.retainAll(enabled);
        if (!selected.isEmpty() && !selected.containsAll(enabled)) {
            modules.removeAll(enabled);
        }
    }

    private void addModule(Set<CheckModule> modules, CheckModule module, boolean enabled) {
        if (enabled) {
            modules.add(module);
        }
    }

    public void configureSecurityFilters() {
        ConnectionGuard.configureActionRateLimit(
                cgVelocityConfig.getConfig().getInt("security.action-cooldown-seconds", 5)
        );
        ConnectionGuard.configureUsernameFilter(
                cgVelocityConfig.getConfig().getBoolean("username-filter.enabled"),
                getScalarStringList("username-filter.blocked-contains")
        );
        ConnectionGuard.configureIspBlocker(
                cgVelocityConfig.getConfig().getBoolean("provider.isp-block.enabled"),
                getScalarStringList("provider.isp-block.asns"),
                getScalarStringList("provider.isp-block.isp-names")
        );
        ConnectionGuard.configureVpnAsnWhitelist(
                cgVelocityConfig.getConfig().getBoolean("behavior.vpn.whitelisted-asn.enabled", true),
                cgVelocityConfig.getConfig().contains("behavior.vpn.whitelisted-asn.built-in-countries")
                        ? getScalarStringList("behavior.vpn.whitelisted-asn.built-in-countries")
                        : ConnectionGuard.getDefaultTrustedIspCountries(),
                getScalarStringList("behavior.vpn.whitelisted-asn.asns"),
                getScalarStringList("behavior.vpn.whitelisted-asn.excluded-asns"),
                cgVelocityConfig.getConfig().getBoolean("behavior.vpn.whitelisted-asn.block-hosting", true),
                cgVelocityConfig.getConfig().getBoolean("behavior.vpn.whitelisted-asn.block-anonymizers", true)
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
