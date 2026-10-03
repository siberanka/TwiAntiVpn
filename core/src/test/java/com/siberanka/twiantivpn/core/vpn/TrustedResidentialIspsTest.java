package com.siberanka.twiantivpn.core.vpn;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrustedResidentialIspsTest {
    @Test
    void coversTurkeyAndTheTurkicStates() {
        assertEquals(
                new HashSet<>(Arrays.asList("TR", "AZ", "KZ", "UZ", "KG", "TM")),
                TrustedResidentialIsps.supportedCountries()
        );
        for (String country : TrustedResidentialIsps.supportedCountries()) {
            assertFalse(TrustedResidentialIsps.asnsFor(country).isEmpty(), country);
        }
    }

    @Test
    void includesTheLargestAccessNetworkOfEveryCountry() {
        assertTrue(TrustedResidentialIsps.asnsFor("TR").contains("AS9121"));
        assertTrue(TrustedResidentialIsps.asnsFor("AZ").contains("AS8814"));
        assertTrue(TrustedResidentialIsps.asnsFor("KZ").contains("AS9198"));
        assertTrue(TrustedResidentialIsps.asnsFor("UZ").contains("AS8193"));
        assertTrue(TrustedResidentialIsps.asnsFor("KG").contains("AS47237"));
        assertTrue(TrustedResidentialIsps.asnsFor("TM").contains("AS20661"));
        assertTrue(TrustedResidentialIsps.asnsFor(" tr ").contains("AS34296"));
        assertTrue(TrustedResidentialIsps.asnsFor("XX").isEmpty());
    }

    @Test
    void entriesAreUniqueAndWellFormed() {
        List<String> all = TrustedResidentialIsps.allAsns();
        assertEquals(all.size(), new HashSet<>(all).size());
        int total = 0;
        for (String country : TrustedResidentialIsps.supportedCountries()) {
            total += TrustedResidentialIsps.asnsFor(country).size();
        }
        assertEquals(total, all.size(), "an ASN must belong to exactly one country");
        for (String asn : all) {
            assertTrue(asn.matches("AS[1-9][0-9]{0,9}"), asn);
        }
    }

    @Test
    void excludesHostingCdnSatelliteAndVpnNetworks() {
        List<String> all = TrustedResidentialIsps.allAsns();
        for (String excluded : Arrays.asList(
                "AS13335",  // Cloudflare
                "AS199524", // G-Core Labs
                "AS202422", // G-Core Labs
                "AS14593",  // Starlink
                "AS137409", // GSL Networks
                "AS212238", // Datacamp / CDN77
                "AS213535", // YottaSrc
                "AS214095"  // eSIM roaming
        )) {
            assertFalse(all.contains(excluded), excluded);
        }
    }

    @Test
    void neverOverlapsTheDefaultIspBlockList() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/config.yml");
        assertNotNull(stream);
        String config;
        try (Scanner scanner = new Scanner(stream, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
            config = scanner.next();
        }
        String ispBlock = config.substring(config.indexOf("  isp-block:"), config.indexOf("    isp-names:"));
        Set<String> blocked = new HashSet<>();
        Matcher matcher = Pattern.compile("AS\\d+").matcher(ispBlock);
        while (matcher.find()) {
            blocked.add(matcher.group());
        }
        assertFalse(blocked.isEmpty());
        for (String asn : TrustedResidentialIsps.allAsns()) {
            assertFalse(blocked.contains(asn), asn);
        }
    }
}
