package com.siberanka.twiantivpn.core.message;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Non-destructive language file migration.
 *
 * <p>Older releases replaced the whole language file with the bundled copy whenever a newly
 * introduced key was missing, which silently discarded every customized message. This updater
 * only adds keys that are missing from the user's file and rewrites legacy command names in the
 * help texts; values the user already has are never touched.</p>
 */
public final class LanguageFileUpdater {
    private static final List<String> LEGACY_COMMAND_PATHS = Collections.unmodifiableList(Arrays.asList(
            "command.unknown-subcommand",
            "messages.help"
    ));
    private static final Pattern LEGACY_COMMAND_PATTERN = Pattern.compile(
            "(^|[\\s>\"'(\\[]|[&§][0-9a-fk-orA-FK-OR])/(?:connectionguard|cg)\\b"
    );

    private LanguageFileUpdater() {
    }

    /**
     * Platform neutral view of a YAML document.
     */
    public interface Document {
        /**
         * Every path that holds a value (not a section), using '.' as separator.
         */
        Set<String> leafPaths();

        boolean contains(String path);

        Object get(String path);

        void set(String path, Object value);
    }

    public static Result update(Document userDocument, Document defaults) {
        List<String> addedPaths = new ArrayList<>();
        List<String> migratedPaths = new ArrayList<>();
        if (userDocument == null) {
            return new Result(addedPaths, migratedPaths, false);
        }
        if (userDocument.leafPaths().isEmpty()) {
            // Empty or unparsable file: never replace it, the caller falls back to the defaults in memory.
            return new Result(addedPaths, migratedPaths, true);
        }
        if (defaults != null) {
            for (String path : defaults.leafPaths()) {
                if (path == null || path.isEmpty() || userDocument.contains(path)) {
                    continue;
                }
                Object value = defaults.get(path);
                if (value != null) {
                    userDocument.set(path, copyValue(value));
                    addedPaths.add(path);
                }
            }
        }
        for (String path : LEGACY_COMMAND_PATHS) {
            if (!userDocument.contains(path)) {
                continue;
            }
            Object current = userDocument.get(path);
            Object migrated = migrateLegacyCommandNames(current);
            if (migrated != current) {
                userDocument.set(path, migrated);
                migratedPaths.add(path);
            }
        }
        return new Result(addedPaths, migratedPaths, false);
    }

    /**
     * Rewrites /connectionguard and /cg command references to /twiantivpn. Returns the same
     * instance when nothing changed.
     */
    static Object migrateLegacyCommandNames(Object value) {
        if (value instanceof String) {
            String migrated = migrateLegacyCommandName((String) value);
            return migrated.equals(value) ? value : migrated;
        }
        if (value instanceof List) {
            List<?> values = (List<?>) value;
            List<Object> migrated = new ArrayList<>(values.size());
            boolean changed = false;
            for (Object entry : values) {
                Object migratedEntry = migrateLegacyCommandNames(entry);
                changed |= migratedEntry != entry;
                migrated.add(migratedEntry);
            }
            return changed ? migrated : value;
        }
        return value;
    }

    static String migrateLegacyCommandName(String value) {
        if (value == null || value.indexOf('/') < 0) {
            return value;
        }
        Matcher matcher = LEGACY_COMMAND_PATTERN.matcher(value);
        if (!matcher.find()) {
            return value;
        }
        return matcher.replaceAll("$1/twiantivpn");
    }

    private static Object copyValue(Object value) {
        if (value instanceof List) {
            return new ArrayList<Object>((List<?>) value);
        }
        return value;
    }

    public static final class Result {
        private final List<String> addedPaths;
        private final List<String> migratedPaths;
        private final boolean unreadable;

        private Result(List<String> addedPaths, List<String> migratedPaths, boolean unreadable) {
            this.addedPaths = Collections.unmodifiableList(addedPaths);
            this.migratedPaths = Collections.unmodifiableList(migratedPaths);
            this.unreadable = unreadable;
        }

        /**
         * True when the user's file had no readable keys (empty or invalid YAML). It is left untouched.
         */
        public boolean isUnreadable() {
            return unreadable;
        }

        public boolean isChanged() {
            return !addedPaths.isEmpty() || !migratedPaths.isEmpty();
        }

        public List<String> getAddedPaths() {
            return addedPaths;
        }

        public List<String> getMigratedPaths() {
            return migratedPaths;
        }
    }
}
