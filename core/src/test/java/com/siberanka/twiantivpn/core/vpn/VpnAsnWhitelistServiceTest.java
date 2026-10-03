package com.siberanka.twiantivpn.core.vpn;

import com.siberanka.twiantivpn.core.geo.GeoResult;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VpnAsnWhitelistServiceTest {
    @Test
    void matchesAsnInsideProviderDescription() {
        VpnAsnWhitelistService service = new VpnAsnWhitelistService();
        service.configure(true, Collections.singletonList("AS9121"));

        assertTrue(service.matches(geo("AS9121 TTNet Turk Telekomunikasyon A.S.")));
        assertFalse(service.matches(geo("AS91210 Similar number must not match")));
    }

    @Test
    void acceptsPrefixedAndNumericConfigEntries() {
        VpnAsnWhitelistService service = new VpnAsnWhitelistService();
        service.configure(true, Arrays.asList("as34984", "0012735"));

        assertTrue(service.matches(geo("AS34984 Superonline")));
        assertTrue(service.matches(geo("AS12735 TurkNet")));
    }

    @Test
    void disabledOrInvalidWhitelistDoesNotBypassVpnChecks() {
        VpnAsnWhitelistService service = new VpnAsnWhitelistService();
        service.configure(false, Collections.singletonList("AS9121"));
        assertFalse(service.matches(geo("AS9121")));

        service.configure(true, Arrays.asList("not-an-asn", "AS4294967296"));
        assertFalse(service.isEnabled());
        assertFalse(service.matches(geo("AS9121")));
    }

    @Test
    void shipsCuratedTurkicCountryAccessNetworks() {
        assertTrue(VpnAsnWhitelistService.defaultAsns().contains("AS9121"));
        assertTrue(VpnAsnWhitelistService.defaultAsns().contains("AS8814"));
        assertTrue(VpnAsnWhitelistService.defaultAsns().contains("AS206026"));
        assertTrue(VpnAsnWhitelistService.defaultAsns().contains("AS8193"));
        assertTrue(VpnAsnWhitelistService.defaultAsns().contains("AS47237"));
        assertTrue(VpnAsnWhitelistService.defaultAsns().contains("AS59974"));
        assertEquals(168, VpnAsnWhitelistService.defaultAsns().size());
    }

    @Test
    void builtInCountriesAreTrustedWithoutListingAsnsInConfig() {
        VpnAsnWhitelistService service = new VpnAsnWhitelistService();
        service.configure(true, Arrays.asList("tr", " kz "), Collections.<String>emptyList(),
                Collections.<String>emptyList(), true, true);

        assertTrue(service.matches(geo("AS34296 Millenicom")));
        assertTrue(service.matches(geo("AS206026 Kar-Tel")));
        assertFalse(service.matches(geo("AS8814 Aztelekom")), "AZ was not selected");
        assertEquals(Arrays.asList("TR", "KZ"), service.builtInCountries());
    }

    @Test
    void unknownCountriesAreIgnoredAndEmptyCountryListDisablesBuiltIns() {
        VpnAsnWhitelistService service = new VpnAsnWhitelistService();
        service.configure(true, Arrays.asList("DE", "XX", "TURKEY"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), true, true);

        assertFalse(service.isEnabled());
        assertFalse(service.matches(geo("AS9121")));
    }

    @Test
    void additionalAndExcludedAsnsAdjustTheBuiltInList() {
        VpnAsnWhitelistService service = new VpnAsnWhitelistService();
        service.configure(true, Collections.singletonList("TR"), Collections.singletonList("AS64500"),
                Collections.singletonList("9121"), true, true);

        assertTrue(service.matches(geo("AS64500 Custom ISP")));
        assertFalse(service.matches(geo("AS9121 TTNet")), "excluded ASN must win over the built-in list");
        assertTrue(service.matches(geo("AS34984 Superonline")));
    }

    @Test
    void hostingAddressesInsideTrustedNetworksAreNotExempted() {
        VpnAsnWhitelistService service = new VpnAsnWhitelistService();
        service.configure(true, Collections.singletonList("TR"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), true, true);
        VpnResult genericProxyGuess = new VpnResult("203.0.113.1", true);

        assertTrue(service.allows(geo("AS9121", null), genericProxyGuess));
        assertTrue(service.allows(geo("AS9121", Boolean.FALSE), genericProxyGuess));
        assertFalse(service.allows(geo("AS9121", Boolean.TRUE), genericProxyGuess));

        service.configure(true, Collections.singletonList("TR"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), false, true);
        assertTrue(service.allows(geo("AS9121", Boolean.TRUE), genericProxyGuess));
    }

    @Test
    void hardAnonymizerEvidenceIsNeverExemptedWhileProtected() {
        VpnAsnWhitelistService service = new VpnAsnWhitelistService();
        service.configure(true, Collections.singletonList("TR"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), true, true);
        VpnResult torExit = new VpnResult("203.0.113.1", true).setAnonymizer(true);

        assertFalse(service.allowsVerdict(torExit));
        assertFalse(service.allows(geo("AS9121"), torExit));
        assertFalse(service.allows(geo("AS9121"), new VpnResult("203.0.113.1", false)));

        service.configure(true, Collections.singletonList("TR"), Collections.<String>emptyList(),
                Collections.<String>emptyList(), true, false);
        assertTrue(service.allows(geo("AS9121"), torExit));
    }

    private GeoResult geo(String asn) {
        return geo(asn, null);
    }

    private GeoResult geo(String asn, Boolean hosting) {
        return new GeoResult("203.0.113.1", "TR", "Istanbul", "ISP", asn, "Organization", hosting);
    }
}
