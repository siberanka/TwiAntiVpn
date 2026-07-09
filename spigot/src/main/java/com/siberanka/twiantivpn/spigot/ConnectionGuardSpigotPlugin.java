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
import java.util.HashMap;

public class ConnectionGuardSpigotPlugin extends JavaPlugin {
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
                .version("4.12.0")
                .build();
        Library gsonLibrary = Library.builder()
                .groupId("com.google.code.gson")
                .artifactId("gson")
                .version("2.11.0")
                .relocate("com{}google{}gson", "com{}siberanka{}twiantivpn{}libs{}com{}google{}gson")
                .build();
        Library bstatsLibrary = Library.builder()
                // Weird replaceAll is necessary, because the gradle shadow relocate method will
                // rewrite org.bstats to com.siberanka.twiantivpn.libs.org.bstats
                // here, but not for libraries like gson.
                // TODO: Investigate why it does that for bStats but not for anything else.
                .groupId("org#bstats".replaceAll("#", "."))
                .artifactId("bstats-bukkit")
                .version("3.0.2")
                .relocate("org{}bstats", "com{}siberanka{}twiantivpn{}libs{}org{}bstats")
                .build();

        libraryManager.addMavenCentral();
        libraryManager.loadLibrary(httpLibrary);
        libraryManager.loadLibrary(gsonLibrary);
        libraryManager.loadLibrary(bstatsLibrary);

        // 3. Download libraries used for specified cache provider and register cache provider afterward
        switch (getConfig().getString("provider.cache.type").toLowerCase()) {
            case "sqlite":
                Library sqliteLibrary = Library.builder()
                        .groupId("org.xerial")
                        .artifactId("sqlite-jdbc")
                        .version("3.46.0.0")
                        .build();
                libraryManager.loadLibrary(sqliteLibrary);
                ConnectionGuard.setCacheProvider(new SQLiteCacheProvider(new File(getDataFolder(), "cache.db").getAbsolutePath()));
                break;
            case "redis":
                Library jedisLibrary = Library.builder()
                        .groupId("redis.clients")
                        .artifactId("jedis")
                        .version("5.0.0")
                        .build();
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

        ConnectionGuard.getCacheProvider().setup();

        // 4. Add every enabled vpn provider and geo provider
        vpnProviderMap.put("proxycheck", new ProxyCheckVpnProvider(getConfig().getString("provider.vpn.proxycheck.api-key")));
        vpnProviderMap.put("ip-api", new IpApiVpnProvider());
        vpnProviderMap.put("iphub", new IpHubVpnProvider(getConfig().getString("provider.vpn.iphub.api-key")));
        vpnProviderMap.put("vpnapi", new VpnApiVpnProvider(getConfig().getString("provider.vpn.vpnapi.api-key")));

        ArrayList<VpnProvider> vpnProviders = new ArrayList<>();

        for (String key : getConfig().getConfigurationSection("provider.vpn").getKeys(false)) {
            if (getConfig().getBoolean("provider.vpn." + key + ".enabled")) {
                if (vpnProviderMap.get(key) != null) {
                    vpnProviders.add(vpnProviderMap.get(key));
                } else {
                    vpnProviders.add(
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

        // 6. Register bukkit listener
        getServer().getPluginManager().registerEvents(new AsyncPlayerPreLoginListener(), this);

        // 7. Register commands
        getCommand("twiantivpn").setExecutor(new ConnectionGuardSpigotCommand());
        getCommand("twiantivpn").setTabCompleter(new ConnectionGuardSpigotCommand());


        Metrics metrics = new Metrics(this, 22911);
    }

    @Override
    public void onDisable() {
        ConnectionGuard.shutdownProxyBlocklist();
        if (ConnectionGuard.getCacheProvider() != null) {
            ConnectionGuard.getCacheProvider().disband();
        }
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
    }

    private void configureProxyBlocklist() {
        ConnectionGuard.configureProxyBlocklist(
                getConfig().getBoolean("proxy-blocklist.enabled"),
                getConfig().getStringList("proxy-blocklist.urls"),
                getConfig().getInt("proxy-blocklist.refresh-interval"),
                getConfig().getInt("proxy-blocklist.max-entries"),
                getConfig().getInt("proxy-blocklist.max-line-length"),
                getConfig().getInt("proxy-blocklist.request-timeout-seconds")
        );
    }

    private void configureSecurityFilters() {
        ConnectionGuard.configureUsernameFilter(
                getConfig().getBoolean("username-filter.enabled"),
                getConfig().getStringList("username-filter.blocked-contains")
        );
        ConnectionGuard.configureIspBlocker(
                getConfig().getBoolean("provider.isp-block.enabled"),
                getConfig().getStringList("provider.isp-block.asns"),
                getConfig().getStringList("provider.isp-block.isp-names")
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

    private void ensureLanguageConfigComplete() {
        if (languageConfig.contains("messages.username-block")
                && languageConfig.contains("messages.isp-block")
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
