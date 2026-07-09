package com.siberanka.twiantivpn.velocity;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import com.siberanka.twiantivpn.core.ConnectionGuard;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class CGVelocityConfig {
    private File configFile;
    private File languageFile;
    private YamlDocument config;
    private YamlDocument languageConfig;
    private Path dataDirectory;

    public CGVelocityConfig(Path dataDirectory) {
        this.dataDirectory = dataDirectory;
    }

    public void load() {
        File translationFolder = dataDirectory.resolve("translation").toFile();
        if (!translationFolder.exists()) {
            translationFolder.mkdirs();
        }
        saveLanguageResource("en.yml", translationFolder);
        saveLanguageResource("tr.yml", translationFolder);
        saveLanguageResource("az.yml", translationFolder);
        saveLanguageResource("es.yml", translationFolder);
        configFile = new File(dataDirectory.toFile(), "config.yml");
        if (!configFile.exists()) {
            try {
                InputStream in = ConnectionGuardVelocityPlugin.class.getResourceAsStream("/config.yml");
                Files.copy(in, configFile.toPath());
            } catch (IOException e) {
                ConnectionGuard.reportError("Velocity config copy", e);
                return;
            }
        }
        try {
            config = YamlDocument.create(configFile, GeneralSettings.builder().setUseDefaults(true).build());
            ensureAdaptiveLoginConfig();
        } catch (IOException e) {
            ConnectionGuard.reportError("Velocity config load", e);
            return;
        }

        String selectedLanguageFileName = config.getString("message-language") + ".yml";
        languageFile = new File(dataDirectory.resolve("translation").toFile(), selectedLanguageFileName);
        if (!languageFile.exists()) {
            languageFile = new File(dataDirectory.resolve("translation").toFile(), "en.yml");
        }
        try {
            languageConfig = YamlDocument.create(languageFile, GeneralSettings.builder().setUseDefaults(true).build());
            ensureLanguageConfigComplete();
        } catch (IOException e) {
            ConnectionGuard.reportError("Velocity language config load", e);
            return;
        }
    }

    public YamlDocument getLanguageConfig() {
        return languageConfig;
    }

    public YamlDocument getConfig() {
        return config;
    }

    private void saveLanguageResource(String fileName, File translationFolder) {
        File file = new File(translationFolder, fileName);
        if (file.exists()) {
            return;
        }
        try (InputStream in = ConnectionGuardVelocityPlugin.class.getResourceAsStream("/translation/" + fileName)) {
            if (in != null) {
                Files.copy(in, file.toPath());
            }
        } catch (IOException e) {
            ConnectionGuard.reportError("Velocity language resource save", e);
        }
    }

    private void ensureLanguageConfigComplete() {
        if (languageConfig != null
                && languageConfig.contains("messages.username-block")
                && languageConfig.contains("messages.isp-block")
                && languageConfig.contains("messages.prefix")
                && languageConfig.contains("messages.kick-prefix")
                && languageConfig.contains("messages.kick-contact")
                && languageConfig.contains("messages.adaptive-sonar-attack-log")
                && languageConfig.contains("messages.adaptive-sonar-recovery-log")
                && languageConfig.contains("messages.adaptive-sonar-normal-log")
                && languageConfig.contains("messages.pre-sonar-check-failed")
                && languageConfig.contains("command.test.connection-result")
                && languageConfigUsesCurrentCommandName()) {
            return;
        }
        try {
            Files.copy(languageFile.toPath(), new File(languageFile.getAbsolutePath() + ".bak." + System.currentTimeMillis()).toPath());
            String resourceName = "/translation/" + languageFile.getName();
            InputStream inputStream = ConnectionGuardVelocityPlugin.class.getResourceAsStream(resourceName);
            if (inputStream == null) {
                inputStream = ConnectionGuardVelocityPlugin.class.getResourceAsStream("/translation/en.yml");
            }
            if (inputStream != null) {
                try (InputStream input = inputStream) {
                    Files.copy(input, languageFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                languageConfig = YamlDocument.create(languageFile, GeneralSettings.builder().setUseDefaults(true).build());
            }
        } catch (IOException e) {
            ConnectionGuard.reportError("Velocity language config update", e);
        }
    }

    private void ensureAdaptiveLoginConfig() throws IOException {
        boolean changed = false;
        changed |= setConfigDefault("login-check.adaptive-sonar.enabled", true);
        changed |= setConfigDefault("login-check.adaptive-sonar.recovery-delay-seconds", 30);
        changed |= setConfigDefault("login-check.adaptive-sonar.pre-sonar-check-timeout-seconds", 6);
        changed |= setConfigDefault("login-check.adaptive-sonar.pre-sonar-block-spike.enabled", true);
        changed |= setConfigDefault("login-check.adaptive-sonar.pre-sonar-block-spike.count-blocks-within-seconds", 60);
        changed |= setConfigDefault("login-check.adaptive-sonar.pre-sonar-block-spike.trigger-after-blocked-connections", 15);
        changed |= setConfigDefault("login-check.adaptive-sonar.before-sonar.username-filter", true);
        changed |= setConfigDefault("login-check.adaptive-sonar.before-sonar.proxy-blocklist", true);
        changed |= setConfigDefault("login-check.adaptive-sonar.before-sonar.vpn-providers.proxycheck", false);
        changed |= setConfigDefault("login-check.adaptive-sonar.before-sonar.vpn-providers.ip-api", false);
        changed |= setConfigDefault("login-check.adaptive-sonar.before-sonar.vpn-providers.iphub", false);
        changed |= setConfigDefault("login-check.adaptive-sonar.before-sonar.vpn-providers.vpnapi", false);
        changed |= setConfigDefault("login-check.adaptive-sonar.before-sonar.vpn-providers.custom", false);
        changed |= setConfigDefault("login-check.adaptive-sonar.before-sonar.geo-block", false);
        changed |= setConfigDefault("login-check.adaptive-sonar.before-sonar.isp-block", false);
        changed |= setConfigDefault("proxy-blocklist.source-delay-millis", 250);
        changed |= setConfigDefault("security.action-cooldown-seconds", 5);
        changed |= setConfigDefault("security.error-log.enabled", true);
        changed |= setConfigDefault("security.error-log.max-size-kb", 2048);
        changed |= setConfigDefault("security.error-log.console-notice-cooldown-seconds", 60);
        if (changed) {
            config.save();
        }
    }

    private boolean setConfigDefault(String path, Object value) {
        if (config.get(path) != null) {
            return false;
        }
        config.set(path, value);
        return true;
    }

    private boolean languageConfigUsesCurrentCommandName() {
        String unknownSubcommand = languageConfig.getString("command.unknown-subcommand");
        if (usesLegacyCommandName(unknownSubcommand)) {
            return false;
        }
        for (String line : languageConfig.getStringList("messages.help")) {
            if (usesLegacyCommandName(line)) {
                return false;
            }
        }
        return true;
    }

    private boolean usesLegacyCommandName(String value) {
        return value != null && (value.contains("/connectionguard") || value.contains("/cg"));
    }
}
