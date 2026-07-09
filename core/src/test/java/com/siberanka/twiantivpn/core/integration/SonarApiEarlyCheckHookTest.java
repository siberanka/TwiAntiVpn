package com.siberanka.twiantivpn.core.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SonarApiEarlyCheckHookTest {
    @Test
    void preVerificationTimeoutUsesSafeBounds() {
        assertEquals(6, SonarApiEarlyCheckHook.boundedTimeout(0));
        assertEquals(6, SonarApiEarlyCheckHook.boundedTimeout(-1));
        assertEquals(1, SonarApiEarlyCheckHook.boundedTimeout(1));
        assertEquals(5, SonarApiEarlyCheckHook.boundedTimeout(5));
        assertEquals(7, SonarApiEarlyCheckHook.boundedTimeout(30));
    }

    @Test
    void disconnectUsesRuntimeRelocatedComponentType() {
        RelocatedUser user = new RelocatedUser();

        boolean disconnected = SonarApiEarlyCheckHook.disconnect(
                user,
                SonarApiEarlyCheckHook.Result.vpn("127.0.0.1", "Player"),
                result -> "blocked"
        );

        assertTrue(disconnected);
        assertEquals("blocked", user.reason.content());
    }

    @Test
    void disconnectSupportsStringReasonApiVariants() {
        StringReasonUser user = new StringReasonUser();

        boolean disconnected = SonarApiEarlyCheckHook.disconnect(
                user,
                SonarApiEarlyCheckHook.Result.vpn("127.0.0.1", "Player"),
                result -> "blocked"
        );

        assertTrue(disconnected);
        assertEquals("blocked", user.reason);
    }

    public interface RelocatedComponent {
        static RelocatedComponent text(String content) {
            return new RelocatedTextComponent(content);
        }

        String content();
    }

    public static final class RelocatedTextComponent implements RelocatedComponent {
        private final String content;

        private RelocatedTextComponent(String content) {
            this.content = content;
        }

        @Override
        public String content() {
            return content;
        }
    }

    public static final class RelocatedUser {
        private RelocatedComponent reason;

        public void disconnect(RelocatedComponent reason) {
            this.reason = reason;
        }
    }

    public static final class StringReasonUser {
        private String reason;

        public void disconnect(String reason) {
            this.reason = reason;
        }
    }
}
