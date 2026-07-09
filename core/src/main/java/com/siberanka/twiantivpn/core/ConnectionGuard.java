package com.siberanka.twiantivpn.core;

import com.siberanka.twiantivpn.core.cache.CacheProvider;
import com.siberanka.twiantivpn.core.blocklist.ProxyBlocklistService;
import com.siberanka.twiantivpn.core.filter.UsernameFilterService;
import com.siberanka.twiantivpn.core.geo.GeoProvider;
import com.siberanka.twiantivpn.core.geo.GeoResult;
import com.siberanka.twiantivpn.core.isp.IspBlockResult;
import com.siberanka.twiantivpn.core.isp.IspBlockService;
import com.siberanka.twiantivpn.core.vpn.VpnProvider;
import com.siberanka.twiantivpn.core.vpn.VpnResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class ConnectionGuard {
    private static int requiredPositiveFlags = 1;
    private static ArrayList<VpnProvider> vpnProviders = new ArrayList<>();
    private static ArrayList<GeoProvider> geoProviders = new ArrayList<>();
    private static GeoProvider geoProvider;
    private static CacheProvider cacheProvider;
    private static Logger logger;
    private static int vpnCacheExpirationTime = 1440;
    private static int geoCacheExpirationTime = 1440;
    private static final ProxyBlocklistService proxyBlocklistService = new ProxyBlocklistService();
    private static final IspBlockService ispBlockService = new IspBlockService();
    private static final UsernameFilterService usernameFilterService = new UsernameFilterService();

    public static CompletableFuture<VpnResult> getVpnResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            if (proxyBlocklistService.contains(ipAddress)) {
                return new VpnResult(ipAddress, true, Optional.of("TwiAntiVpn proxy blocklist"));
            }

            if (cacheProvider == null) {
                return new VpnResult(ipAddress, false);
            }

            Optional<VpnResult> vpnResultOptional = cacheProvider.getVpnResult(ipAddress).join();
            Optional<String> vpnProviderName = Optional.empty();

            if (vpnResultOptional.isPresent())
                return vpnResultOptional.get();

            int vpnPositives = 0;
            ArrayList<CompletableFuture<Optional<VpnResult>>> vpnResultList = new ArrayList<>();

            for (VpnProvider vpnProvider : vpnProviders) {
                vpnResultList.add(vpnProvider.getVpnResult(ipAddress));
            }

            if (!vpnResultList.isEmpty()) {
                try {
                    CompletableFuture.allOf(vpnResultList.toArray(new CompletableFuture[0])).join();
                } catch (Exception exception) {
                    if (logger != null) {
                        logger.info("One or more VPN providers failed: " + exception.getMessage());
                    }
                }
            }

            for (CompletableFuture<Optional<VpnResult>> vpnResultCompleted : vpnResultList) {
                try {
                    Optional<VpnResult> providerResult = vpnResultCompleted.join();
                    if (providerResult.isPresent()) {
                        if (providerResult.get().getVpnProviderName().isPresent()) {
                            vpnProviderName = providerResult.get().getVpnProviderName();
                        }
                        if (providerResult.get().isVpn())
                            vpnPositives++;
                    }
                } catch (Exception exception) {
                    if (logger != null) {
                        logger.info("VPN provider failed: " + exception.getMessage());
                    }
                }
            }

            VpnResult computedVpnResult = new VpnResult(ipAddress, false, vpnProviderName);

            computedVpnResult.setVpn(vpnPositives >= requiredPositiveFlags);

            cacheProvider.addVpnResult(computedVpnResult).join();
            return computedVpnResult;
        });
    }

    public static CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            if (cacheProvider == null || geoProvider == null) {
                return Optional.empty();
            }

            Optional<GeoResult> geoResultOptional = cacheProvider.getGeoResult(ipAddress).join();

            if (geoResultOptional.isPresent())
                return geoResultOptional;

            geoResultOptional = queryGeoProviders(ipAddress);

            if (geoResultOptional.isPresent() && cacheProvider != null) {
                try {
                    cacheProvider.addGeoResult(geoResultOptional.get()).join();
                } catch (Exception exception) {
                    if (logger != null) {
                        logger.info("Could not cache geo result: " + exception.getMessage());
                    }
                }
            }

            return geoResultOptional;
        });
    }

    private static Optional<GeoResult> queryGeoProviders(String ipAddress) {
        ArrayList<GeoProvider> providers = !geoProviders.isEmpty() ? geoProviders : new ArrayList<>();
        if (providers.isEmpty() && geoProvider != null) {
            providers.add(geoProvider);
        }

        Optional<GeoResult> firstResult = Optional.empty();
        for (GeoProvider provider : providers) {
            try {
                Optional<GeoResult> result = provider.getGeoResult(ipAddress).join();
                if (!result.isPresent()) {
                    continue;
                }
                if (!firstResult.isPresent()) {
                    firstResult = result;
                }
                if (result.get().hasNetworkIdentity()) {
                    return result;
                }
            } catch (Exception exception) {
                if (logger != null) {
                    logger.info("Geo provider failed: " + exception.getMessage());
                }
            }
        }
        return firstResult;
    }

    public static Optional<IspBlockResult> getIspBlockResult(GeoResult geoResult) {
        return ispBlockService.match(geoResult);
    }

    public static Optional<String> getBlockedUsernamePart(String username) {
        return usernameFilterService.findMatch(username);
    }

    public static void setRequiredPositiveFlags(int requiredPositiveFlags) {
        ConnectionGuard.requiredPositiveFlags = requiredPositiveFlags;
    }

    public static void setVpnProviders(ArrayList<VpnProvider> vpnProviders) {
        ConnectionGuard.vpnProviders = vpnProviders == null ? new ArrayList<>() : vpnProviders;
    }

    public static void setGeoProvider(GeoProvider geoProvider) {
        ConnectionGuard.geoProvider = geoProvider;
        ConnectionGuard.geoProviders = new ArrayList<>();
        if (geoProvider != null) {
            ConnectionGuard.geoProviders.add(geoProvider);
        }
    }

    public static void setGeoProviders(ArrayList<GeoProvider> geoProviders) {
        ConnectionGuard.geoProviders = geoProviders == null ? new ArrayList<>() : geoProviders;
        ConnectionGuard.geoProvider = ConnectionGuard.geoProviders.isEmpty() ? null : ConnectionGuard.geoProviders.get(0);
    }

    public static void setCacheProvider(CacheProvider cacheProvider) {
        ConnectionGuard.cacheProvider = cacheProvider;
    }

    public static void setLogger(Logger logger) {
        ConnectionGuard.logger = logger;
    }

    public static void setVpnCacheExpirationTime(int vpnCacheExpirationTime) {
        ConnectionGuard.vpnCacheExpirationTime = vpnCacheExpirationTime;
    }

    public static void setGeoCacheExpirationTime(int geoCacheExpirationTime) {
        ConnectionGuard.geoCacheExpirationTime = geoCacheExpirationTime;
    }

    public static void configureProxyBlocklist(
            boolean enabled,
            List<String> urls,
            int refreshIntervalMinutes,
            int maxEntries,
            int maxLineLength,
            int timeoutSeconds
    ) {
        proxyBlocklistService.configure(
                enabled,
                urls == null ? Collections.emptyList() : urls,
                refreshIntervalMinutes,
                maxEntries,
                maxLineLength,
                timeoutSeconds
        );
        proxyBlocklistService.start();
    }

    public static void shutdownProxyBlocklist() {
        proxyBlocklistService.stop();
    }

    public static ProxyBlocklistService getProxyBlocklistService() {
        return proxyBlocklistService;
    }

    public static void configureIspBlocker(boolean enabled, List<String> asns, List<String> ispNames) {
        ispBlockService.configure(enabled, asns, ispNames);
    }

    public static void configureUsernameFilter(boolean enabled, List<String> blockedContains) {
        usernameFilterService.configure(enabled, blockedContains);
    }

    public static int getRequiredPositiveFlags() {
        return requiredPositiveFlags;
    }

    public static ArrayList<VpnProvider> getVpnProviders() {
        return vpnProviders;
    }

    public static GeoProvider getGeoProvider() {
        return geoProvider;
    }

    public static CacheProvider getCacheProvider() {
        return cacheProvider;
    }

    public static Logger getLogger() {
        return logger;
    }

    public static int getVpnCacheExpirationTime() {
        return vpnCacheExpirationTime;
    }

    public static int getGeoCacheExpirationTime() {
        return geoCacheExpirationTime;
    }
}
