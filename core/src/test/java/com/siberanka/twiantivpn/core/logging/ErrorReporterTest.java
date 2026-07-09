package com.siberanka.twiantivpn.core.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ErrorReporterTest {
    @TempDir
    Path tempDir;

    @Test
    void writesStackTraceToErrorLog() throws Exception {
        ErrorReporter reporter = new ErrorReporter();
        reporter.configure(true, tempDir, 64, 60, Logger.getLogger("test"));

        reporter.report("Test context", new IllegalStateException("boom"));

        Path logFile = tempDir.resolve("error.log");
        assertTrue(Files.exists(logFile));
        String content = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
        assertTrue(content.contains("Test context"));
        assertTrue(content.contains("IllegalStateException"));
        assertTrue(content.contains("boom"));
    }

    @Test
    void rotatesWhenLogIsTooLarge() throws Exception {
        ErrorReporter reporter = new ErrorReporter();
        reporter.configure(true, tempDir, 64, 60, Logger.getLogger("test"));
        Path logFile = tempDir.resolve("error.log");
        Files.write(logFile, new byte[70 * 1024]);

        reporter.report("Rotating context", new RuntimeException("rotate"));

        assertTrue(Files.exists(tempDir.resolve("error.log.1")));
        assertTrue(Files.exists(logFile));
        String content = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
        assertTrue(content.contains("Rotating context"));
    }
}
