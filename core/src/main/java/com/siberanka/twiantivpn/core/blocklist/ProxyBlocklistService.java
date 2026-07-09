package com.siberanka.twiantivpn.core.blocklist;

import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.net.IpAddressUtil;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ProxyBlocklistService {
    private static final int DEFAULT_INTERVAL_MINUTES = 60;
    private static final int DEFAULT_MAX_ENTRIES = 750000;
    private static final int DEFAULT_MAX_LINE_LENGTH = 512;
    private static final int DEFAULT_TIMEOUT_SECONDS = 15;
    private static final int MAX_URLS = 64;
    private static final int MAX_RETRIES = 3;
    private static final long SOURCE_DELAY_MILLIS = 1000L;

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());
    private final Object lifecycleLock = new Object();
    private volatile ScheduledExecutorService executorService;
    private volatile ScheduledFuture<?> refreshTask;
    private volatile boolean enabled;
    private volatile List<String> urls = Collections.emptyList();
    private volatile int refreshIntervalMinutes = DEFAULT_INTERVAL_MINUTES;
    private volatile int maxEntries = DEFAULT_MAX_ENTRIES;
    private volatile int maxLineLength = DEFAULT_MAX_LINE_LENGTH;
    private volatile int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

    public void configure(
            boolean enabled,
            List<String> urls,
            int refreshIntervalMinutes,
            int maxEntries,
            int maxLineLength,
            int timeoutSeconds
    ) {
        this.enabled = enabled;
        this.urls = sanitizeUrls(urls);
        this.refreshIntervalMinutes = positiveOrDefault(refreshIntervalMinutes, DEFAULT_INTERVAL_MINUTES);
        this.maxEntries = positiveOrDefault(maxEntries, DEFAULT_MAX_ENTRIES);
        this.maxLineLength = positiveOrDefault(maxLineLength, DEFAULT_MAX_LINE_LENGTH);
        this.timeoutSeconds = positiveOrDefault(timeoutSeconds, DEFAULT_TIMEOUT_SECONDS);
    }

    public void start() {
        synchronized (lifecycleLock) {
            stopLocked(false);
            if (!enabled || urls.isEmpty()) {
                snapshot.set(Snapshot.empty());
                return;
            }

            executorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "TwiAntiVpn-ProxyBlocklist");
                    thread.setDaemon(true);
                    return thread;
                }
            });
            refreshTask = executorService.scheduleWithFixedDelay(
                    new Runnable() {
                        @Override
                        public void run() {
                            refreshSafely();
                        }
                    },
                    0L,
                    refreshIntervalMinutes,
                    TimeUnit.MINUTES
            );
        }
    }

    public void stop() {
        synchronized (lifecycleLock) {
            stopLocked(true);
        }
    }

    public boolean contains(String ipAddress) {
        Optional<IpAddressUtil.Address> address = IpAddressUtil.parseLiteral(ipAddress);
        return address.isPresent() && snapshot.get().contains(address.get());
    }

    public long size() {
        return snapshot.get().size;
    }

    public long lastRefreshEpochMillis() {
        return snapshot.get().createdAtMillis;
    }

    private void stopLocked(boolean clearSnapshot) {
        if (refreshTask != null) {
            refreshTask.cancel(true);
            refreshTask = null;
        }
        if (executorService != null) {
            executorService.shutdownNow();
            executorService = null;
        }
        if (clearSnapshot) {
            snapshot.set(Snapshot.empty());
        }
    }

    private void refreshSafely() {
        try {
            refresh();
        } catch (Throwable throwable) {
            log("Unexpected refresh failure: " + throwable.getMessage());
        }
    }

    private void refresh() {
        List<String> currentUrls = urls;
        if (!enabled || currentUrls.isEmpty()) {
            snapshot.set(Snapshot.empty());
            return;
        }

        OkHttpClient httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .callTimeout(timeoutSeconds * 2L, TimeUnit.SECONDS)
                .build();

        SnapshotBuilder builder = new SnapshotBuilder(maxEntries);
        int successfulSources = 0;

        for (int i = 0; i < currentUrls.size(); i++) {
            if (Thread.currentThread().isInterrupted() || builder.isFull()) {
                break;
            }

            if (i > 0) {
                sleepBetweenSources();
            }

            String url = currentUrls.get(i);
            boolean downloaded = false;
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                try {
                    int added = downloadSource(httpClient, url, builder);
                    log("Loaded " + added + " entries from " + url + ".");
                    successfulSources++;
                    downloaded = true;
                    break;
                } catch (IOException | IllegalArgumentException exception) {
                    log("Could not load proxy blocklist source " + url + " (attempt "
                            + attempt + "/" + MAX_RETRIES + "): " + exception.getMessage());
                    if (attempt < MAX_RETRIES) {
                        sleepBetweenSources();
                    }
                }
            }
            if (!downloaded) {
                log("Skipped proxy blocklist source after retries: " + url);
            }
        }

        if (successfulSources > 0) {
            Snapshot newSnapshot = builder.build();
            snapshot.set(newSnapshot);
            log("Proxy blocklist refreshed with " + newSnapshot.size + " unique entries from "
                    + successfulSources + " source(s).");
        } else {
            log("Proxy blocklist refresh failed for every source; keeping the previous snapshot.");
        }
    }

    private int downloadSource(OkHttpClient httpClient, String url, SnapshotBuilder builder) throws IOException {
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "TwiAntiVpn/1.0")
                .build();

        Response response = httpClient.newCall(request).execute();
        try {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("empty response body");
            }

            int before = builder.size();
            BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null && !builder.isFull()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("refresh interrupted");
                }
                if (line.length() > maxLineLength) {
                    continue;
                }
                Optional<Entry> entry = parseEntry(line);
                if (entry.isPresent()) {
                    builder.add(entry.get());
                }
            }
            return builder.size() - before;
        } finally {
            response.close();
        }
    }

    private Optional<Entry> parseEntry(String line) {
        String trimmed = stripComment(line).trim();
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        String[] tokens = trimmed.split("\\s+");
        for (String token : tokens) {
            Optional<Entry> parsed = parseToken(token);
            if (parsed.isPresent()) {
                return parsed;
            }
        }
        return Optional.empty();
    }

    private Optional<Entry> parseToken(String token) {
        String cleaned = token.trim();
        while (cleaned.endsWith(",") || cleaned.endsWith(";")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        if (cleaned.isEmpty() || cleaned.contains("://")) {
            return Optional.empty();
        }

        if (cleaned.contains("/")) {
            Optional<IpAddressUtil.Cidr> cidr = IpAddressUtil.parseCidr(cleaned);
            if (cidr.isPresent()) {
                return Optional.of(Entry.cidr(cidr.get()));
            }
        }

        Optional<IpAddressUtil.Address> direct = IpAddressUtil.parseLiteral(cleaned);
        if (direct.isPresent()) {
            return Optional.of(Entry.address(direct.get()));
        }

        int lastColon = cleaned.lastIndexOf(':');
        if (lastColon > 0 && cleaned.indexOf(':') == lastColon) {
            String possibleIpv4 = cleaned.substring(0, lastColon);
            Optional<IpAddressUtil.Address> withoutPort = IpAddressUtil.parseLiteral(possibleIpv4);
            if (withoutPort.isPresent()) {
                return Optional.of(Entry.address(withoutPort.get()));
            }
        }

        return Optional.empty();
    }

    private String stripComment(String line) {
        int hashIndex = line.indexOf('#');
        int semicolonIndex = line.indexOf(';');
        int commentIndex = -1;
        if (hashIndex >= 0) {
            commentIndex = hashIndex;
        }
        if (semicolonIndex >= 0 && (commentIndex < 0 || semicolonIndex < commentIndex)) {
            commentIndex = semicolonIndex;
        }
        return commentIndex >= 0 ? line.substring(0, commentIndex) : line;
    }

    private List<String> sanitizeUrls(List<String> configuredUrls) {
        if (configuredUrls == null || configuredUrls.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> sanitized = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String configuredUrl : configuredUrls) {
            if (sanitized.size() >= MAX_URLS || configuredUrl == null) {
                break;
            }
            String value = configuredUrl.trim();
            if (value.length() > 2048 || value.isEmpty() || !seen.add(value)) {
                continue;
            }
            try {
                URI uri = URI.create(value);
                String scheme = uri.getScheme();
                if (scheme == null) {
                    continue;
                }
                String lowerScheme = scheme.toLowerCase(Locale.ROOT);
                if (!lowerScheme.equals("http") && !lowerScheme.equals("https")) {
                    continue;
                }
                if (uri.getHost() == null) {
                    continue;
                }
                sanitized.add(value);
            } catch (IllegalArgumentException ignored) {
                log("Ignoring invalid proxy blocklist URL: " + value);
            }
        }
        return Collections.unmodifiableList(sanitized);
    }

    private int positiveOrDefault(int value, int defaultValue) {
        return value > 0 ? value : defaultValue;
    }

    private void sleepBetweenSources() {
        try {
            Thread.sleep(SOURCE_DELAY_MILLIS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    private void log(String message) {
        if (ConnectionGuard.getLogger() != null) {
            ConnectionGuard.getLogger().info("ProxyBlocklist | " + message);
        }
    }

    private static final class Entry {
        private final IpAddressUtil.Address address;
        private final IpAddressUtil.Cidr cidr;

        private Entry(IpAddressUtil.Address address, IpAddressUtil.Cidr cidr) {
            this.address = address;
            this.cidr = cidr;
        }

        private static Entry address(IpAddressUtil.Address address) {
            return new Entry(address, null);
        }

        private static Entry cidr(IpAddressUtil.Cidr cidr) {
            return new Entry(null, cidr);
        }
    }

    private static final class SnapshotBuilder {
        private final int maxEntries;
        private final Set<String> exactAddresses = new HashSet<>();
        private final Map<Integer, Set<Long>> ipv4Networks = new HashMap<>();
        private final List<Ipv6Network> ipv6Networks = new ArrayList<>();

        private SnapshotBuilder(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        private void add(Entry entry) {
            if (isFull()) {
                return;
            }

            if (entry.address != null) {
                exactAddresses.add(entry.address.getNormalized());
                return;
            }

            IpAddressUtil.Cidr cidr = entry.cidr;
            IpAddressUtil.Address address = cidr.getAddress();
            if (address.isIpv4()) {
                long mask = ipv4Mask(cidr.getPrefixLength());
                long network = address.getIpv4Value() & mask;
                ipv4Networks.computeIfAbsent(cidr.getPrefixLength(), ignored -> new HashSet<>()).add(network);
            } else {
                ipv6Networks.add(new Ipv6Network(address.getBytes(), cidr.getPrefixLength()));
            }
        }

        private boolean isFull() {
            return size() >= maxEntries;
        }

        private int size() {
            int networks = 0;
            for (Set<Long> values : ipv4Networks.values()) {
                networks += values.size();
            }
            return exactAddresses.size() + networks + ipv6Networks.size();
        }

        private Snapshot build() {
            Map<Integer, Set<Long>> immutableIpv4Networks = new HashMap<>();
            for (Map.Entry<Integer, Set<Long>> entry : ipv4Networks.entrySet()) {
                immutableIpv4Networks.put(entry.getKey(), Collections.unmodifiableSet(new HashSet<>(entry.getValue())));
            }
            return new Snapshot(
                    Collections.unmodifiableSet(new HashSet<>(exactAddresses)),
                    Collections.unmodifiableMap(immutableIpv4Networks),
                    Collections.unmodifiableList(new ArrayList<>(ipv6Networks)),
                    size(),
                    System.currentTimeMillis()
            );
        }
    }

    private static final class Snapshot {
        private final Set<String> exactAddresses;
        private final Map<Integer, Set<Long>> ipv4Networks;
        private final List<Ipv6Network> ipv6Networks;
        private final long size;
        private final long createdAtMillis;

        private Snapshot(
                Set<String> exactAddresses,
                Map<Integer, Set<Long>> ipv4Networks,
                List<Ipv6Network> ipv6Networks,
                long size,
                long createdAtMillis
        ) {
            this.exactAddresses = exactAddresses;
            this.ipv4Networks = ipv4Networks;
            this.ipv6Networks = ipv6Networks;
            this.size = size;
            this.createdAtMillis = createdAtMillis;
        }

        private static Snapshot empty() {
            return new Snapshot(
                    Collections.emptySet(),
                    Collections.emptyMap(),
                    Collections.emptyList(),
                    0L,
                    0L
            );
        }

        private boolean contains(IpAddressUtil.Address address) {
            if (exactAddresses.contains(address.getNormalized())) {
                return true;
            }
            if (address.isIpv4()) {
                for (Map.Entry<Integer, Set<Long>> entry : ipv4Networks.entrySet()) {
                    long network = address.getIpv4Value() & ipv4Mask(entry.getKey());
                    if (entry.getValue().contains(network)) {
                        return true;
                    }
                }
                return false;
            }
            byte[] bytes = address.getBytes();
            for (Ipv6Network network : ipv6Networks) {
                if (network.matches(bytes)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Ipv6Network {
        private final byte[] networkBytes;
        private final int prefixLength;

        private Ipv6Network(byte[] addressBytes, int prefixLength) {
            this.prefixLength = prefixLength;
            this.networkBytes = maskIpv6(addressBytes, prefixLength);
        }

        private boolean matches(byte[] addressBytes) {
            byte[] maskedAddress = maskIpv6(addressBytes, prefixLength);
            for (int i = 0; i < networkBytes.length; i++) {
                if (networkBytes[i] != maskedAddress[i]) {
                    return false;
                }
            }
            return true;
        }
    }

    private static long ipv4Mask(int prefixLength) {
        if (prefixLength == 0) {
            return 0L;
        }
        return (0xFFFFFFFFL << (32 - prefixLength)) & 0xFFFFFFFFL;
    }

    private static byte[] maskIpv6(byte[] bytes, int prefixLength) {
        byte[] masked = bytes.clone();
        int fullBytes = prefixLength / 8;
        int remainingBits = prefixLength % 8;
        for (int i = fullBytes + (remainingBits > 0 ? 1 : 0); i < masked.length; i++) {
            masked[i] = 0;
        }
        if (remainingBits > 0 && fullBytes < masked.length) {
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            masked[fullBytes] = (byte) (masked[fullBytes] & mask);
        }
        return masked;
    }
}
