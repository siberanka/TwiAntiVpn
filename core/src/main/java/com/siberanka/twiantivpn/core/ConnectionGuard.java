package com.siberanka.twiantivpn.core;

import com.siberanka.twiantivpn.core.cache.CacheProvider;
import com.siberanka.twiantivpn.core.blocklist.ProxyBlocklistService;
import com.siberanka.twiantivpn.core.filter.UsernameFilterService;
import com.siberanka.twiantivpn.core.geo.GeoProvider;
import com.siberanka.twiantivpn.core.geo.GeoResult;
import com.siberanka.twiantivpn.core.isp.IspBlockResult;
import com.siberanka.twiantivpn.core.isp.IspBlockService;
import com.siberanka.twiantivpn.core.logging.ErrorReporter;
import com.siberanka.twiantivpn.core.security.ActionRateLimiter;
import com.siberanka.twiantivpn.core.vpn.VpnProvider;
import com.siberanka.twiantivpn.core.vpn.VpnResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class ConnectionGuard {
    private static volatile int requiredPositiveFlags = 1;
    private static volatile Map<String, VpnProvider> vpnProviders = Collections.emptyMap();
    private static volatile List<GeoProvider> geoProviders = Collections.emptyList();
    private static volatile GeoProvider geoProvider;
    private static volatile CacheProvider cacheProvider;
    private static volatile Logger logger;
    private static volatile int vpnCacheExpirationTime = 1440;
    private static volatile int geoCacheExpirationTime = 1440;
    private static final ProxyBlocklistService proxyBlocklistService = new ProxyBlocklistService();
    private static final IspBlockService ispBlockService = new IspBlockService();
    private static final UsernameFilterService usernameFilterService = new UsernameFilterService();
    private static final ActionRateLimiter actionRateLimiter = new ActionRateLimiter();
    private static final ErrorReporter errorReporter = new ErrorReporter();
    private static final ConcurrentMap<String, CompletableFuture<VpnResult>> inFlightVpnChecks =
            new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, CompletableFuture<Optional<VpnResult>>> inFlightProviderChecks =
            new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, ProviderCacheEntry> recentProviderResults =
            new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, CompletableFuture<Optional<GeoResult>>> inFlightGeoChecks =
            new ConcurrentHashMap<>();
    private static final int MAX_RECENT_PROVIDER_RESULTS = 50000;
    private static final int MAX_IN_FLIGHT_VPN_CHECKS = 2048;
    private static final int MAX_IN_FLIGHT_PROVIDER_CHECKS = 4096;
    private static final int MAX_IN_FLIGHT_GEO_CHECKS = 2048;
    private static final long PROVIDER_RESULT_TTL_MILLIS = 60000L;

    public static CompletableFuture<VpnResult> getVpnResult(String ipAddress) {
        return getVpnResult(ipAddress, true, vpnProviders.keySet());
    }

    public static CompletableFuture<VpnResult> getVpnResult(
            String ipAddress,
            boolean includeProxyBlocklist,
            Set<String> selectedProviderNames
    ) {
        Map<String, VpnProvider> providersSnapshot = vpnProviders;
        Set<String> selected = sanitizeProviderNames(selectedProviderNames, providersSnapshot);
        if (includeProxyBlocklist && proxyBlocklistService.contains(ipAddress)) {
            return CompletableFuture.completedFuture(
                    new VpnResult(ipAddress, true, Optional.of("TwiAntiVpn proxy blocklist"))
            );
        }
        if (selected.isEmpty()) {
            return CompletableFuture.completedFuture(new VpnResult(ipAddress, false));
        }
        boolean fullProviderSelection = selected.size() == providersSnapshot.size()
                && selected.containsAll(providersSnapshot.keySet());
        String key = buildInFlightKey(ipAddress, selected);

        CompletableFuture<VpnResult> existing = inFlightVpnChecks.get(key);
        if (existing != null) {
            return existing;
        }

        if (inFlightVpnChecks.size() >= MAX_IN_FLIGHT_VPN_CHECKS) {
            return CompletableFuture.completedFuture(overloadVpnResult(ipAddress));
        }

        CompletableFuture<VpnResult> created = new CompletableFuture<>();
        CompletableFuture<VpnResult> raced = inFlightVpnChecks.putIfAbsent(key, created);
        if (raced != null) {
            return raced;
        }
        try {
            computeVpnResult(
                    ipAddress,
                    selected,
                    providersSnapshot,
                    fullProviderSelection
            ).whenComplete((result, throwable) -> {
                inFlightVpnChecks.remove(key, created);
                if (throwable == null) {
                    created.complete(result);
                } else {
                    created.completeExceptionally(throwable);
                }
            });
        } catch (Throwable throwable) {
            inFlightVpnChecks.remove(key, created);
            created.completeExceptionally(throwable);
        }
        return created;
    }

    private static VpnResult overloadVpnResult(String ipAddress) {
        return new VpnResult(
                ipAddress,
                true,
                Optional.of("TwiAntiVpn capacity protection")
        );
    }

    private static CompletableFuture<VpnResult> computeVpnResult(
            String ipAddress,
            Set<String> selectedProviderNames,
            Map<String, VpnProvider> providersSnapshot,
            boolean useAggregateCache
    ) {
        return CompletableFuture.supplyAsync(() -> {
            Optional<VpnResult> vpnResultOptional = Optional.empty();
            Optional<String> vpnProviderName = Optional.empty();

            if (useAggregateCache && cacheProvider != null) {
                vpnResultOptional = cacheProvider.getVpnResult(ipAddress).join();
            }
            if (vpnResultOptional.isPresent()) {
                return vpnResultOptional.get();
            }

            int vpnPositives = 0;
            int successfulProviders = 0;
            ArrayList<CompletableFuture<Optional<VpnResult>>> vpnResultList = new ArrayList<>();

            for (String providerName : selectedProviderNames) {
                VpnProvider vpnProvider = providersSnapshot.get(providerName);
                if (vpnProvider != null) {
                    vpnResultList.add(queryProvider(providerName, vpnProvider, ipAddress));
                }
            }

            if (!vpnResultList.isEmpty()) {
                try {
                    CompletableFuture.allOf(vpnResultList.toArray(new CompletableFuture[0])).join();
                } catch (Exception exception) {
                    reportError("VPN provider batch check", exception);
                }
            }

            for (CompletableFuture<Optional<VpnResult>> vpnResultCompleted : vpnResultList) {
                try {
                    Optional<VpnResult> providerResult = vpnResultCompleted.join();
                    if (providerResult.isPresent()) {
                        successfulProviders++;
                        if (providerResult.get().getVpnProviderName().isPresent()) {
                            vpnProviderName = providerResult.get().getVpnProviderName();
                        }
                        if (providerResult.get().isVpn())
                            vpnPositives++;
                    }
                } catch (Exception exception) {
                    reportError("VPN provider check", exception);
                }
            }

            VpnResult computedVpnResult = new VpnResult(ipAddress, false, vpnProviderName);

            computedVpnResult.setVpn(vpnPositives >= requiredPositiveFlags);

            if (useAggregateCache && cacheProvider != null && successfulProviders > 0) {
                cacheProvider.addVpnResult(computedVpnResult).join();
            }
            return computedVpnResult;
        });
    }

    private static Set<String> sanitizeProviderNames(
            Set<String> selectedProviderNames,
            Map<String, VpnProvider> providersSnapshot
    ) {
        if (selectedProviderNames == null || selectedProviderNames.isEmpty()) {
            return Collections.emptySet();
        }
        TreeSet<String> selected = new TreeSet<>();
        for (String name : selectedProviderNames) {
            if (name == null) {
                continue;
            }
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            if (providersSnapshot.containsKey(normalized)) {
                selected.add(normalized);
            }
        }
        return Collections.unmodifiableSet(selected);
    }

    private static CompletableFuture<Optional<VpnResult>> queryProvider(
            String providerName,
            VpnProvider provider,
            String ipAddress
    ) {
        String key = providerName + "|" + (ipAddress == null ? "" : ipAddress);
        long now = System.currentTimeMillis();
        ProviderCacheEntry cached = recentProviderResults.get(key);
        if (cached != null) {
            if (cached.expiresAtMillis > now) {
                return CompletableFuture.completedFuture(cached.result);
            }
            recentProviderResults.remove(key, cached);
        }

        CompletableFuture<Optional<VpnResult>> existing = inFlightProviderChecks.get(key);
        if (existing != null) {
            return existing;
        }

        if (inFlightProviderChecks.size() >= MAX_IN_FLIGHT_PROVIDER_CHECKS) {
            return CompletableFuture.completedFuture(
                    Optional.of(overloadVpnResult(ipAddress))
            );
        }

        CompletableFuture<Optional<VpnResult>> created = new CompletableFuture<>();
        CompletableFuture<Optional<VpnResult>> raced =
                inFlightProviderChecks.putIfAbsent(key, created);
        if (raced != null) {
            return raced;
        }
        try {
            provider.getVpnResult(ipAddress).whenComplete((result, throwable) -> {
                inFlightProviderChecks.remove(key, created);
                if (throwable == null) {
                    if (result != null && result.isPresent()
                            && recentProviderResults.size() < MAX_RECENT_PROVIDER_RESULTS) {
                        recentProviderResults.put(
                                key,
                                new ProviderCacheEntry(
                                        result,
                                        System.currentTimeMillis() + PROVIDER_RESULT_TTL_MILLIS
                                )
                        );
                    }
                    created.complete(result == null ? Optional.empty() : result);
                } else {
                    created.completeExceptionally(throwable);
                }
            });
        } catch (Throwable throwable) {
            inFlightProviderChecks.remove(key, created);
            created.completeExceptionally(throwable);
        }
        return created;
    }

    private static String buildInFlightKey(
            String ipAddress,
            Set<String> selectedProviderNames
    ) {
        String safeIpAddress = ipAddress == null ? "" : ipAddress;
        return safeIpAddress + "|" + String.join(",", selectedProviderNames);
    }

    public static CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress) {
        String key = ipAddress == null ? "" : ipAddress;
        CompletableFuture<Optional<GeoResult>> existing = inFlightGeoChecks.get(key);
        if (existing != null) {
            return existing;
        }
        if (inFlightGeoChecks.size() >= MAX_IN_FLIGHT_GEO_CHECKS) {
            return CompletableFuture.completedFuture(Optional.empty());
        }

        CompletableFuture<Optional<GeoResult>> created = new CompletableFuture<>();
        CompletableFuture<Optional<GeoResult>> raced = inFlightGeoChecks.putIfAbsent(key, created);
        if (raced != null) {
            return raced;
        }
        try {
            computeGeoResult(ipAddress).whenComplete((result, throwable) -> {
                inFlightGeoChecks.remove(key, created);
                if (throwable == null) {
                    created.complete(result == null ? Optional.empty() : result);
                } else {
                    created.completeExceptionally(throwable);
                }
            });
        } catch (Throwable throwable) {
            inFlightGeoChecks.remove(key, created);
            created.completeExceptionally(throwable);
        }
        return created;
    }

    private static CompletableFuture<Optional<GeoResult>> computeGeoResult(String ipAddress) {
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
                    reportError("Geo cache write", exception);
                }
            }

            return geoResultOptional;
        });
    }

    private static Optional<GeoResult> queryGeoProviders(String ipAddress) {
        List<GeoProvider> providers = !geoProviders.isEmpty()
                ? geoProviders
                : new ArrayList<>();
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
                reportError("Geo provider check", exception);
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
        int providerCount = Math.max(1, vpnProviders.size());
        ConnectionGuard.requiredPositiveFlags = Math.max(
                1,
                Math.min(requiredPositiveFlags, providerCount)
        );
    }

    public static void setVpnProviders(ArrayList<VpnProvider> vpnProviders) {
        LinkedHashMap<String, VpnProvider> namedProviders = new LinkedHashMap<>();
        if (vpnProviders != null) {
            int index = 0;
            for (VpnProvider provider : vpnProviders) {
                if (provider != null) {
                    namedProviders.put("provider-" + index++, provider);
                }
            }
        }
        setVpnProviders(namedProviders);
    }

    public static void setVpnProviders(Map<String, VpnProvider> vpnProviders) {
        LinkedHashMap<String, VpnProvider> sanitized = new LinkedHashMap<>();
        if (vpnProviders != null) {
            for (Map.Entry<String, VpnProvider> entry : vpnProviders.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String name = entry.getKey().trim().toLowerCase(Locale.ROOT);
                if (!name.isEmpty() && name.length() <= 64) {
                    sanitized.put(name, entry.getValue());
                }
            }
        }
        ConnectionGuard.vpnProviders = Collections.unmodifiableMap(sanitized);
        inFlightVpnChecks.clear();
        inFlightProviderChecks.clear();
        recentProviderResults.clear();
    }

    public static void setGeoProvider(GeoProvider geoProvider) {
        ConnectionGuard.geoProvider = geoProvider;
        ConnectionGuard.geoProviders = Collections.emptyList();
        if (geoProvider != null) {
            ConnectionGuard.geoProviders = Collections.singletonList(geoProvider);
        }
    }

    public static void setGeoProviders(ArrayList<GeoProvider> geoProviders) {
        ConnectionGuard.geoProviders = geoProviders == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(geoProviders));
        ConnectionGuard.geoProvider = ConnectionGuard.geoProviders.isEmpty() ? null : ConnectionGuard.geoProviders.get(0);
        inFlightGeoChecks.clear();
    }

    public static void setCacheProvider(CacheProvider cacheProvider) {
        ConnectionGuard.cacheProvider = cacheProvider;
    }

    public static boolean initializeCacheProvider() {
        CacheProvider provider = cacheProvider;
        if (provider == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(provider.setup().get(15, TimeUnit.SECONDS));
        } catch (Exception exception) {
            reportError("Cache initialization", exception);
            return false;
        }
    }

    public static void shutdownCacheProvider() {
        CacheProvider provider = cacheProvider;
        cacheProvider = null;
        if (provider == null) {
            return;
        }
        try {
            provider.disband().get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            reportError("Cache shutdown", exception);
        }
    }

    public static void setLogger(Logger logger) {
        ConnectionGuard.logger = logger;
    }

    public static void setVpnCacheExpirationTime(int vpnCacheExpirationTime) {
        ConnectionGuard.vpnCacheExpirationTime = boundedCacheExpiration(vpnCacheExpirationTime);
    }

    public static void setGeoCacheExpirationTime(int geoCacheExpirationTime) {
        ConnectionGuard.geoCacheExpirationTime = boundedCacheExpiration(geoCacheExpirationTime);
    }

    private static int boundedCacheExpiration(int minutes) {
        return Math.max(1, Math.min(minutes, 525600));
    }

    public static void configureProxyBlocklist(
            boolean enabled,
            List<String> urls,
            int refreshIntervalMinutes,
            int maxEntries,
            int maxLineLength,
            int timeoutSeconds,
            int sourceDelayMillis
    ) {
        proxyBlocklistService.configure(
                enabled,
                urls == null ? Collections.emptyList() : urls,
                refreshIntervalMinutes,
                maxEntries,
                maxLineLength,
                timeoutSeconds,
                sourceDelayMillis
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

    public static void configureActionRateLimit(int cooldownSeconds) {
        actionRateLimiter.configure(cooldownSeconds);
    }

    public static void configureErrorReporting(boolean enabled,
                                               Path dataDirectory,
                                               int maxSizeKb,
                                               int consoleCooldownSeconds) {
        errorReporter.configure(enabled, dataDirectory, maxSizeKb, consoleCooldownSeconds, logger);
    }

    public static void reportError(String context, Throwable throwable) {
        errorReporter.report(context, throwable);
    }

    public static boolean shouldEmitActions(String actionType, String ipAddress) {
        return actionRateLimiter.tryAcquire(actionType, ipAddress);
    }

    public static int getRequiredPositiveFlags() {
        return requiredPositiveFlags;
    }

    public static ArrayList<VpnProvider> getVpnProviders() {
        return new ArrayList<>(vpnProviders.values());
    }

    public static Set<String> getVpnProviderNames() {
        return vpnProviders.keySet();
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

    private static final class ProviderCacheEntry {
        private final Optional<VpnResult> result;
        private final long expiresAtMillis;

        private ProviderCacheEntry(Optional<VpnResult> result, long expiresAtMillis) {
            this.result = result;
            this.expiresAtMillis = expiresAtMillis;
        }
    }
}
