package com.siberanka.twiantivpn.core.vpn.custom;

import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.net.BoundedResponseBody;
import com.siberanka.twiantivpn.core.net.SharedHttpClient;
import com.siberanka.twiantivpn.core.vpn.VpnProvider;
import com.siberanka.twiantivpn.core.vpn.VpnResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.*;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class CustomVpnProvider implements VpnProvider {
    private String requestType;
    private String requestUrl;
    private List<String> requestHeaders;
    private String requestBodyType;
    private String requestBody;
    private String responseType;
    private String isVpnFieldName;
    private String isVpnFieldType;
    private String isVpnString;
    private String vpnProviderFieldName;

    public CustomVpnProvider(
            String requestType,
            String requestUrl,
            List<String> requestHeaders,
            String requestBodyType,
            String requestBody,
            String responseType,
            String isVpnFieldName,
            String isVpnFieldType,
            String isVpnString,
            String vpnProviderFieldName
    ) {
        this.requestType = requestType;
        this.requestUrl = requestUrl;
        this.requestHeaders = requestHeaders;
        this.requestBodyType = requestBodyType;
        this.requestBody = requestBody;
        this.responseType = responseType;
        this.isVpnFieldName = isVpnFieldName;
        this.isVpnFieldType = isVpnFieldType;
        this.isVpnString = isVpnString;
        this.vpnProviderFieldName = vpnProviderFieldName;
    }

    @Override
    public CompletableFuture<Optional<VpnResult>> getVpnResult(String ipAddress) {
        return CompletableFuture.supplyAsync(() -> {
            // Set URL
            if (requestUrl == null || requestUrl.length() > 2048) {
                ConnectionGuard.getLogger().info("Custom Detection Provider | Invalid request URL.");
                return Optional.empty();
            }
            Request.Builder requestBuilder = new Request.Builder()
                    .url(requestUrl.replace("%IP%", ipAddress));

            // Set method and request body
            if (requestType == null) {
                return Optional.empty();
            }

            switch (requestType.toUpperCase()) {
                case "GET":
                    break;
                case "POST":
                    requestBuilder = requestBuilder.post(
                            RequestBody.create(
                                    requestBody == null ? "" : requestBody.replace("%IP%", ipAddress),
                                    MediaType.get(requestBodyType == null ? "application/json" : requestBodyType)
                            )
                    );
                    break;
                default:
                    ConnectionGuard.getLogger().info("Custom Detection Provider | Unknown request type. Please use 'GET' or 'POST'!");
                    return Optional.empty();
            }

            // Set request headers
            if (requestHeaders != null) {
                for (String header : requestHeaders) {
                    if (header == null) {
                        continue;
                    }
                    String[] headerSplit = header.split(":", 2);
                    if (headerSplit.length == 2) {
                        requestBuilder = requestBuilder.addHeader(headerSplit[0], headerSplit[1]);
                    }
                }
            }

            Response response;
            try {
                response = SharedHttpClient.get().newCall(requestBuilder.build()).execute();
            } catch (IOException e) {
                ConnectionGuard.reportError("Custom VPN provider request", e);
                return Optional.empty();
            }

            try {
                if (responseType == null) {
                    return Optional.empty();
                }
                switch (responseType.toLowerCase()) {
                    case "application/json":
                        try {
                            if (response.body() == null) {
                                return Optional.empty();
                            }
                            return readJsonResponse(
                                    ipAddress,
                                    BoundedResponseBody.read(response.body())
                            );
                        } catch (Exception e) {
                            ConnectionGuard.reportError("Custom VPN provider response parse", e);
                            return Optional.empty();
                        }
                    default:
                        ConnectionGuard.getLogger().info("Custom Detection Provider | Unknown response type. Please use 'application/json'!");
                        break;
                }
            } finally {
                response.close();
            }

            return Optional.empty();
        });
    }

    private Optional<VpnResult> readJsonResponse(String ipAddress, String responseBody) {
        JsonElement jsonElement = JsonParser.parseString(responseBody);
        if (isVpnFieldName == null || isVpnFieldType == null) {
            return Optional.empty();
        }

        String[] isVpnTree = isVpnFieldName.replace("%IP%", ipAddress).split("#");

        if (isVpnTree.length == 0)
            isVpnTree = new String[]{ isVpnFieldName.replace("%IP%", ipAddress) };
        JsonObject isVpnObject = jsonElement.getAsJsonObject();
        boolean isVpn = false;

        for (int i = 0; i < isVpnTree.length; i++) {
            if (isVpnTree.length - 1 == i) {
                if (isVpnFieldType.equalsIgnoreCase("STRING")) {
                    String isVpnResult = isVpnObject.get(isVpnTree[i]).getAsString();
                    if (isVpnResult.equalsIgnoreCase(isVpnString)) {
                        isVpn = true;
                        break;
                    }
                }
                if (isVpnFieldType.equalsIgnoreCase("BOOLEAN")) {
                    isVpn = isVpnObject.get(isVpnTree[i]).getAsBoolean();
                    break;
                }
            } else {
                isVpnObject = isVpnObject.getAsJsonObject(isVpnTree[i]);
            }
        }

        if (vpnProviderFieldName == null || vpnProviderFieldName.equalsIgnoreCase("")) {
            return Optional.of(new VpnResult(ipAddress, isVpn));
        }

        String[] vpnProviderNameTree = vpnProviderFieldName.replace("%IP%", ipAddress).split("#");
        if (vpnProviderNameTree.length == 0)
            vpnProviderNameTree = new String[]{ vpnProviderFieldName.replace("%IP%", ipAddress) };
        JsonObject vpnProviderNameObject = jsonElement.getAsJsonObject();
        String vpnProviderName = "";

        for (int i = 0; i < vpnProviderNameTree.length; i++) {
            if (vpnProviderNameTree.length - 1 == i) {
                vpnProviderName = vpnProviderNameObject.get(vpnProviderNameTree[i]).getAsString();
                break;
            } else {
                vpnProviderNameObject = vpnProviderNameObject.get(vpnProviderNameTree[i]).getAsJsonObject();
            }
        }

        // A positive verdict that names the VPN operator is hard anonymizer evidence.
        return Optional.of(new VpnResult(ipAddress, isVpn, Optional.of(vpnProviderName))
                .setAnonymizer(isVpn && vpnProviderName != null && !vpnProviderName.trim().isEmpty()));
    }
}
