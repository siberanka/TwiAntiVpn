package com.siberanka.twiantivpn.core.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SonarApiEarlyCheckHookTest {
    @Test
    void preVerificationTimeoutUsesSafeBounds() {
        assertEquals(6, SonarApiEarlyCheckHook.boundedTimeout(0));
        assertEquals(6, SonarApiEarlyCheckHook.boundedTimeout(-1));
        assertEquals(1, SonarApiEarlyCheckHook.boundedTimeout(1));
        assertEquals(5, SonarApiEarlyCheckHook.boundedTimeout(5));
        assertEquals(7, SonarApiEarlyCheckHook.boundedTimeout(30));
    }
}
