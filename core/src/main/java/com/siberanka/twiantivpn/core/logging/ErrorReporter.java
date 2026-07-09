package com.siberanka.twiantivpn.core.logging;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class ErrorReporter {
    private static final long DEFAULT_MAX_SIZE_BYTES = 2L * 1024L * 1024L;
    private static final long MIN_MAX_SIZE_BYTES = 64L * 1024L;
    private static final long MAX_MAX_SIZE_BYTES = 64L * 1024L * 1024L;
    private static final long DEFAULT_CONSOLE_COOLDOWN_MILLIS = TimeUnit.SECONDS.toMillis(60);

    private final Object lock = new Object();
    private final Map<String, Long> lastConsoleNotice = new HashMap<>();

    private volatile boolean enabled = true;
    private volatile Path logFile;
    private volatile long maxSizeBytes = DEFAULT_MAX_SIZE_BYTES;
    private volatile long consoleCooldownMillis = DEFAULT_CONSOLE_COOLDOWN_MILLIS;
    private volatile Logger logger;

    public void configure(boolean enabled,
                          Path dataDirectory,
                          int maxSizeKb,
                          int consoleCooldownSeconds,
                          Logger logger) {
        this.enabled = enabled;
        this.logger = logger;
        this.logFile = dataDirectory == null ? null : dataDirectory.resolve("error.log");
        this.maxSizeBytes = boundMaxSize(maxSizeKb);
        this.consoleCooldownMillis = TimeUnit.SECONDS.toMillis(
                Math.max(5, Math.min(3600, consoleCooldownSeconds))
        );
        synchronized (lock) {
            lastConsoleNotice.clear();
        }
    }

    public void report(String context, Throwable throwable) {
        String safeContext = sanitizeContext(context);
        if (!enabled || logFile == null) {
            logConsole(safeContext + " failed: " + safeMessage(throwable));
            return;
        }

        boolean written = writeError(safeContext, throwable);
        if (written) {
            logConsoleThrottled(
                    safeContext,
                    safeContext + " failed. Details were written to error.log."
            );
        } else {
            logConsole(safeContext + " failed. Could not write details to error.log.");
        }
    }

    private boolean writeError(String context, Throwable throwable) {
        synchronized (lock) {
            try {
                Path file = logFile;
                Files.createDirectories(file.getParent());
                rotateIfNeeded(file);
                Files.write(
                        file,
                        formatEntry(context, throwable).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
                return true;
            } catch (IOException ignored) {
                return false;
            }
        }
    }

    private void rotateIfNeeded(Path file) throws IOException {
        if (Files.exists(file) && Files.size(file) >= maxSizeBytes) {
            Path rotated = file.resolveSibling(file.getFileName().toString() + ".1");
            Files.move(file, rotated, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String formatEntry(String context, Throwable throwable) {
        StringBuilder builder = new StringBuilder(512);
        builder.append('[').append(Instant.now()).append("] ").append(context).append(System.lineSeparator());
        if (throwable == null) {
            builder.append("No throwable was provided.").append(System.lineSeparator());
        } else {
            StringWriter stringWriter = new StringWriter();
            throwable.printStackTrace(new PrintWriter(stringWriter));
            builder.append(stringWriter);
        }
        builder.append(System.lineSeparator());
        return builder.toString();
    }

    private void logConsoleThrottled(String key, String message) {
        long now = System.currentTimeMillis();
        synchronized (lock) {
            Long previous = lastConsoleNotice.get(key);
            if (previous != null && now - previous < consoleCooldownMillis) {
                return;
            }
            if (lastConsoleNotice.size() > 256) {
                lastConsoleNotice.clear();
            }
            lastConsoleNotice.put(key, now);
        }
        logConsole(message);
    }

    private void logConsole(String message) {
        Logger currentLogger = logger;
        if (currentLogger != null) {
            currentLogger.warning("TwiAntiVpn | " + message);
        }
    }

    private long boundMaxSize(int maxSizeKb) {
        if (maxSizeKb <= 0) {
            return DEFAULT_MAX_SIZE_BYTES;
        }
        long bytes = maxSizeKb * 1024L;
        return Math.max(MIN_MAX_SIZE_BYTES, Math.min(MAX_MAX_SIZE_BYTES, bytes));
    }

    private String sanitizeContext(String context) {
        if (context == null || context.trim().isEmpty()) {
            return "Unexpected plugin error";
        }
        String normalized = context.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() > 120 ? normalized.substring(0, 120) : normalized;
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null || throwable.getMessage() == null || throwable.getMessage().trim().isEmpty()) {
            return "unknown error";
        }
        String message = throwable.getMessage().replaceAll("[\\r\\n\\t]+", " ").trim();
        return message.length() > 160 ? message.substring(0, 160) : message;
    }
}
