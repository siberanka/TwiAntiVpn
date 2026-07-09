package com.siberanka.twiantivpn.core.vpn;

import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.net.BoundedResponseBody;
import com.siberanka.twiantivpn.core.net.SharedHttpClient;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class IpHubVpnProvider implements VpnProvider {
    private String apiKey;

    public IpHubVpnProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            Request request = new Request.Builder()
                    .url("https://v2.api.iphub.info/ip/" + ipAddress)
                    .header("X-Key", apiKey)
                    .build();
            int blockLevel;
            JsonObject jsonObject;
            try (Response response = SharedHttpClient.get().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    ConnectionGuard.getLogger().info("IP-Hub | API returned with status code " + response.code());
                    return Optional.empty();
                }
                jsonObject = JsonParser.parseString(
                        BoundedResponseBody.read(response.body())
                ).getAsJsonObject();
            } catch (Exception e) {
                ConnectionGuard.reportError("IP-Hub VPN provider request", e);
                return Optional.empty();
            }

            blockLevel = jsonObject.get("block").getAsInt();

            if (blockLevel == 1) {
                return Optional.of(new VpnResult(ipAddress, true));
            } else {
                return Optional.of(new VpnResult(ipAddress, false));
            }
        });
    }
}
