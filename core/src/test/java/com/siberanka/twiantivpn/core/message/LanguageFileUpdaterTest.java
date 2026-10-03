package com.siberanka.twiantivpn.core.message;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LanguageFileUpdaterTest {
    @Test
    void addsOnlyMissingKeysAndKeepsCustomizedMessages() {
        MapDocument defaults = new MapDocument()
                .with("messages.prefix", "&bTwiAntiVpn &7|")
                .with("messages.vpn-block", "default vpn block")
                .with("command.whitelist.added", "added %IP%")
                .with("messages.help", Arrays.asList("help 1", "help 2"));
        MapDocument user = new MapDocument()
                .with("messages.prefix", "&cMyServer &8>")
                .with("messages.vpn-block", "custom vpn block");

        LanguageFileUpdater.Result result = LanguageFileUpdater.update(user, defaults);

        assertTrue(result.isChanged());
        assertEquals(Arrays.asList("command.whitelist.added", "messages.help"), result.getAddedPaths());
        assertEquals("&cMyServer &8>", user.get("messages.prefix"));
        assertEquals("custom vpn block", user.get("messages.vpn-block"));
        assertEquals("added %IP%", user.get("command.whitelist.added"));
        assertEquals(Arrays.asList("help 1", "help 2"), user.get("messages.help"));
    }

    @Test
    void completeCustomizedFileIsLeftUntouched() {
        MapDocument defaults = new MapDocument()
                .with("messages.prefix", "default")
                .with("messages.help", Arrays.asList("&f/twiantivpn help"));
        MapDocument user = new MapDocument()
                .with("messages.prefix", "custom")
                .with("messages.help", Arrays.asList("&f/twiantivpn help &7Custom text"))
                .with("messages.extra-user-key", "kept");

        LanguageFileUpdater.Result result = LanguageFileUpdater.update(user, defaults);

        assertFalse(result.isChanged());
        assertEquals("custom", user.get("messages.prefix"));
        assertEquals("kept", user.get("messages.extra-user-key"));
        assertEquals(0, user.writes);
    }

    @Test
    void migratesLegacyCommandNamesInsideCustomizedHelpTexts() {
        MapDocument user = new MapDocument()
                .with("command.unknown-subcommand", "{prefix} &7Use &f/cg help&7 for help.")
                .with("messages.help", Arrays.asList(
                        "{prefix} &7Commands",
                        " &8- &f/connectionguard reload &7My custom reload text",
                        "/cg"
                ));

        LanguageFileUpdater.Result result = LanguageFileUpdater.update(user, null);

        assertEquals(Arrays.asList("command.unknown-subcommand", "messages.help"), result.getMigratedPaths());
        assertEquals("{prefix} &7Use &f/twiantivpn help&7 for help.", user.get("command.unknown-subcommand"));
        assertEquals(Arrays.asList(
                "{prefix} &7Commands",
                " &8- &f/twiantivpn reload &7My custom reload text",
                "/twiantivpn"
        ), user.get("messages.help"));
    }

    @Test
    void legacyMigrationDoesNotRewriteUnrelatedSlashes() {
        String contact = "discord.gg/cgnetwork store.example.net/cg";
        assertSame(contact, LanguageFileUpdater.migrateLegacyCommandName(contact));
        String command = "&f/cgx help";
        assertSame(command, LanguageFileUpdater.migrateLegacyCommandName(command));
        assertEquals("<gray>/twiantivpn</gray>", LanguageFileUpdater.migrateLegacyCommandName("<gray>/cg</gray>"));
        assertEquals("§f/twiantivpn info", LanguageFileUpdater.migrateLegacyCommandName("§f/cg info"));
    }

    @Test
    void emptyOrUnparsableFileIsNeverReplaced() {
        MapDocument defaults = new MapDocument().with("messages.prefix", "default");
        MapDocument unreadable = new MapDocument();

        LanguageFileUpdater.Result result = LanguageFileUpdater.update(unreadable, defaults);

        assertTrue(result.isUnreadable());
        assertFalse(result.isChanged());
        assertEquals(0, unreadable.writes);
    }

    @Test
    void customLanguageWithoutBundledCopyIsCompletedFromDefaults() {
        MapDocument englishDefaults = new MapDocument()
                .with("messages.prefix", "default")
                .with("messages.geo-block", "default geo");
        MapDocument german = new MapDocument().with("messages.prefix", "&bTwiAntiVpn &7| Deutsch");

        LanguageFileUpdater.update(german, englishDefaults);

        assertEquals("&bTwiAntiVpn &7| Deutsch", german.get("messages.prefix"));
        assertEquals("default geo", german.get("messages.geo-block"));
    }

    private static final class MapDocument implements LanguageFileUpdater.Document {
        private final Map<String, Object> values = new LinkedHashMap<>();
        private int writes;

        private MapDocument with(String path, Object value) {
            values.put(path, value);
            return this;
        }

        @Override
        public Set<String> leafPaths() {
            return new LinkedHashSet<>(values.keySet());
        }

        @Override
        public boolean contains(String path) {
            return values.containsKey(path);
        }

        @Override
        public Object get(String path) {
            return values.get(path);
        }

        @Override
        public void set(String path, Object value) {
            writes++;
            values.put(path, value);
        }
    }
}
