package com.siberanka.twiantivpn.core.vpn;

import com.siberanka.twiantivpn.core.geo.GeoResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a VPN/proxy verdict should be ignored because the connection comes from a
 * trusted local residential or mobile ISP.
 *
 * <p>The trust is network based, not address based, so hard evidence still wins: addresses that
 * a geo provider reports as hosting/datacenter space and verdicts backed by anonymizer evidence
 * (Tor exits, named VPN operators, anonymizer/proxy/cloud blocklist sources) are never exempted
 * while the matching safeguard is enabled.</p>
 */
public final class VpnAsnWhitelistService {
    private static final int MAX_ENTRIES = 1024;
    private static final int MAX_ENTRY_LENGTH = 128;
    private static final long MAX_ASN = 4_294_967_295L;
    private static final Pattern ASN_PATTERN =
            Pattern.compile("(?i)\\bAS\\s*(\\d{1,10})\\b|\\b(\\d{1,10})\\b");
    private static final Pattern COUNTRY_PATTERN = Pattern.compile("[A-Z]{2}");

    private volatile State state = State.disabled();

    /**
     * Configures an explicit ASN list without the built-in registry. Kept for API compatibility.
     */
    public void configure(boolean enabled, List<String> asns) {
        configure(enabled, Collections.<String>emptyList(), asns, Collections.<String>emptyList(), true, true);
    }

    public void configure(boolean enabled,
                          List<String> builtInCountries,
                          List<String> additionalAsns,
                          List<String> excludedAsns,
                          boolean blockHosting,
                          boolean blockAnonymizers) {
        List<String> countries = sanitizeCountries(builtInCountries);
        Set<String> trusted = new HashSet<>(sanitizeAsns(TrustedResidentialIsps.asnsFor(countries)));
        trusted.addAll(sanitizeAsns(additionalAsns));
        trusted.removeAll(sanitizeAsns(excludedAsns));
        state = new State(
                enabled,
                Collections.unmodifiableSet(trusted),
                Collections.unmodifiableList(countries),
                blockHosting,
                blockAnonymizers
        );
    }

    public boolean isEnabled() {
        State current = state;
        return current.enabled && !current.trustedAsns.isEmpty();
    }

    /**
     * Returns true when the network identity belongs to a trusted ISP and is not hosting space.
     */
    public boolean matches(GeoResult geoResult) {
        State current = state;
        if (!current.enabled || current.trustedAsns.isEmpty() || geoResult == null) {
            return false;
        }
        if (current.blockHosting && geoResult.isHosting()) {
            return false;
        }
        String normalizedAsn = normalizeAsn(geoResult.getAsn());
        return !normalizedAsn.isEmpty() && current.trustedAsns.contains(normalizedAsn);
    }

    /**
     * Returns true when a positive VPN/proxy verdict may be ignored for this connection.
     */
    public boolean allows(GeoResult geoResult, VpnResult vpnResult) {
        return allowsVerdict(vpnResult) && matches(geoResult);
    }

    /**
     * Network-independent half of {@link #allows}: lets callers skip the geo lookup when the
     * verdict itself can never be exempted.
     */
    public boolean allowsVerdict(VpnResult vpnResult) {
        if (vpnResult == null || !vpnResult.isVpn()) {
            return false;
        }
        return !(state.blockAnonymizers && vpnResult.isAnonymizer());
    }

    public int trustedAsnCount() {
        return state.trustedAsns.size();
    }

    public List<String> builtInCountries() {
        return state.builtInCountries;
    }

    public static List<String> defaultAsns() {
        return TrustedResidentialIsps.allAsns();
    }

    static String normalizeAsn(String raw) {
        if (raw == null || raw.length() > MAX_ENTRY_LENGTH) {
            return "";
        }
        Matcher matcher = ASN_PATTERN.matcher(raw.trim());
        if (!matcher.find()) {
            return "";
        }
        String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        if (value == null) {
            return "";
        }
        try {
            long numericValue = Long.parseLong(value);
            return numericValue <= MAX_ASN ? Long.toString(numericValue) : "";
        } catch (NumberFormatException ignored) {
            return "";
        }
    }

    private static Set<String> sanitizeAsns(List<String> asns) {
        if (asns == null || asns.isEmpty()) {
            return Collections.emptySet();
        }
        Set<String> sanitized = new HashSet<>();
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

    private static List<String> sanitizeCountries(List<String> countries) {
        if (countries == null || countries.isEmpty()) {
            return Collections.emptyList();
        }
        Set<String> sanitized = new LinkedHashSet<>();
        for (String raw : countries) {
            if (raw == null) {
                continue;
            }
            String countryCode = raw.trim().toUpperCase(Locale.ROOT);
            if (COUNTRY_PATTERN.matcher(countryCode).matches()
                    && TrustedResidentialIsps.supportedCountries().contains(countryCode)) {
                sanitized.add(countryCode);
            }
        }
        return new ArrayList<>(sanitized);
    }

    private static final class State {
        private final boolean enabled;
        private final Set<String> trustedAsns;
        private final List<String> builtInCountries;
        private final boolean blockHosting;
        private final boolean blockAnonymizers;

        private State(boolean enabled,
                      Set<String> trustedAsns,
                      List<String> builtInCountries,
                      boolean blockHosting,
                      boolean blockAnonymizers) {
            this.enabled = enabled;
            this.trustedAsns = trustedAsns;
            this.builtInCountries = builtInCountries;
            this.blockHosting = blockHosting;
            this.blockAnonymizers = blockAnonymizers;
        }

        private static State disabled() {
            return new State(false, Collections.<String>emptySet(), Collections.<String>emptyList(), true, true);
        }
    }
}
