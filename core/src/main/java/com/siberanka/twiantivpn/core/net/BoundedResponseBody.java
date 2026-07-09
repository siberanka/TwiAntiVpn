package com.siberanka.twiantivpn.core.net;

import okhttp3.ResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class BoundedResponseBody {
    public static final int DEFAULT_MAX_BYTES = 1024 * 1024;

    private BoundedResponseBody() {
    }

    public static String read(ResponseBody body) throws IOException {
        return read(body, DEFAULT_MAX_BYTES);
    }

    public static String read(ResponseBody body, int maxBytes) throws IOException {
        if (body == null) {
            throw new IOException("empty response body");
        }
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        long contentLength = body.contentLength();
        if (contentLength > maxBytes) {
            throw new IOException("response body is too large");
        }

        try (InputStream input = body.byteStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream(
                     contentLength > 0 ? (int) Math.min(contentLength, maxBytes) : 8192
             )) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("response body exceeded the safe size limit");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
