package com.siberanka.twiantivpn.core.blocklist;

import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.net.IpAddressUtil;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class ProxyBlocklistService {
    private static final int DEFAULT_INTERVAL_MINUTES = 60;
    private static final int DEFAULT_MAX_ENTRIES = 3000000;
    private static final int DEFAULT_MAX_LINE_LENGTH = 512;
    private static final int DEFAULT_TIMEOUT_SECONDS = 15;
    private static final int DEFAULT_SOURCE_DELAY_MILLIS = 250;
    private static final int MIN_INTERVAL_MINUTES = 5;
    private static final int MAX_INTERVAL_MINUTES = 1440;
    private static final int MAX_ENTRIES_LIMIT = 15000000;
    private static final int MIN_LINE_LENGTH = 64;
    private static final int MAX_LINE_LENGTH_LIMIT = 2048;
    private static final int MIN_TIMEOUT_SECONDS = 3;
    private static final int MAX_TIMEOUT_SECONDS = 30;
    private static final int MIN_SOURCE_DELAY_MILLIS = 100;
    private static final int MAX_SOURCE_DELAY_MILLIS = 5000;
    private static final int MAX_URLS = 64;
    private static final int MAX_RETRIES = 3;
    private static final int MIN_IPV4_PREFIX_LENGTH = 8;
    private static final int MIN_IPV6_PREFIX_LENGTH = 16;
    private static final long MAX_SOURCE_BYTES = 64L * 1024L * 1024L;

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());
    private final AtomicLong generation = new AtomicLong();
    private final Object lifecycleLock = new Object();
    private volatile ScheduledExecutorService executorService;
    private volatile ScheduledFuture<?> refreshTask;
    private volatile boolean enabled;
    private volatile List<String> urls = Collections.emptyList();
    private volatile int refreshIntervalMinutes = DEFAULT_INTERVAL_MINUTES;
    private volatile int maxEntries = DEFAULT_MAX_ENTRIES;
    private volatile int maxLineLength = DEFAULT_MAX_LINE_LENGTH;
    private volatile int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
    private volatile int sourceDelayMillis = DEFAULT_SOURCE_DELAY_MILLIS;

    public void configure(
            boolean enabled,
            List<String> urls,
            int refreshIntervalMinutes,
            int maxEntries,
            int maxLineLength,
            int timeoutSeconds,
            int sourceDelayMillis
    ) {
        this.enabled = enabled;
        this.urls = sanitizeUrls(urls);
        this.refreshIntervalMinutes = boundedOrDefault("refresh-interval", refreshIntervalMinutes,
                DEFAULT_INTERVAL_MINUTES, MIN_INTERVAL_MINUTES, MAX_INTERVAL_MINUTES);
        this.maxEntries = boundedOrDefault("max-entries", maxEntries,
                DEFAULT_MAX_ENTRIES, 1, MAX_ENTRIES_LIMIT);
        this.maxLineLength = boundedOrDefault("max-line-length", maxLineLength,
                DEFAULT_MAX_LINE_LENGTH, MIN_LINE_LENGTH, MAX_LINE_LENGTH_LIMIT);
        this.timeoutSeconds = boundedOrDefault("request-timeout-seconds", timeoutSeconds,
                DEFAULT_TIMEOUT_SECONDS, MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
        this.sourceDelayMillis = boundedOrDefault("source-delay-millis", sourceDelayMillis,
                DEFAULT_SOURCE_DELAY_MILLIS, MIN_SOURCE_DELAY_MILLIS, MAX_SOURCE_DELAY_MILLIS);
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
            long refreshGeneration = generation.get();
            refreshTask = executorService.scheduleWithFixedDelay(
                    new Runnable() {
                        @Override
                        public void run() {
                            refreshSafely(refreshGeneration);
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
        generation.incrementAndGet();
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

    private void refreshSafely(long refreshGeneration) {
        try {
            refresh(refreshGeneration);
        } catch (Throwable throwable) {
            log("Unexpected refresh failure: " + throwable.getMessage());
        }
    }

    private void refresh(long refreshGeneration) {
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

        if (successfulSources > 0 && builder.size() > 0) {
            Snapshot newSnapshot = builder.build();
            if (refreshGeneration != generation.get() || !enabled) {
                log("Discarded a stale proxy blocklist refresh.");
                return;
            }
            snapshot.set(newSnapshot);
            log("Proxy blocklist refreshed with " + newSnapshot.size + " unique entries from "
                    + successfulSources + " source(s).");
        } else {
            log("Proxy blocklist refresh produced no usable entries; keeping the previous snapshot.");
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
            if (body.contentLength() > MAX_SOURCE_BYTES) {
                throw new IOException("response body is too large");
            }

            int before = builder.size();
            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new LimitedInputStream(body.byteStream(), MAX_SOURCE_BYTES),
                    StandardCharsets.UTF_8
            ));
            String line;
            while ((line = readBoundedLine(reader)) != null && !builder.isFull()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("refresh interrupted");
                }
                if (line.isEmpty()) {
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

        String[] tokens = trimmed.split("[\\s,]+");
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
                int minimumPrefix = cidr.get().getAddress().isIpv4()
                        ? MIN_IPV4_PREFIX_LENGTH
                        : MIN_IPV6_PREFIX_LENGTH;
                if (cidr.get().getPrefixLength() < minimumPrefix) {
                    return Optional.empty();
                }
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

    private String readBoundedLine(BufferedReader reader) throws IOException {
        StringBuilder builder = new StringBuilder(Math.min(maxLineLength, 128));
        boolean readAny = false;
        boolean exceededLimit = false;

        while (true) {
            int value = reader.read();
            if (value == -1) {
                if (!readAny) {
                    return null;
                }
                return exceededLimit ? "" : builder.toString();
            }

            readAny = true;
            if (value == '\n') {
                return exceededLimit ? "" : builder.toString();
            }
            if (value == '\r') {
                continue;
            }
            if (exceededLimit) {
                continue;
            }
            if (builder.length() >= maxLineLength) {
                exceededLimit = true;
                continue;
            }
            builder.append((char) value);
        }
    }

    private int boundedOrDefault(String name, int value, int defaultValue, int minValue, int maxValue) {
        if (value <= 0) {
            return defaultValue;
        }
        if (value < minValue) {
            log("Config value proxy-blocklist." + name + " is below the safe minimum; using " + minValue + ".");
            return minValue;
        }
        if (value > maxValue) {
            log("Config value proxy-blocklist." + name + " is above the safe maximum; using " + maxValue + ".");
            return maxValue;
        }
        return value;
    }

    private void sleepBetweenSources() {
        try {
            Thread.sleep(sourceDelayMillis);
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
        private final PrimitiveIntSet exactIpv4Addresses = new PrimitiveIntSet();
        private final Set<Ipv6Network> exactIpv6Addresses = new HashSet<>();
        private final Map<Integer, PrimitiveIntSet> ipv4Networks = new HashMap<>();
        private final Map<Integer, Set<Ipv6Network>> ipv6Networks = new HashMap<>();

        private SnapshotBuilder(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        private void add(Entry entry) {
            if (isFull()) {
                return;
            }

            if (entry.address != null) {
                addAddress(entry.address);
                return;
            }

            IpAddressUtil.Cidr cidr = entry.cidr;
            IpAddressUtil.Address address = cidr.getAddress();
            if ((address.isIpv4() && cidr.getPrefixLength() == 32)
                    || (!address.isIpv4() && cidr.getPrefixLength() == 128)) {
                addAddress(address);
                return;
            }
            if (address.isIpv4()) {
                int network = ((int) address.getIpv4Value()) & ipv4Mask(cidr.getPrefixLength());
                ipv4Networks.computeIfAbsent(cidr.getPrefixLength(), ignored -> new PrimitiveIntSet()).add(network);
            } else {
                ipv6Networks.computeIfAbsent(cidr.getPrefixLength(), ignored -> new HashSet<>())
                        .add(new Ipv6Network(address.getBytes(), cidr.getPrefixLength()));
            }
        }

        private void addAddress(IpAddressUtil.Address address) {
            if (address.isIpv4()) {
                exactIpv4Addresses.add((int) address.getIpv4Value());
            } else {
                exactIpv6Addresses.add(new Ipv6Network(address.getBytes(), 128));
            }
        }

        private boolean isFull() {
            return size() >= maxEntries;
        }

        private int size() {
            int networks = 0;
            for (PrimitiveIntSet values : ipv4Networks.values()) {
                networks += values.size();
            }
            for (Set<Ipv6Network> values : ipv6Networks.values()) {
                networks += values.size();
            }
            return exactIpv4Addresses.size() + exactIpv6Addresses.size() + networks;
        }

        private Snapshot build() {
            exactIpv4Addresses.freeze();
            Map<Integer, PrimitiveIntSet> immutableIpv4Networks = new HashMap<>();
            for (Map.Entry<Integer, PrimitiveIntSet> entry : ipv4Networks.entrySet()) {
                entry.getValue().freeze();
                immutableIpv4Networks.put(entry.getKey(), entry.getValue());
            }
            Map<Integer, Set<Ipv6Network>> immutableIpv6Networks = new HashMap<>();
            for (Map.Entry<Integer, Set<Ipv6Network>> entry : ipv6Networks.entrySet()) {
                immutableIpv6Networks.put(entry.getKey(), Collections.unmodifiableSet(new HashSet<>(entry.getValue())));
            }
            return new Snapshot(
                    exactIpv4Addresses,
                    Collections.unmodifiableSet(new HashSet<>(exactIpv6Addresses)),
                    Collections.unmodifiableMap(immutableIpv4Networks),
                    Collections.unmodifiableMap(immutableIpv6Networks),
                    size(),
                    System.currentTimeMillis()
            );
        }
    }

    private static final class Snapshot {
        private final PrimitiveIntSet exactIpv4Addresses;
        private final Set<Ipv6Network> exactIpv6Addresses;
        private final Map<Integer, PrimitiveIntSet> ipv4Networks;
        private final Map<Integer, Set<Ipv6Network>> ipv6Networks;
        private final long size;
        private final long createdAtMillis;

        private Snapshot(
                PrimitiveIntSet exactIpv4Addresses,
                Set<Ipv6Network> exactIpv6Addresses,
                Map<Integer, PrimitiveIntSet> ipv4Networks,
                Map<Integer, Set<Ipv6Network>> ipv6Networks,
                long size,
                long createdAtMillis
        ) {
            this.exactIpv4Addresses = exactIpv4Addresses;
            this.exactIpv6Addresses = exactIpv6Addresses;
            this.ipv4Networks = ipv4Networks;
            this.ipv6Networks = ipv6Networks;
            this.size = size;
            this.createdAtMillis = createdAtMillis;
        }

        private static Snapshot empty() {
            return new Snapshot(
                    PrimitiveIntSet.empty(),
                    Collections.emptySet(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    0L,
                    0L
            );
        }

        private boolean contains(IpAddressUtil.Address address) {
            if (address.isIpv4()) {
                int ipv4 = (int) address.getIpv4Value();
                if (exactIpv4Addresses.contains(ipv4)) {
                    return true;
                }
                for (Map.Entry<Integer, PrimitiveIntSet> entry : ipv4Networks.entrySet()) {
                    int network = ipv4 & ipv4Mask(entry.getKey());
                    if (entry.getValue().contains(network)) {
                        return true;
                    }
                }
                return false;
            }
            byte[] bytes = address.getBytes();
            if (exactIpv6Addresses.contains(new Ipv6Network(bytes, 128))) {
                return true;
            }
            for (Map.Entry<Integer, Set<Ipv6Network>> entry : ipv6Networks.entrySet()) {
                if (entry.getValue().contains(new Ipv6Network(bytes, entry.getKey()))) {
                    return true;
                }
            }
            return false;
        }
    }

    private static final class Ipv6Network {
        private final long highBits;
        private final long lowBits;
        private final int prefixLength;

        private Ipv6Network(byte[] addressBytes, int prefixLength) {
            this.prefixLength = prefixLength;
            long high = bytesToLong(addressBytes, 0);
            long low = bytesToLong(addressBytes, 8);
            if (prefixLength <= 0) {
                high = 0L;
                low = 0L;
            } else if (prefixLength < 64) {
                high &= ipv6Mask(prefixLength);
                low = 0L;
            } else if (prefixLength == 64) {
                low = 0L;
            } else if (prefixLength < 128) {
                low &= ipv6Mask(prefixLength - 64);
            }
            this.highBits = high;
            this.lowBits = low;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Ipv6Network)) {
                return false;
            }
            Ipv6Network that = (Ipv6Network) other;
            return prefixLength == that.prefixLength && highBits == that.highBits && lowBits == that.lowBits;
        }

        @Override
        public int hashCode() {
            int result = (int) (highBits ^ (highBits >>> 32));
            result = 31 * result + (int) (lowBits ^ (lowBits >>> 32));
            result = 31 * result + prefixLength;
            return result;
        }
    }

    private static final class PrimitiveIntSet {
        private static final int DEFAULT_CAPACITY = 16;
        private static final int LOAD_FACTOR_PERCENT = 70;

        private int[] keys;
        private byte[] used;
        private int size;
        private boolean frozen;

        private PrimitiveIntSet() {
            this(new int[DEFAULT_CAPACITY], new byte[DEFAULT_CAPACITY], 0, false);
        }

        private PrimitiveIntSet(int[] keys, byte[] used, int size, boolean frozen) {
            this.keys = keys;
            this.used = used;
            this.size = size;
            this.frozen = frozen;
        }

        private static PrimitiveIntSet empty() {
            return new PrimitiveIntSet(new int[0], new byte[0], 0, true);
        }

        private boolean add(int value) {
            if (frozen) {
                throw new IllegalStateException("set is frozen");
            }
            ensureCapacity(size + 1);
            int mask = keys.length - 1;
            int index = spread(value) & mask;
            while (used[index] != 0) {
                if (keys[index] == value) {
                    return false;
                }
                index = (index + 1) & mask;
            }
            used[index] = 1;
            keys[index] = value;
            size++;
            return true;
        }

        private boolean contains(int value) {
            if (size == 0 || keys.length == 0) {
                return false;
            }
            int mask = keys.length - 1;
            int index = spread(value) & mask;
            while (used[index] != 0) {
                if (keys[index] == value) {
                    return true;
                }
                index = (index + 1) & mask;
            }
            return false;
        }

        private int size() {
            return size;
        }

        private void freeze() {
            frozen = true;
        }

        private void ensureCapacity(int targetSize) {
            if (keys.length == 0) {
                rehash(DEFAULT_CAPACITY);
                return;
            }
            if ((long) targetSize * 100L <= (long) keys.length * LOAD_FACTOR_PERCENT) {
                return;
            }
            rehash(keys.length << 1);
        }

        private void rehash(int requestedCapacity) {
            int capacity = tableSizeFor(requestedCapacity);
            int[] oldKeys = keys;
            byte[] oldUsed = used;
            keys = new int[capacity];
            used = new byte[capacity];
            int oldSize = size;
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldUsed[i] != 0) {
                    add(oldKeys[i]);
                }
            }
            size = oldSize;
        }

        private static int tableSizeFor(int requestedCapacity) {
            int capacity = DEFAULT_CAPACITY;
            while (capacity < requestedCapacity) {
                capacity <<= 1;
            }
            return capacity;
        }

        private static int spread(int value) {
            value ^= value >>> 16;
            value *= 0x7feb352d;
            value ^= value >>> 15;
            value *= 0x846ca68b;
            value ^= value >>> 16;
            return value;
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long maxBytes;
        private long bytesRead;

        private LimitedInputStream(InputStream inputStream, long maxBytes) {
            super(inputStream);
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value != -1) {
                countBytes(1);
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int read = super.read(bytes, offset, length);
            if (read > 0) {
                countBytes(read);
            }
            return read;
        }

        private void countBytes(int count) throws IOException {
            bytesRead += count;
            if (bytesRead > maxBytes) {
                throw new IOException("response body exceeded the safe size limit");
            }
        }
    }

    private static int ipv4Mask(int prefixLength) {
        if (prefixLength == 0) {
            return 0;
        }
        return (int) (0xFFFFFFFFL << (32 - prefixLength));
    }

    private static long bytesToLong(byte[] bytes, int offset) {
        long value = 0L;
        for (int i = offset; i < offset + 8; i++) {
            value = (value << 8) | (bytes[i] & 0xFFL);
        }
        return value;
    }

    private static long ipv6Mask(int prefixLength) {
        if (prefixLength <= 0) {
            return 0L;
        }
        if (prefixLength >= 64) {
            return -1L;
        }
        return -1L << (64 - prefixLength);
    }
}
