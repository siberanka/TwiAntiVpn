package com.siberanka.twiantivpn.core.geo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProxyCheckGeoProviderTest {
    @Test
    void mapsNetworkTypeToHostingClassification() {
        assertEquals(Boolean.TRUE, ProxyCheckGeoProvider.hostingFromType("Hosting"));
        assertEquals(Boolean.TRUE, ProxyCheckGeoProvider.hostingFromType("Data Center"));
        assertEquals(Boolean.FALSE, ProxyCheckGeoProvider.hostingFromType("Residential"));
        assertEquals(Boolean.FALSE, ProxyCheckGeoProvider.hostingFromType("wireless"));
        assertEquals(Boolean.FALSE, ProxyCheckGeoProvider.hostingFromType("Business"));
        assertNull(ProxyCheckGeoProvider.hostingFromType("VPN"));
        assertNull(ProxyCheckGeoProvider.hostingFromType(""));
        assertNull(ProxyCheckGeoProvider.hostingFromType(null));
    }
}
