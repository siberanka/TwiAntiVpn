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

public class IpApiVpnProvider implements VpnProvider {
    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            Request request = new Request.Builder()
                    .url("http://ip-api.com/json/" + ipAddress + "?fields=proxy")
                    .build();
            String message;
            boolean isProxy = false;
            JsonObject jsonObject;
            try (Response response = SharedHttpClient.get().newCall(request).execute()) {
                jsonObject = JsonParser.parseString(
                        BoundedResponseBody.read(response.body())
                ).getAsJsonObject();
                if (response.code() != 200) {
                    message = jsonObject.has("message")
                            ? jsonObject.get("message").getAsString()
                            : "HTTP " + response.code();
                    ConnectionGuard.getLogger().info("IP-API | " + message);
                    return Optional.empty();
                }
            } catch (Exception e) {
                ConnectionGuard.reportError("IP-API VPN provider request", e);
                return Optional.empty();
            }

            JsonElement isProxyElement = jsonObject.get("proxy");

            if (isProxyElement == null) {
                ConnectionGuard.getLogger().info("IP-API | There is no 'proxy'-Element in the response of IP-API.");
            } else {
                isProxy = jsonObject.get("proxy").getAsBoolean();
            }

            if (isProxy) {
                return Optional.of(new VpnResult(ipAddress, true));
            } else {
                return Optional.of(new VpnResult(ipAddress, false));
            }
        });
    }
}
