package com.siberanka.twiantivpn.velocity;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;

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
                ConnectionGuardVelocityPlugin.getInstance().getLogger().error("TwiAntiVpn | " + e.getMessage());
                return;
            }
        }
        try {
            config = YamlDocument.create(configFile, GeneralSettings.builder().setUseDefaults(true).build());
            ensureAdaptiveLoginConfig();
        } catch (IOException e) {
            ConnectionGuardVelocityPlugin.getInstance().getLogger().error("TwiAntiVpn | " + e.getMessage());
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
            ConnectionGuardVelocityPlugin.getInstance().getLogger().error("TwiAntiVpn | " + e.getMessage());
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
            ConnectionGuardVelocityPlugin.getInstance().getLogger().error("TwiAntiVpn | " + e.getMessage());
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
            ConnectionGuardVelocityPlugin.getInstance().getLogger().error("TwiAntiVpn | Could not update language file: " + e.getMessage());
        }
    }

    private void ensureAdaptiveLoginConfig() throws IOException {
        boolean changed = false;
        if (config.get("login-check.adaptive-sonar.enabled") == null) {
            config.set("login-check.adaptive-sonar.enabled", true);
            changed = true;
        }
        if (config.get("login-check.adaptive-sonar.recovery-delay-seconds") == null) {
            config.set("login-check.adaptive-sonar.recovery-delay-seconds", 30);
            changed = true;
        }
        if (changed) {
            config.save();
        }
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
