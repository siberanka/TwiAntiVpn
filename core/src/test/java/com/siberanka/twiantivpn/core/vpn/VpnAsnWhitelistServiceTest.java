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
        assertTrue(VpnAsnWhitelistService.defaultAsns().contains("AS28787"));
        assertTrue(VpnAsnWhitelistService.defaultAsns().contains("AS9198"));
        assertTrue(VpnAsnWhitelistService.defaultAsns().contains("AS28910"));
        assertTrue(VpnAsnWhitelistService.defaultAsns().contains("AS12997"));
        assertTrue(VpnAsnWhitelistService.defaultAsns().contains("AS20661"));
        assertEquals(34, VpnAsnWhitelistService.defaultAsns().size());
    }

    private GeoResult geo(String asn) {
        return new GeoResult("203.0.113.1", "TR", "Istanbul", "ISP", asn, "Organization");
    }
}
