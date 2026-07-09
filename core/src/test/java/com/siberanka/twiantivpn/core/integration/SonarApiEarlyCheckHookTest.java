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

    @Test
    void disconnectMovesModernSonarEncoderToConfigurationState() {
        StatefulUser user = new StatefulUser();

        assertTrue(SonarApiEarlyCheckHook.disconnect(
                user,
                SonarApiEarlyCheckHook.Result.vpn("127.0.0.1", "Player"),
                result -> "blocked"
        ));

        assertEquals(FakeRegistry.CONFIG, user.channel.pipeline.encoder.registry);
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

    public enum FakeRegistry {
        LOGIN,
        CONFIG
    }

    public static final class FakeEncoder {
        private FakeRegistry registry = FakeRegistry.LOGIN;

        public FakeRegistry getPacketRegistry() {
            return registry;
        }

        public void updateRegistry(FakeRegistry registry) {
            this.registry = registry;
        }
    }

    public static final class FakePipeline {
        private final FakeEncoder encoder = new FakeEncoder();

        public Object get(String name) {
            return "sonar-packet-encoder".equals(name) ? encoder : null;
        }
    }

    public static final class FakeChannel {
        private final FakePipeline pipeline = new FakePipeline();

        public FakePipeline pipeline() {
            return pipeline;
        }
    }

    public static final class StatefulUser {
        private final FakeChannel channel = new FakeChannel();
        private String reason;

        public FakeChannel channel() {
            return channel;
        }

        public void disconnect(String reason) {
            this.reason = reason;
        }
    }
}
