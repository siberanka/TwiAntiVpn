package com.siberanka.twiantivpn.core.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class UsernameFilterService {
    private static final int MAX_PATTERNS = 256;
    private static final int MAX_PATTERN_LENGTH = 32;
    private static final int MAX_USERNAME_LENGTH = 64;

    private volatile boolean enabled;
    private volatile List<String> blockedContains = new ArrayList<>();

    public void configure(boolean enabled, List<String> blockedContains) {
        this.enabled = enabled;
        this.blockedContains = sanitize(blockedContains);
    }

    public Optional<String> findMatch(String username) {
        if (!enabled || username == null || username.length() > MAX_USERNAME_LENGTH) {
            return Optional.empty();
        }
        String normalizedUsername = username.toLowerCase(Locale.ROOT);
        for (String blocked : blockedContains) {
            if (normalizedUsername.contains(blocked)) {
                return Optional.of(blocked);
            }
        }
        return Optional.empty();
    }

    private List<String> sanitize(List<String> values) {
        List<String> sanitized = new ArrayList<>();
        if (values == null) {
            return sanitized;
        }
        for (String raw : values) {
            if (sanitized.size() >= MAX_PATTERNS || raw == null) {
                break;
            }
            String normalized = raw.trim().toLowerCase(Locale.ROOT);
            if (!normalized.isEmpty() && normalized.length() <= MAX_PATTERN_LENGTH) {
                sanitized.add(normalized);
            }
        }
        return sanitized;
    }
}
