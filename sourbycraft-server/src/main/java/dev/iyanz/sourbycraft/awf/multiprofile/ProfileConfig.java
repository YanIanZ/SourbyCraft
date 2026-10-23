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
            SourbyLogger.warn("Aurora config key '" + key + "' is invalid; AWF MultiProfile uses its default");
        }
        return parsed.settings();
    }
}
