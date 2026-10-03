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

public class VpnApiVpnProvider implements VpnProvider {
    private String apiKey;

    public VpnApiVpnProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            Request request = new Request.Builder()
                    .url("https://vpnapi.io/api/" + ipAddress + "?key=" + apiKey)
                    .build();
            JsonObject jsonObject;
            try (Response response = SharedHttpClient.get().newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    ConnectionGuard.getLogger().info("VPNAPI | HTTP " + response.code());
                    return Optional.empty();
                }
                jsonObject = JsonParser.parseString(
                        BoundedResponseBody.read(response.body())
                ).getAsJsonObject();
            } catch (Exception e) {
                ConnectionGuard.reportError("VPNAPI provider request", e);
                return Optional.empty();
            }

            try {
                boolean isVpn = jsonObject.get("security").getAsJsonObject().get("vpn").getAsBoolean();
                boolean isProxy = jsonObject.get("security").getAsJsonObject().get("proxy").getAsBoolean();
                boolean isTor = jsonObject.get("security").getAsJsonObject().get("tor").getAsBoolean();
                boolean isRelay = jsonObject.get("security").getAsJsonObject().get("relay").getAsBoolean();

                if (isVpn || isProxy || isTor || isRelay) {
                    return Optional.of(new VpnResult(ipAddress, true).setAnonymizer(isTor || isRelay));
                } else {
                    return Optional.of(new VpnResult(ipAddress, false));
                }
            } catch (Exception e) {
                ConnectionGuard.reportError("VPNAPI response parse", e);
                return Optional.empty();
            }
        });
    }
}
