package com.siberanka.twiantivpn.core.geo;

import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.net.BoundedResponseBody;
import com.siberanka.twiantivpn.core.net.SharedHttpClient;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class IpApiGeoProvider implements GeoProvider {
    @Override
    public CompletableFuture<Optional<GeoResult>> getGeoResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            Request request = new Request.Builder()
                    .url("http://ip-api.com/json/" + ipAddress + "?fields=status,message,countryCode,city,isp,as,asname,org,hosting")
                    .build();

            String status;
            String message;
            String countryCode;
            String cityName;
            String ispName;
            String asn;
            String organization;
            JsonObject jsonObject;
            try (Response response = SharedHttpClient.get().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return Optional.empty();
                }
                jsonObject = JsonParser.parseString(
                        BoundedResponseBody.read(response.body())
                ).getAsJsonObject();
            } catch (Exception e) {
                ConnectionGuard.reportError("IP-API geo provider request", e);
                return Optional.empty();
            }

            status = getString(jsonObject, "status");

            if (status.equalsIgnoreCase("fail")) {
                message = getString(jsonObject, "message");
                ConnectionGuard.getLogger().info("IP-API | " + message);
                return Optional.empty();
            }

            countryCode = getString(jsonObject, "countryCode");
            cityName = getString(jsonObject, "city");
            ispName = getString(jsonObject, "isp");
            asn = getString(jsonObject, "as");
            organization = getString(jsonObject, "org");
            if (organization.isEmpty()) {
                organization = getString(jsonObject, "asname");
            }

            return Optional.of(
                    new GeoResult(
                            ipAddress,
                            countryCode,
                            cityName,
                            ispName,
                            asn,
                            organization,
                            getBoolean(jsonObject, "hosting")
                    )
            );
        });
    }

    private Boolean getBoolean(JsonObject jsonObject, String key) {
        if (jsonObject == null || !jsonObject.has(key) || jsonObject.get(key).isJsonNull()) {
            return null;
        }
        try {
            return jsonObject.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return null;
        }
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
}
