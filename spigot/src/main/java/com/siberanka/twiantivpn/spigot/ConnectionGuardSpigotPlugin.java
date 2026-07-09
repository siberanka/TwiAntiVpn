package com.siberanka.twiantivpn.spigot;

import net.byteflux.libby.BukkitLibraryManager;
import net.byteflux.libby.Library;
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
import com.siberanka.twiantivpn.spigot.commands.ConnectionGuardSpigotCommand;
import com.siberanka.twiantivpn.spigot.listener.AsyncPlayerPreLoginListener;
import org.bstats.bukkit.Metrics;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

public class ConnectionGuardSpigotPlugin extends JavaPlugin {
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

    private static ConnectionGuardSpigotPlugin connectionGuardSpigotPlugin;
    private File languageFile;
    private YamlConfiguration languageConfig;
    private HashMap<String, VpnProvider> vpnProviderMap;

    @Override
    public void onLoad() {
        connectionGuardSpigotPlugin = this;

        vpnProviderMap = new HashMap<>();
    }

    @Override
    public void onEnable() {
        // 1. Save Default Config & set logger
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        String selectedLanguageFileName = getConfig().getString("message-language") + ".yml";
        if (!new File(getDataFolder(), "translation").exists()) {
            new File(getDataFolder(), "translation").mkdirs();
        }
        saveLanguageResource("en.yml");
        saveLanguageResource("tr.yml");
        saveLanguageResource("az.yml");
        saveLanguageResource("es.yml");
        languageFile = new File(getDataFolder().toPath().resolve("translation").toFile(), selectedLanguageFileName);
        if (!languageFile.exists()) {
            languageFile = new File(getDataFolder().toPath().resolve("translation").toFile(), "en.yml");
        }
        languageConfig = YamlConfiguration.loadConfiguration(languageFile);
        ensureLanguageConfigComplete();

        ConnectionGuard.setLogger(getLogger());

        // 2. Download libraries used for vpn and geo checks
        BukkitLibraryManager libraryManager = new BukkitLibraryManager(this);

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
                // TODO: Investigate why it does that for bStats but not for anything else.
                .groupId("org#bstats".replaceAll("#", "."))
                .artifactId("bstats-bukkit")
                .version(BSTATS_VERSION)
                .relocate("org{}bstats", "com{}siberanka{}twiantivpn{}libs{}org{}bstats")
                .build();

        libraryManager.addMavenCentral();
        loadHttpRuntimeLibraries(libraryManager);
        libraryManager.loadLibrary(httpLibrary);
        libraryManager.loadLibrary(gsonLibrary);
        loadBStatsRuntimeLibraries(libraryManager);
        libraryManager.loadLibrary(bstatsLibrary);

        // 3. Download libraries used for specified cache provider and register cache provider afterward
        switch (getConfig().getString("provider.cache.type").toLowerCase()) {
            case "sqlite":
                Library sqliteLibrary = Library.builder()
                        .groupId("org.xerial")
                        .artifactId("sqlite-jdbc")
                        .version(SQLITE_VERSION)
                        .build();
                loadSQLiteRuntimeLibraries(libraryManager);
                libraryManager.loadLibrary(sqliteLibrary);
                ConnectionGuard.setCacheProvider(new SQLiteCacheProvider(new File(getDataFolder(), "cache.db").getAbsolutePath()));
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
                                getConfig().getString("provider.cache.redis.hostname"),
                                getConfig().getInt("provider.cache.redis.port"),
                                getConfig().getString("provider.cache.redis.username"),
                                getConfig().getString("provider.cache.redis.password")
                        )
                );
                break;
            case "disabled":
                ConnectionGuard.setCacheProvider(new NoCacheProvider());
                break;
            default:
                getLogger().info("The specified cache provider is invalid. Please use SQLite,Redis or disable the cache.");
                setEnabled(false);
                return;
        }

        if (!ConnectionGuard.initializeCacheProvider()) {
            getLogger().warning("TwiAntiVpn | Cache initialization failed; using the no-cache fallback.");
            ConnectionGuard.shutdownCacheProvider();
            ConnectionGuard.setCacheProvider(new NoCacheProvider());
        }

        // 4. Add every enabled vpn provider and geo provider
        vpnProviderMap.put("proxycheck", new ProxyCheckVpnProvider(getConfig().getString("provider.vpn.proxycheck.api-key")));
        vpnProviderMap.put("ip-api", new IpApiVpnProvider());
        vpnProviderMap.put("iphub", new IpHubVpnProvider(getConfig().getString("provider.vpn.iphub.api-key")));
        vpnProviderMap.put("vpnapi", new VpnApiVpnProvider(getConfig().getString("provider.vpn.vpnapi.api-key")));

        HashMap<String, VpnProvider> vpnProviders = new HashMap<>();

        for (String key : getConfig().getConfigurationSection("provider.vpn").getKeys(false)) {
            if (getConfig().getBoolean("provider.vpn." + key + ".enabled")) {
                if (vpnProviderMap.get(key) != null) {
                    vpnProviders.put(key, vpnProviderMap.get(key));
                } else {
                    vpnProviders.put(
                            key,
                            new CustomVpnProvider(
                                    getConfig().getString("provider.vpn." + key + ".request-type"),
                                    getConfig().getString("provider.vpn." + key + ".request-url"),
                                    getConfig().getStringList("provider.vpn." + key + ".request-header"),
                                    getConfig().getString("provider.vpn." + key + ".request-body-type"),
                                    getConfig().getString("provider.vpn." + key + ".request-body"),
                                    getConfig().getString("provider.vpn." + key + ".response-type"),
                                    getConfig().getString("provider.vpn." + key + ".response-format.is-vpn-field.field-name"),
                                    getConfig().getString("provider.vpn." + key + ".response-format.is-vpn-field.field-type"),
                                    getConfig().getString("provider.vpn." + key + ".response-format.is-vpn-field.string-options.is-vpn-string"),
                                    getConfig().getString("provider.vpn." + key + ".response-format.vpn-provider-field.field-name")
                            )
                    );
                }
                ConnectionGuard.getLogger().info("Registered vpn detection provider '" + key + "'.");
            }
        }

        ConnectionGuard.setVpnProviders(vpnProviders);

        configureGeoProviders();

        // 5. Set required positive vpn flags and cache expiration
        ConnectionGuard.setRequiredPositiveFlags(getConfig().getInt("required-positive-flags"));
        ConnectionGuard.setVpnCacheExpirationTime(getConfig().getInt("provider.cache.expiration.vpn"));
        ConnectionGuard.setGeoCacheExpirationTime(getConfig().getInt("provider.cache.expiration.geo"));
        configureSecurityFilters();
        configureProxyBlocklist();
        configureSonarEarlyHook();

        // 6. Register bukkit listener
        getServer().getPluginManager().registerEvents(new AsyncPlayerPreLoginListener(), this);

        // 7. Register commands
        getCommand("twiantivpn").setExecutor(new ConnectionGuardSpigotCommand());
        getCommand("twiantivpn").setTabCompleter(new ConnectionGuardSpigotCommand());


        Metrics metrics = new Metrics(this, 22911);
    }

    @Override
    public void onDisable() {
        SonarApiEarlyCheckHook.uninstall(getLogger());
        ConnectionGuard.shutdownProxyBlocklist();
        ConnectionGuard.shutdownCacheProvider();
    }

    public YamlConfiguration getLanguageConfig() {
        return languageConfig;
    }

    public void reloadAllConfigs() {
        reloadConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        String selectedLanguageFileName = getConfig().getString("message-language") + ".yml";
        languageFile = new File(getDataFolder().toPath().resolve("translation").toFile(), selectedLanguageFileName);
        if (!languageFile.exists()) {
            languageFile = new File(getDataFolder().toPath().resolve("translation").toFile(), "en.yml");
        }
        languageConfig = YamlConfiguration.loadConfiguration(languageFile);
        ensureLanguageConfigComplete();
        configureGeoProviders();
        configureSecurityFilters();
        configureProxyBlocklist();
        configureSonarEarlyHook();
    }

    private void configureProxyBlocklist() {
        ConnectionGuard.configureProxyBlocklist(
                getConfig().getBoolean("proxy-blocklist.enabled"),
                getConfig().getStringList("proxy-blocklist.urls"),
                getConfig().getInt("proxy-blocklist.refresh-interval"),
                getConfig().getInt("proxy-blocklist.max-entries"),
                getConfig().getInt("proxy-blocklist.max-line-length"),
                getConfig().getInt("proxy-blocklist.request-timeout-seconds"),
                getConfig().getInt("proxy-blocklist.source-delay-millis", 250)
        );
    }

    private void configureSonarEarlyHook() {
        AdaptiveLoginOrderService.getInstance().configure(
                shouldRunBeforeAntiBot(),
                getConfig().getBoolean("login-check.adaptive-sonar.enabled", true),
                getConfig().getInt("login-check.adaptive-sonar.recovery-delay-seconds", 30),
                getConfig().getBoolean("login-check.adaptive-sonar.local-attack-detection.enabled", true),
                getConfig().getInt("login-check.adaptive-sonar.local-attack-detection.window-seconds", 60),
                getConfig().getInt("login-check.adaptive-sonar.local-attack-detection.block-threshold", 15),
                getLogger(),
                getLanguageConfig().getString("messages.adaptive-sonar-attack-log", ""),
                getLanguageConfig().getString("messages.adaptive-sonar-local-attack-log", ""),
                getLanguageConfig().getString("messages.adaptive-sonar-recovery-log", ""),
                getLanguageConfig().getString("messages.adaptive-sonar-normal-log", ""),
                configuredBeforeSonarModules()
        );
        if (!shouldRunBeforeAntiBot() || getServer().getPluginManager().getPlugin("Sonar") == null) {
            SonarApiEarlyCheckHook.uninstall(getLogger());
            return;
        }
        SonarApiEarlyCheckHook.install(
                getLogger(),
                (ipAddress, username) -> getConfig().getStringList("behavior.vpn.exemptions").contains(ipAddress)
                        || getConfig().getStringList("behavior.vpn.exemptions").contains(username),
                (ipAddress, username) -> getConfig().getStringList("behavior.geo.exemptions").contains(ipAddress)
                        || getConfig().getStringList("behavior.geo.exemptions").contains(username),
                this::isGeoBlocked,
                result -> {
                    String path = messagePathForResult(result.getType());
                    return MessageFormatter.toPlainText(
                            getLanguageConfig().getString(path),
                            MessageFormatter.placeholdersWithKickLayout(
                                    getLanguageConfig().getString("messages.prefix", "&bTwiAntiVpn &7|"),
                                    getLanguageConfig().getString("messages.kick-prefix", "&b&lTwiAntiVpn"),
                                    getLanguageConfig().getString(
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
        String order = getConfig().getString("login-check.order", "BEFORE_ANTIBOT");
        return !order.equalsIgnoreCase("AFTER_ANTIBOT");
    }

    private boolean isGeoBlocked(com.siberanka.twiantivpn.core.geo.GeoResult geoResult) {
        String type = getConfig().getString("behavior.geo.type", "BLACKLIST");
        boolean listed = getConfig().getStringList("behavior.geo.list")
                .contains(geoResult.getCountryName());
        return type.equalsIgnoreCase("WHITELIST") ? !listed : listed;
    }

    private String messagePathForResult(String type) {
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
        addModule(modules, CheckModule.USERNAME_FILTER, getConfig().getBoolean(base + "username-filter", false));
        addModule(modules, CheckModule.PROXY_BLOCKLIST, getConfig().getBoolean(base + "proxy-blocklist", true));
        addModule(modules, CheckModule.VPN_PROXYCHECK, getConfig().getBoolean(base + "vpn-providers.proxycheck", false));
        addModule(modules, CheckModule.VPN_IP_API, getConfig().getBoolean(base + "vpn-providers.ip-api", false));
        addModule(modules, CheckModule.VPN_IPHUB, getConfig().getBoolean(base + "vpn-providers.iphub", false));
        addModule(modules, CheckModule.VPN_VPNAPI, getConfig().getBoolean(base + "vpn-providers.vpnapi", false));
        addModule(modules, CheckModule.VPN_CUSTOM, getConfig().getBoolean(base + "vpn-providers.custom", false));
        addModule(modules, CheckModule.GEO_BLOCK, getConfig().getBoolean(base + "geo-block", false));
        addModule(modules, CheckModule.ISP_BLOCK, getConfig().getBoolean(base + "isp-block", false));
        keepVpnProviderThresholdAtomic(modules);
        return modules;
    }

    private void keepVpnProviderThresholdAtomic(EnumSet<CheckModule> modules) {
        if (ConnectionGuard.getRequiredPositiveFlags() <= 1) {
            return;
        }
        EnumSet<CheckModule> enabled = EnumSet.noneOf(CheckModule.class);
        addModule(enabled, CheckModule.VPN_PROXYCHECK, getConfig().getBoolean("provider.vpn.proxycheck.enabled"));
        addModule(enabled, CheckModule.VPN_IP_API, getConfig().getBoolean("provider.vpn.ip-api.enabled"));
        addModule(enabled, CheckModule.VPN_IPHUB, getConfig().getBoolean("provider.vpn.iphub.enabled"));
        addModule(enabled, CheckModule.VPN_VPNAPI, getConfig().getBoolean("provider.vpn.vpnapi.enabled"));
        addModule(enabled, CheckModule.VPN_CUSTOM, getConfig().getBoolean("provider.vpn.custom.enabled"));
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

    private void configureSecurityFilters() {
        ConnectionGuard.configureActionRateLimit(
                getConfig().getInt("security.action-cooldown-seconds", 5)
        );
        ConnectionGuard.configureUsernameFilter(
                getConfig().getBoolean("username-filter.enabled"),
                getScalarStringList("username-filter.blocked-contains")
        );
        ConnectionGuard.configureIspBlocker(
                getConfig().getBoolean("provider.isp-block.enabled"),
                getScalarStringList("provider.isp-block.asns"),
                getScalarStringList("provider.isp-block.isp-names")
        );
    }

    private void configureGeoProviders() {
        ArrayList<GeoProvider> geoProviders = new ArrayList<>();
        java.util.List<String> services = getConfig().getStringList("provider.geo.services");
        if (services.isEmpty()) {
            services.add(getConfig().getString("provider.geo.service", "IP-API"));
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
                    geoProviders.add(new ProxyCheckGeoProvider(getConfig().getString("provider.vpn.proxycheck.api-key")));
                    break;
                default:
                    getLogger().info("The specified geo provider is invalid: " + service);
            }
        }
        ConnectionGuard.setGeoProviders(geoProviders);
    }

    private void saveLanguageResource(String fileName) {
        File file = new File(getDataFolder().toPath().resolve("translation").toFile(), fileName);
        if (!file.exists()) {
            saveResource("translation" + File.separator + fileName, false);
        }
    }

    private List<String> getScalarStringList(String path) {
        List<?> values = getConfig().getList(path);
        if (values == null) {
            return getConfig().getStringList(path);
        }
        List<String> result = new ArrayList<>();
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

    private void loadHttpRuntimeLibraries(BukkitLibraryManager libraryManager) {
        libraryManager.loadLibrary(library("org.jetbrains", "annotations", "13.0"));
        libraryManager.loadLibrary(library("org.jetbrains.kotlin", "kotlin-stdlib-common", KOTLIN_VERSION));
        libraryManager.loadLibrary(library("org.jetbrains.kotlin", "kotlin-stdlib", KOTLIN_VERSION));
        libraryManager.loadLibrary(library("org.jetbrains.kotlin", "kotlin-stdlib-jdk7", KOTLIN_VERSION));
        libraryManager.loadLibrary(library("org.jetbrains.kotlin", "kotlin-stdlib-jdk8", KOTLIN_VERSION));
        libraryManager.loadLibrary(library("com.squareup.okio", "okio-jvm", OKIO_VERSION));
    }

    private void loadBStatsRuntimeLibraries(BukkitLibraryManager libraryManager) {
        libraryManager.loadLibrary(
                Library.builder()
                        .groupId("org#bstats".replaceAll("#", "."))
                        .artifactId("bstats-base")
                        .version(BSTATS_VERSION)
                        .relocate("org{}bstats", "com{}siberanka{}twiantivpn{}libs{}org{}bstats")
                        .build()
        );
    }

    private void loadSQLiteRuntimeLibraries(BukkitLibraryManager libraryManager) {
        libraryManager.loadLibrary(library("org.slf4j", "slf4j-api", SLF4J_VERSION));
    }

    private void loadRedisRuntimeLibraries(BukkitLibraryManager libraryManager) {
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

    private void ensureLanguageConfigComplete() {
        if (languageConfig.contains("messages.username-block")
                && languageConfig.contains("messages.isp-block")
                && languageConfig.contains("messages.prefix")
                && languageConfig.contains("messages.kick-prefix")
                && languageConfig.contains("messages.kick-contact")
                && languageConfig.contains("messages.adaptive-sonar-attack-log")
                && languageConfig.contains("messages.adaptive-sonar-recovery-log")
                && languageConfig.contains("messages.adaptive-sonar-normal-log")
                && languageConfigUsesCurrentCommandName()) {
            return;
        }
        try {
            Files.copy(languageFile.toPath(), new File(languageFile.getAbsolutePath() + ".bak." + System.currentTimeMillis()).toPath());
            String resourceName = "translation/" + languageFile.getName();
            InputStream inputStream = getResource(resourceName);
            if (inputStream == null) {
                inputStream = getResource("translation/en.yml");
            }
            if (inputStream != null) {
                try (InputStream input = inputStream) {
                    Files.copy(input, languageFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                languageConfig = YamlConfiguration.loadConfiguration(languageFile);
            }
        } catch (Exception exception) {
            getLogger().info("TwiAntiVpn | Could not update language file: " + exception.getMessage());
        }
    }

    private boolean languageConfigUsesCurrentCommandName() {
        String unknownSubcommand = languageConfig.getString("command.unknown-subcommand", "");
        if (usesLegacyCommandName(unknownSubcommand)) {
            return false;
        }
        for (String line : languageConfig.getStringList("messages.help")) {
            if (usesLegacyCommandName(line)) {
                return false;
            }
        }
        return true;
    }

    private boolean usesLegacyCommandName(String value) {
        return value != null && (value.contains("/connectionguard") || value.contains("/cg"));
    }

    public static ConnectionGuardSpigotPlugin getInstance() {
        return connectionGuardSpigotPlugin;
    }
}
