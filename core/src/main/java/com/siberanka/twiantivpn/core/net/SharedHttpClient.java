package com.siberanka.twiantivpn.core.net;

import okhttp3.OkHttpClient;

import java.util.concurrent.TimeUnit;

public final class SharedHttpClient {
    private static final OkHttpClient INSTANCE = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build();

    private SharedHttpClient() {
    }

    public static OkHttpClient get() {
        return INSTANCE;
    }
}
