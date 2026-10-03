package com.siberanka.twiantivpn.core.vpn;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyCheckVpnProviderTest {
    @Test
    void torExitsAndNamedVpnOperatorsAreHardAnonymizerEvidence() {
        assertTrue(ProxyCheckVpnProvider.isHardAnonymizer(json("{\"proxy\":\"yes\",\"type\":\"TOR\"}")));
        assertTrue(ProxyCheckVpnProvider.isHardAnonymizer(json(
                "{\"proxy\":\"yes\",\"type\":\"VPN\",\"operator\":{\"name\":\"ExampleVPN\"}}")));
        assertTrue(ProxyCheckVpnProvider.isHardAnonymizer(json("{\"proxy\":\"yes\",\"operator\":\"ExampleVPN\"}")));
    }

    @Test
    void genericProxyGuessesAreNotHardEvidence() {
        assertFalse(ProxyCheckVpnProvider.isHardAnonymizer(json("{\"proxy\":\"yes\",\"type\":\"VPN\"}")));
        assertFalse(ProxyCheckVpnProvider.isHardAnonymizer(json("{\"proxy\":\"yes\",\"type\":\"Inference Engine\"}")));
        assertFalse(ProxyCheckVpnProvider.isHardAnonymizer(json("{\"proxy\":\"yes\",\"operator\":{}}")));
        assertFalse(ProxyCheckVpnProvider.isHardAnonymizer(json("{\"proxy\":\"yes\",\"operator\":null}")));
        assertFalse(ProxyCheckVpnProvider.isHardAnonymizer(null));
    }

    private static JsonObject json(String value) {
        return JsonParser.parseString(value).getAsJsonObject();
    }
}
