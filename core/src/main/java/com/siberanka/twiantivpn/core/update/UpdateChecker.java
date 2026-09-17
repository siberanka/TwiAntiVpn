package com.siberanka.twiantivpn.core.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.siberanka.twiantivpn.core.net.BoundedResponseBody;
import com.siberanka.twiantivpn.core.net.SharedHttpClient;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class UpdateChecker {
    static final String GITHUB_API_URL =
            "https://api.github.com/repos/siberanka/TwiAntiVpn/releases/latest";
    static final String GITLAB_API_URL =
            "https://gitlab.com/api/v4/projects/85741353/releases/permalink/latest";
    private static final String GITHUB_RELEASE_URL =
            "https://github.com/siberanka/TwiAntiVpn/releases/tag/";
    private static final String GITLAB_RELEASE_URL =
            "https://gitlab.com/siberanka/TwiAntiVpn/-/releases/";
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^v?(\\d{4})\\.(\\d{1,2})\\.(\\d{1,2})\\.(\\d+)$");
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final OkHttpClient httpClient;
    private final String githubApiUrl;
    private final String gitlabApiUrl;

    public UpdateChecker() {
        this(SharedHttpClient.get(), GITHUB_API_URL, GITLAB_API_URL);
    }

    UpdateChecker(OkHttpClient httpClient, String githubApiUrl, String gitlabApiUrl) {
        this.httpClient = httpClient;
        this.githubApiUrl = githubApiUrl;
        this.gitlabApiUrl = gitlabApiUrl;
    }

    public CompletableFuture<Optional<UpdateInfo>> check(String currentVersion) {
        return CompletableFuture.supplyAsync(() -> checkNow(currentVersion));
    }

    Optional<UpdateInfo> checkNow(String currentVersion) {
        Optional<Version> current = Version.parse(currentVersion);
        if (!current.isPresent()) {
            return Optional.empty();
        }

        Optional<Release> release = fetchRelease(githubApiUrl, Source.GITHUB);
        if (!release.isPresent()) {
            release = fetchRelease(gitlabApiUrl, Source.GITLAB);
        }
        if (!release.isPresent()) {
            return Optional.empty();
        }

        Optional<Version> latest = Version.parse(release.get().version);
        if (!latest.isPresent() || latest.get().compareTo(current.get()) <= 0) {
            return Optional.empty();
        }
        return Optional.of(new UpdateInfo(
                current.get().normalized,
                latest.get().normalized,
                release.get().source,
                releaseUrl(release.get().source, release.get().version.trim())
        ));
    }

    private Optional<Release> fetchRelease(String url, Source source) {
        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "TwiAntiVpn-UpdateChecker")
                .get()
                .build();
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return Optional.empty();
            }
            JsonElement parsed = JsonParser.parseString(
                    BoundedResponseBody.read(response.body(), MAX_RESPONSE_BYTES)
            );
            if (!parsed.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject object = parsed.getAsJsonObject();
            JsonElement tagName = object.get("tag_name");
            if (tagName == null || !tagName.isJsonPrimitive()) {
                return Optional.empty();
            }
            String version = tagName.getAsString();
            if (!Version.parse(version).isPresent()) {
                return Optional.empty();
            }
            return Optional.of(new Release(version, source));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private String releaseUrl(Source source, String version) {
        return (source == Source.GITHUB ? GITHUB_RELEASE_URL : GITLAB_RELEASE_URL) + version;
    }

    public enum Source {
        GITHUB,
        GITLAB
    }

    public static final class UpdateInfo {
        private final String currentVersion;
        private final String latestVersion;
        private final Source source;
        private final String releaseUrl;

        private UpdateInfo(String currentVersion, String latestVersion, Source source, String releaseUrl) {
            this.currentVersion = currentVersion;
            this.latestVersion = latestVersion;
            this.source = source;
            this.releaseUrl = releaseUrl;
        }

        public String getCurrentVersion() {
            return currentVersion;
        }

        public String getLatestVersion() {
            return latestVersion;
        }

        public Source getSource() {
            return source;
        }

        public String getReleaseUrl() {
            return releaseUrl;
        }
    }

    private static final class Release {
        private final String version;
        private final Source source;

        private Release(String version, Source source) {
            this.version = version;
            this.source = source;
        }
    }

    static final class Version implements Comparable<Version> {
        private final LocalDate date;
        private final long revision;
        private final String normalized;

        private Version(LocalDate date, long revision) {
            this.date = date;
            this.revision = revision;
            this.normalized = String.format(
                    java.util.Locale.ROOT,
                    "%04d.%02d.%02d.%d",
                    date.getYear(),
                    date.getMonthValue(),
                    date.getDayOfMonth(),
                    revision
            );
        }

        private static Optional<Version> parse(String raw) {
            if (raw == null || raw.length() > 64) {
                return Optional.empty();
            }
            Matcher matcher = VERSION_PATTERN.matcher(raw.trim());
            if (!matcher.matches()) {
                return Optional.empty();
            }
            try {
                LocalDate date = LocalDate.of(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3))
                );
                long revision = Long.parseLong(matcher.group(4));
                return Optional.of(new Version(date, revision));
            } catch (DateTimeException | NumberFormatException ignored) {
                return Optional.empty();
            }
        }

        @Override
        public int compareTo(Version other) {
            int dateComparison = date.compareTo(other.date);
            return dateComparison != 0 ? dateComparison : Long.compare(revision, other.revision);
        }
    }
}
