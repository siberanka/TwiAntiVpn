package com.siberanka.twiantivpn.core.webhook;

import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.net.SharedHttpClient;
import com.google.gson.Gson;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

public class CGWebHookHelper {
    public static CompletableFuture<Void> sendWebHook(String url, String content) {
        return CompletableFuture.runAsync(() -> {
            Gson gson = new Gson();
            String jsonRequest = gson.toJson(new CGWebHookRequest(content));

            RequestBody requestBody = RequestBody.create(jsonRequest, MediaType.get("application/json"));

            Request request = new Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .build();

            try (Response response = SharedHttpClient.get().newCall(request).execute()) {
                if (response.code() != 204) {
                    ConnectionGuard.getLogger().info("WebHook | " + response.message());
                }
            } catch (IOException e) {
                ConnectionGuard.getLogger().info("WebHook | " + e.getMessage());
            }
        });
    }
}
