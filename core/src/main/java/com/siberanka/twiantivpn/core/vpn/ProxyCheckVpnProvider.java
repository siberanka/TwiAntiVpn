package com.siberanka.twiantivpn.core.vpn;

import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.net.BoundedResponseBody;
import com.siberanka.twiantivpn.core.net.SharedHttpClient;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class ProxyCheckVpnProvider implements VpnProvider {
    private String apiKey;

    public ProxyCheckVpnProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            Request request = new Request.Builder()
                    .url(
                            "https://proxycheck.io/v2/"
                            + ipAddress
                            + "?key=" + apiKey
                            + "&vpn=1"
                    ).build();

            JsonObject jsonObject;
            try (Response response = SharedHttpClient.get().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    ConnectionGuard.getLogger().info("ProxyCheck | HTTP " + response.code());
                    return Optional.empty();
                }
                jsonObject = JsonParser.parseString(
                        BoundedResponseBody.read(response.body())
                ).getAsJsonObject();
            } catch (Exception e) {
                ConnectionGuard.reportError("ProxyCheck VPN provider request", e);
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
                    ConnectionGuard.getLogger().info(
                            "ProxyCheck | "
                            + jsonObject.get("message").getAsString()
                    );
                    return Optional.empty();
                case "error":
                    ConnectionGuard.getLogger().info(
                            "ProxyCheck | "
                            + jsonObject.get("message").getAsString()
                    );
                    return Optional.empty();
            }

            JsonObject ipObject = jsonObject.get(ipAddress).getAsJsonObject();
            String isVpn = ipObject.get("proxy").getAsString();

            if (isVpn.equalsIgnoreCase("yes")) {
                return Optional.of(new VpnResult(ipAddress, true).setAnonymizer(isHardAnonymizer(ipObject)));
            } else {
                return Optional.of(new VpnResult(ipAddress, false));
            }
        });
    }

    /**
     * Tor exits and ranges attributed to a named VPN operator are hard anonymizer evidence.
     */
    static boolean isHardAnonymizer(JsonObject ipObject) {
        if (ipObject == null) {
            return false;
        }
        JsonElement type = ipObject.get("type");
        if (type != null && type.isJsonPrimitive() && type.getAsString().trim().equalsIgnoreCase("TOR")) {
            return true;
        }
        JsonElement operator = ipObject.get("operator");
        if (operator == null || operator.isJsonNull()) {
            return false;
        }
        if (operator.isJsonObject()) {
            return operator.getAsJsonObject().size() > 0;
        }
        return operator.isJsonPrimitive() && !operator.getAsString().trim().isEmpty();
    }
}
