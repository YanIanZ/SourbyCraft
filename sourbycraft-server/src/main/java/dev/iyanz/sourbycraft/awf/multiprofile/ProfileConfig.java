package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Early reader for {@code aurora.awf.multiprofile.*}; same precedence as the other AWF readers. */
public final class ProfileConfig {

    private ProfileConfig() {}

    public static ProfileSettings readEarly(final Path auroraFile, final Path unifiedFile) {
        final Map<String, Object> values = new HashMap<>();
        for (final Path file : List.of(unifiedFile, auroraFile)) {
            final Path source = dev.iyanz.sourbycraft.config.ConfigSources.existing(file);
            if (source == null) continue;
            try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                final var config = dev.iyanz.sourbycraft.config.ConfigSources.parse(source, reader);
                for (final String key : ProfileSettings.KEYS) {
                    final Object value = config.get(key);
                    if (value != null) values.put(key, value);
                }
            } catch (final Exception unreadable) {
                SourbyLogger.warn("AWF MultiProfile could not read "
                    + dev.iyanz.sourbycraft.config.ConfigParseErrors.describe(source, unreadable)
                    + "; MultiProfile stays disabled");
                return ProfileSettings.DEFAULT;
            }
        }
        final ProfileSettings.Parsed parsed = ProfileSettings.parse(values);
        for (final String key : parsed.invalidKeys()) {
            SourbyLogger.warn("Aurora config key '" + key + "' is invalid; " + fallback(key, parsed.settings()));
        }
        final ProfileSettings.Migration migration = parsed.settings().migration();
        if (migration.mode() != ProfileSettings.MigrationMode.OFF) {
            SourbyLogger.warn("AWF MultiProfile: " + ProfileSettings.MIGRATION_MODE_KEY + " is " + migration.mode().id()
                + (migration.applies()
                    ? "; a pre-Build-51 (format 1) profile store is backed up to '" + migration.backupDir()
                        + "' and then converted to format 2 at startup. This is one way: builds older than Build 51 refuse a format-2 store"
                        + " (rollback = restore the backup)"
                    : "; a pre-Build-51 (format 1) profile store is only inspected and reported, nothing is written")
                + (parsed.settings().enabled() ? "" : " (ignored: aurora.awf.multiprofile.enabled is false)"));
        }
        return parsed.settings();
    }

    /** What the server does instead of the invalid value, for the WARN. */
    private static String fallback(final String key, final ProfileSettings settings) {
        return switch (key) {
            case ProfileSettings.TYPES_ALLOWED_KEY -> "AWF MultiProfile allows "
                + String.join(", ", ProfileSettings.typeIds(settings.types().allowed()));
            case ProfileSettings.TYPES_DEFAULT_KEY -> "AWF MultiProfile creates '"
                + settings.types().defaultType().id() + "' profiles by default (the default must be in "
                + ProfileSettings.TYPES_ALLOWED_KEY + ")";
            case ProfileSettings.MIGRATION_MODE_KEY -> "AWF MultiProfile uses migration.mode "
                + settings.migration().mode().id() + " (apply needs a valid " + ProfileSettings.MIGRATION_BACKUP_DIR_KEY + ")";
            case ProfileSettings.MIGRATION_BACKUP_DIR_KEY -> "AWF MultiProfile has no migration backup directory";
            default -> "AWF MultiProfile uses its default";
        };
    }
}
