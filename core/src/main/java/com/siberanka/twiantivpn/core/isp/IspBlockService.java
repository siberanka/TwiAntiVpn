package com.siberanka.twiantivpn.core.isp;

import com.siberanka.twiantivpn.core.geo.GeoResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IspBlockService {
    private static final int MAX_ENTRIES = 512;
    private static final int MAX_ENTRY_LENGTH = 128;
    private static final Pattern ASN_PATTERN = Pattern.compile("(?i)\\bAS\\s*(\\d{1,10})\\b|\\b(\\d{1,10})\\b");

    private volatile boolean enabled;
    private volatile Set<String> blockedAsns = new HashSet<>();
    private volatile List<String> blockedIspNames = new ArrayList<>();

    public void configure(boolean enabled, List<String> asns, List<String> ispNames) {
        this.enabled = enabled;
        this.blockedAsns = sanitizeAsns(asns);
        this.blockedIspNames = sanitizeNames(ispNames);
    }

    public Optional<IspBlockResult> match(GeoResult geoResult) {
        if (!enabled || geoResult == null) {
            return Optional.empty();
        }

        String normalizedAsn = normalizeAsn(geoResult.getAsn());
        if (!normalizedAsn.isEmpty() && blockedAsns.contains(normalizedAsn)) {
            return Optional.of(new IspBlockResult(geoResult, "ASN", "AS" + normalizedAsn));
        }

        String isp = normalizeText(geoResult.getIspName());
        String organization = normalizeText(geoResult.getOrganization());
        for (String blockedName : blockedIspNames) {
            if ((!isp.isEmpty() && isp.contains(blockedName)) || (!organization.isEmpty() && organization.contains(blockedName))) {
                return Optional.of(new IspBlockResult(geoResult, "ISP", blockedName));
            }
        }

        return Optional.empty();
    }

    private Set<String> sanitizeAsns(List<String> asns) {
        Set<String> sanitized = new HashSet<>();
        if (asns == null) {
            return sanitized;
        }
        for (String raw : asns) {
            if (sanitized.size() >= MAX_ENTRIES) {
                break;
            }
            String normalized = normalizeAsn(raw);
            if (!normalized.isEmpty()) {
                sanitized.add(normalized);
            }
        }
        return sanitized;
    }

    private List<String> sanitizeNames(List<String> names) {
        List<String> sanitized = new ArrayList<>();
        if (names == null) {
            return sanitized;
        }
        for (String raw : names) {
            if (sanitized.size() >= MAX_ENTRIES || raw == null) {
                break;
            }
            String normalized = normalizeText(raw);
            if (!normalized.isEmpty() && normalized.length() <= MAX_ENTRY_LENGTH) {
                sanitized.add(normalized);
            }
        }
        return sanitized;
    }

    private String normalizeAsn(String raw) {
        if (raw == null || raw.length() > MAX_ENTRY_LENGTH) {
            return "";
        }
        Matcher matcher = ASN_PATTERN.matcher(raw.trim());
        if (!matcher.find()) {
            return "";
        }
        String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        return value == null ? "" : value.replaceFirst("^0+(?!$)", "");
    }

    private String normalizeText(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_ENTRY_LENGTH) {
            return "";
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
}
