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
        version="2026.07.09.3",
        url="https://github.com/siberanka",
        authors = {"gerolndnr", "siberanka"}
)
public class ConnectionGuardVelocityPlugin {
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
                .groupId("org#bstats".replaceAll("#", "."))
                .artifactId("bstats-velocity")
                .version("3.0.2")
                .relocate("org{}bstats", "com{}siberanka{}twiantivpn{}libs{}org{}bstats")
                .build();

        libraryManager.addMavenCentral();
        libraryManager.loadLibrary(boostedYamlLibrary);
        libraryManager.loadLibrary(httpLibrary);
        libraryManager.loadLibrary(gsonLibrary);
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
                        .version("3.46.0.0")
                        .build();
                libraryManager.loadLibrary(sqliteLibrary);
                ConnectionGuard.setCacheProvider(new SQLiteCacheProvider(new File(dataDirectory.toFile(), "cache.db").getAbsolutePath()));
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

        // 7. Register velocity listener and commands
        proxyServer.getEventManager().register(this, new ConnectionGuardVelocityListener());

        CommandMeta commandMeta = proxyServer.getCommandManager().metaBuilder("connectionguard")
                .aliases("cg")
                .plugin(this)
                .build();
        SimpleCommand simpleCommand = new ConnectionGuardVelocityCommand();
        proxyServer.getCommandManager().register(commandMeta, simpleCommand);
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent shutdownEvent) {
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

    public void configureSecurityFilters() {
        ConnectionGuard.configureUsernameFilter(
                cgVelocityConfig.getConfig().getBoolean("username-filter.enabled"),
                cgVelocityConfig.getConfig().getStringList("username-filter.blocked-contains")
        );
        ConnectionGuard.configureIspBlocker(
                cgVelocityConfig.getConfig().getBoolean("provider.isp-block.enabled"),
                cgVelocityConfig.getConfig().getStringList("provider.isp-block.asns"),
                cgVelocityConfig.getConfig().getStringList("provider.isp-block.isp-names")
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
