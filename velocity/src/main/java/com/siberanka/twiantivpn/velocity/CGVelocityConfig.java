package com.siberanka.twiantivpn.velocity;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.settings.general.GeneralSettings;
import com.siberanka.twiantivpn.core.ConnectionGuard;
import com.siberanka.twiantivpn.core.message.LanguageFileUpdater;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

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
        } catch (IOException | RuntimeException e) {
            ConnectionGuard.reportError("Velocity language config load", e);
            try {
                languageConfig = loadBundledLanguage(languageFile.getName());
            } catch (IOException fallbackFailure) {
                ConnectionGuard.reportError("Velocity bundled language load", fallbackFailure);
            }
            if (ConnectionGuard.getLogger() != null) {
                ConnectionGuard.getLogger().warning(languageFile.getName()
                        + " could not be read; it was left untouched and the bundled messages are used until it is fixed.");
            }
            return;
        }
        ensureLanguageConfigComplete();
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
        if (languageConfig == null) {
            return;
        }
        try {
            YamlDocument defaults = loadBundledLanguage(languageFile.getName());
            LanguageFileUpdater.Result result = LanguageFileUpdater.update(
                    new BoostedLanguageDocument(languageConfig),
                    defaults == null ? null : new BoostedLanguageDocument(defaults)
            );
            if (result.isUnreadable()) {
                if (defaults != null) {
                    languageConfig = defaults;
                }
                if (ConnectionGuard.getLogger() != null) {
                    ConnectionGuard.getLogger().warning(languageFile.getName() + " is empty or not valid YAML; it was left untouched and the bundled messages are used until it is fixed.");
                }
                return;
            }
            if (!result.isChanged()) {
                return;
            }
            Files.copy(
                    languageFile.toPath(),
                    new File(languageFile.getAbsolutePath() + ".bak." + System.currentTimeMillis()).toPath()
            );
            languageConfig.save();
            if (ConnectionGuard.getLogger() != null) {
                ConnectionGuard.getLogger().info("Updated " + languageFile.getName()
                        + " without overwriting customized messages: "
                        + result.getAddedPaths().size() + " missing key(s) added, "
                        + result.getMigratedPaths().size() + " legacy command reference(s) migrated.");
            }
        } catch (IOException | RuntimeException e) {
            ConnectionGuard.reportError("Velocity language config update", e);
        }
    }

    private YamlDocument loadBundledLanguage(String fileName) throws IOException {
        InputStream inputStream = ConnectionGuardVelocityPlugin.class.getResourceAsStream("/translation/" + fileName);
        if (inputStream == null) {
            inputStream = ConnectionGuardVelocityPlugin.class.getResourceAsStream("/translation/en.yml");
        }
        if (inputStream == null) {
            return null;
        }
        try (InputStream input = inputStream) {
            return YamlDocument.create(input);
        }
    }

    private static final class BoostedLanguageDocument implements LanguageFileUpdater.Document {
        private final YamlDocument document;

        private BoostedLanguageDocument(YamlDocument document) {
            this.document = document;
        }

        @Override
        public Set<String> leafPaths() {
            Set<String> paths = new LinkedHashSet<>();
            for (String path : document.getRoutesAsStrings(true)) {
                if (!document.isSection(path)) {
                    paths.add(path);
                }
            }
            return paths;
        }

        @Override
        public boolean contains(String path) {
            return document.contains(path);
        }

        @Override
        public Object get(String path) {
            return document.get(path);
        }

        @Override
        public void set(String path, Object value) {
            document.set(path, value);
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
        changed |= setConfigDefault("update-check.enabled", true);
        changed |= setConfigDefault("behavior.vpn.whitelisted-asn.enabled", true);
        changed |= setConfigDefault(
                "behavior.vpn.whitelisted-asn.built-in-countries",
                ConnectionGuard.getDefaultTrustedIspCountries()
        );
        changed |= setConfigDefault("behavior.vpn.whitelisted-asn.asns", new ArrayList<String>());
        changed |= setConfigDefault("behavior.vpn.whitelisted-asn.excluded-asns", new ArrayList<String>());
        changed |= setConfigDefault("behavior.vpn.whitelisted-asn.block-hosting", true);
        changed |= setConfigDefault("behavior.vpn.whitelisted-asn.block-anonymizers", true);
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
}
