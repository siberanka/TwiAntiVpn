package com.siberanka.twiantivpn.core.geo;

import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.vpn.ProxyCheckVpnProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ProxyCheckGeoProvider implements GeoProvider {
    private String apiKey;

    public ProxyCheckGeoProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            OkHttpClient httpClient = new OkHttpClient();

            Request request = new Request.Builder()
                    .url(
                            "http://proxycheck.io/v2/"
                            + ipAddress
                            + "?key=" + apiKey
                            + "&asn=1"
                    ).build();

            Response response;

            try {
                response = httpClient.newCall(request).execute();
            } catch (IOException e) {
                ConnectionGuard.getLogger().info("ProxyCheck Geo | " + e.getMessage());
                return Optional.empty();
            }

            JsonObject jsonObject;
            try {
                jsonObject = JsonParser.parseString(response.body().string()).getAsJsonObject();
            } catch (IOException e) {
                ConnectionGuard.getLogger().info("ProxyCheck Geo | " + e.getMessage());
                return Optional.empty();
            }

            String requestStatus = jsonObject.get("status").getAsString();

            switch (requestStatus.toLowerCase()) {
                case "ok":
                    break;
                case "warning":
                    ConnectionGuard.getLogger().info(
                            "ProxyCheck | "
                                    + jsonObject.get("message").getAsString()
                    );
                    break;
                case "denied":
                case "error":
                    ConnectionGuard.getLogger().info(
                            "ProxyCheck | "
                                    + jsonObject.get("message").getAsString()
                    );
                    return Optional.empty();
            }

            JsonObject ipObject = jsonObject.get(ipAddress).getAsJsonObject();
            String providerName = ipObject.get("provider").getAsString();
            String countryCode = ipObject.get("isocode").getAsString();

            return Optional.of(new GeoResult(ipAddress, countryCode, "Unknown", providerName));
        });
    }
}
