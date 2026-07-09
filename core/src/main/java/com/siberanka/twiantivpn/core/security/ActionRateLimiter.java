package com.siberanka.twiantivpn.core.security;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class ActionRateLimiter {
    private static final int MAX_KEYS = 10000;
    private static final int DEFAULT_COOLDOWN_SECONDS = 5;
    private static final int MAX_COOLDOWN_SECONDS = 300;

    private final Object lock = new Object();
    private final LinkedHashMap<String, Long> lastActions =
            new LinkedHashMap<String, Long>(128, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                    return size() > MAX_KEYS;
                }
            };
    private volatile long cooldownNanos =
            TimeUnit.SECONDS.toNanos(DEFAULT_COOLDOWN_SECONDS);

    public void configure(int cooldownSeconds) {
        int bounded = cooldownSeconds <= 0
                ? DEFAULT_COOLDOWN_SECONDS
                : Math.min(cooldownSeconds, MAX_COOLDOWN_SECONDS);
        cooldownNanos = TimeUnit.SECONDS.toNanos(bounded);
        synchronized (lock) {
            lastActions.clear();
        }
    }

    public boolean tryAcquire(String actionType, String ipAddress) {
        String key = safe(actionType, 32) + "|" + safe(ipAddress, 64);
        long now = System.nanoTime();
        synchronized (lock) {
            Long previous = lastActions.get(key);
            if (previous != null && now - previous < cooldownNanos) {
                return false;
            }
            lastActions.put(key, now);
            return true;
        }
    }

    public void clear() {
        synchronized (lock) {
            lastActions.clear();
        }
    }

    private String safe(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
