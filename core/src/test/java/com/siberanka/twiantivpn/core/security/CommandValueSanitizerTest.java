package com.siberanka.twiantivpn.core.security;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandValueSanitizerTest {
    @Test
    void removesCommandSeparatorsControlsAndWhitespace() {
        assertEquals(
                "Provider_kick_op_user",
                CommandValueSanitizer.sanitize("Provider\n/kick op user")
        );
    }

    @Test
    void preservesNetworkIdentifiersAndBoundsLength() {
        assertEquals("AS13335", CommandValueSanitizer.sanitize("AS13335"));
        assertEquals("203.0.113.10:25565", CommandValueSanitizer.sanitize("203.0.113.10:25565"));
        char[] longValue = new char[256];
        Arrays.fill(longValue, 'x');
        assertTrue(CommandValueSanitizer.sanitize(new String(longValue)).length() <= 128);
    }
}
