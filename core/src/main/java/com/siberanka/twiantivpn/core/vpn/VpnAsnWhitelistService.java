package com.siberanka.twiantivpn.core.vpn;

import com.siberanka.twiantivpn.core.geo.GeoResult;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VpnAsnWhitelistService {
    private static final int MAX_ENTRIES = 512;
    private static final int MAX_ENTRY_LENGTH = 128;
    private static final long MAX_ASN = 4_294_967_295L;
    private static final Pattern ASN_PATTERN =
            Pattern.compile("(?i)\\bAS\\s*(\\d{1,10})\\b|\\b(\\d{1,10})\\b");

    private static final List<String> DEFAULT_ASNS = Collections.unmodifiableList(Arrays.asList(
            // Turkey: Turk Telekom/TTNet, Turkcell/Superonline, TurkNet, Turksat and Vodafone
            "AS9121", "AS47331", "AS20978", "AS34984", "AS16135",
            "AS12735", "AS47524", "AS8386", "AS15924", "AS15897",
            // Azerbaijan: Aztelekom, Azeronline, Uninet, Azercell and Bakcell
            "AS28787", "AS15723", "AS39232", "AS31721", "AS197830",
            // Kazakhstan: Kazakhtelecom, Transtelecom, Beeline, Kcell, Tele2 and AlmaTV
            "AS9198", "AS41798", "AS21299", "AS29355", "AS48503", "AS39824",
            // Uzbekistan: Uzbektelecom, Uzmobile, IST Telekom, Sarkor and Ucell
            "AS28910", "AS201767", "AS34718", "AS12365", "AS49273",
            // Kyrgyzstan: Kyrgyztelecom, ElCat, Mega-Line, AKNET, O! and MegaCom
            "AS12997", "AS8449", "AS41750", "AS12764", "AS41329", "AS50223",
            // Turkmenistan: Turkmentelecom and Ashgabat City Telephone Network
            "AS20661", "AS51495"
    ));

    private volatile State state = new State(false, Collections.emptySet());

    public void configure(boolean enabled, List<String> asns) {
        state = new State(enabled, sanitizeAsns(asns));
    }

    public boolean isEnabled() {
        State current = state;
        return current.enabled && !current.whitelistedAsns.isEmpty();
    }

    public boolean matches(GeoResult geoResult) {
        State current = state;
        if (!current.enabled || current.whitelistedAsns.isEmpty() || geoResult == null) {
            return false;
        }
        String normalizedAsn = normalizeAsn(geoResult.getAsn());
        return !normalizedAsn.isEmpty() && current.whitelistedAsns.contains(normalizedAsn);
    }

    public static List<String> defaultAsns() {
        return DEFAULT_ASNS;
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

    private Set<String> sanitizeAsns(List<String> asns) {
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
        return Collections.unmodifiableSet(sanitized);
    }

    private static final class State {
        private final boolean enabled;
        private final Set<String> whitelistedAsns;

        private State(boolean enabled, Set<String> whitelistedAsns) {
            this.enabled = enabled;
            this.whitelistedAsns = whitelistedAsns;
        }
    }
}
