package com.siberanka.twiantivpn.core.update;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerTest {
    @Test
    void usesGithubWhenItsLatestReleaseIsReachable() {
        AtomicInteger calls = new AtomicInteger();
        UpdateChecker checker = checker(chain -> {
            calls.incrementAndGet();
            return response(chain.request(), 200, "{\"tag_name\":\"2026.09.17.2\"}");
        });

        Optional<UpdateChecker.UpdateInfo> result = checker.checkNow("2026.09.17.1");

        assertTrue(result.isPresent());
        assertEquals(UpdateChecker.Source.GITHUB, result.get().getSource());
        assertEquals("2026.09.17.2", result.get().getLatestVersion());
        assertEquals(1, calls.get());
    }

    @Test
    void fallsBackToGitlabWhenGithubIsUnavailable() {
        AtomicInteger calls = new AtomicInteger();
        UpdateChecker checker = checker(chain -> {
            int call = calls.incrementAndGet();
            return call == 1
                    ? response(chain.request(), 503, "unavailable")
                    : response(chain.request(), 200, "{\"tag_name\":\"2026.09.18.1\"}");
        });

        Optional<UpdateChecker.UpdateInfo> result = checker.checkNow("2026.09.17.1");

        assertTrue(result.isPresent());
        assertEquals(UpdateChecker.Source.GITLAB, result.get().getSource());
        assertEquals(
                "https://gitlab.com/siberanka/TwiAntiVpn/-/releases/2026.09.18.1",
                result.get().getReleaseUrl()
        );
        assertEquals(2, calls.get());
    }

    @Test
    void doesNotConsultGitlabAfterAValidGithubResponse() {
        AtomicInteger calls = new AtomicInteger();
        UpdateChecker checker = checker(chain -> {
            calls.incrementAndGet();
            return response(chain.request(), 200, "{\"tag_name\":\"2026.09.17.1\"}");
        });

        assertFalse(checker.checkNow("2026.09.17.1").isPresent());
        assertEquals(1, calls.get());
    }

    @Test
    void rejectsMalformedVersionsAndFallsBackSafely() {
        AtomicInteger calls = new AtomicInteger();
        UpdateChecker checker = checker(chain -> {
            int call = calls.incrementAndGet();
            return call == 1
                    ? response(chain.request(), 200, "{\"tag_name\":\"../../malicious\"}")
                    : response(chain.request(), 200, "{\"tag_name\":\"2026.09.17.3\"}");
        });

        Optional<UpdateChecker.UpdateInfo> result = checker.checkNow("v2026.09.17.1");

        assertTrue(result.isPresent());
        assertEquals(UpdateChecker.Source.GITLAB, result.get().getSource());
        assertEquals(2, calls.get());
    }

    @Test
    void invalidCurrentVersionDoesNotMakeNetworkRequests() {
        AtomicInteger calls = new AtomicInteger();
        UpdateChecker checker = checker(chain -> {
            calls.incrementAndGet();
            return response(chain.request(), 200, "{\"tag_name\":\"2026.09.17.3\"}");
        });

        assertFalse(checker.checkNow("development-build").isPresent());
        assertEquals(0, calls.get());
    }

    @Test
    void comparesRevisionAsANumber() {
        UpdateChecker checker = checker(chain ->
                response(chain.request(), 200, "{\"tag_name\":\"2026.09.17.10\"}"));

        Optional<UpdateChecker.UpdateInfo> result = checker.checkNow("2026.09.17.2");

        assertTrue(result.isPresent());
        assertEquals("2026.09.17.10", result.get().getLatestVersion());
    }

    @Test
    void preservesValidatedVersionPrefixInReleaseUrl() {
        UpdateChecker checker = checker(chain ->
                response(chain.request(), 200, "{\"tag_name\":\"v2026.09.18.1\"}"));

        Optional<UpdateChecker.UpdateInfo> result = checker.checkNow("2026.09.17.1");

        assertTrue(result.isPresent());
        assertEquals("2026.09.18.1", result.get().getLatestVersion());
        assertEquals(
                "https://github.com/siberanka/TwiAntiVpn/releases/tag/v2026.09.18.1",
                result.get().getReleaseUrl()
        );
    }

    @Test
    void rejectsInvalidCalendarDateAndUsesFallback() {
        AtomicInteger calls = new AtomicInteger();
        UpdateChecker checker = checker(chain -> {
            int call = calls.incrementAndGet();
            return call == 1
                    ? response(chain.request(), 200, "{\"tag_name\":\"2026.02.30.1\"}")
                    : response(chain.request(), 200, "{\"tag_name\":\"2026.09.18.1\"}");
        });

        Optional<UpdateChecker.UpdateInfo> result = checker.checkNow("2026.09.17.1");

        assertTrue(result.isPresent());
        assertEquals(UpdateChecker.Source.GITLAB, result.get().getSource());
        assertEquals(2, calls.get());
    }

    @Test
    void rejectsOversizedMetadataAndUsesFallback() {
        AtomicInteger calls = new AtomicInteger();
        UpdateChecker checker = checker(chain -> {
            int call = calls.incrementAndGet();
            return call == 1
                    ? response(chain.request(), 200, "{\"padding\":\"" + repeat('x', 70_000) + "\"}")
                    : response(chain.request(), 200, "{\"tag_name\":\"2026.09.18.1\"}");
        });

        Optional<UpdateChecker.UpdateInfo> result = checker.checkNow("2026.09.17.1");

        assertTrue(result.isPresent());
        assertEquals(UpdateChecker.Source.GITLAB, result.get().getSource());
        assertEquals(2, calls.get());
    }

    private UpdateChecker checker(Interceptor interceptor) {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(interceptor)
                .build();
        return new UpdateChecker(
                client,
                "https://primary.invalid/latest",
                "https://fallback.invalid/latest"
        );
    }

    private Response response(Request request, int code, String body) throws IOException {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(code >= 200 && code < 300 ? "OK" : "Unavailable")
                .body(ResponseBody.create(body, MediaType.parse("application/json")))
                .build();
    }

    private String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            result.append(value);
        }
        return result.toString();
    }
}
