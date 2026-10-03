package com.siberanka.twiantivpn.core.geo;

import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.net.BoundedResponseBody;
import com.siberanka.twiantivpn.core.net.SharedHttpClient;
import com.siberanka.twiantivpn.core.vpn.ProxyCheckVpnProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ProxyCheckGeoProvider implements GeoProvider {
    private String apiKey;
    public ProxyCheckGeoProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            Request request = new Request.Builder()
                    .url(
                            "https://proxycheck.io/v2/"
                            + ipAddress
                            + "?key=" + apiKey
                            + "&asn=1"
                            + "&vpn=1"
                    ).build();

            JsonObject jsonObject;
            try (Response response = SharedHttpClient.get().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return Optional.empty();
                }
                jsonObject = JsonParser.parseString(
                        BoundedResponseBody.read(response.body())
                ).getAsJsonObject();
            } catch (IOException e) {
                ConnectionGuard.reportError("ProxyCheck geo provider request", e);
                return Optional.empty();
            } catch (Exception e) {
                ConnectionGuard.reportError("ProxyCheck geo provider parse", e);
                return Optional.empty();
            }

            String requestStatus = getString(jsonObject, "status");

            switch (requestStatus.toLowerCase()) {
                case "ok":
                    break;
                case "warning":
                    ConnectionGuard.getLogger().info(
                            "ProxyCheck | "
                                    + getString(jsonObject, "message")
                    );
                    break;
                case "denied":
                case "error":
                    ConnectionGuard.getLogger().info(
                            "ProxyCheck | "
                                    + getString(jsonObject, "message")
                    );
                    return Optional.empty();
            }

            if (!jsonObject.has(ipAddress) || !jsonObject.get(ipAddress).isJsonObject()) {
                return Optional.empty();
            }

            JsonObject ipObject = jsonObject.get(ipAddress).getAsJsonObject();
            JsonObject networkObject = ipObject.has("network") && ipObject.get("network").isJsonObject()
                    ? ipObject.get("network").getAsJsonObject()
                    : ipObject;
            String providerName = firstNonEmpty(
                    getString(networkObject, "provider"),
                    getString(networkObject, "organisation"),
                    getString(ipObject, "provider")
            );
            String countryCode = firstNonEmpty(getString(ipObject, "isocode"), getString(ipObject, "country"));
            String asn = getString(networkObject, "asn");
            String networkType = firstNonEmpty(getString(networkObject, "type"), getString(ipObject, "type"));

            return Optional.of(new GeoResult(
                    ipAddress,
                    countryCode,
                    "Unknown",
                    providerName,
                    asn,
                    getString(networkObject, "organisation"),
                    hostingFromType(networkType)
            ));
        });
    }

    private String getString(JsonObject jsonObject, String key) {
        if (jsonObject == null || !jsonObject.has(key) || jsonObject.get(key).isJsonNull()) {
            return "";
        }
        try {
            return jsonObject.get(key).getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    static Boolean hostingFromType(String networkType) {
        if (networkType == null || networkType.trim().isEmpty()) {
            return null;
        }
        String normalized = networkType.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("hosting") || normalized.contains("data center") || normalized.contains("datacenter")) {
            return Boolean.TRUE;
        }
        if (normalized.equals("residential") || normalized.equals("wireless") || normalized.equals("business")) {
            return Boolean.FALSE;
        }
        return null;
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }
}
