package dev.iyanz.sourbycraft.awf.redis.fabric;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code aurora.awf.redis.fabric.*} early, from the same files and with the same precedence as
 * {@link dev.iyanz.sourbycraft.awf.AwfSettings#readEarly} and
 * {@link dev.iyanz.sourbycraft.awf.redis.RedisSettings#readEarly}: the unified file first, then
 * {@code aurora.yml} over it. The fabric is created during startup, before the configuration object
 * exists, so it cannot wait for the normal config load.
 */
public final class FabricConfig {

    private FabricConfig() {}

    /** Fabric settings; disabled when no file can be read or a value is unusable. */
    public static FabricSettings readEarly(final Path auroraFile, final Path unifiedFile) {
        final Map<String, Object> values = new HashMap<>();
        for (final Path file : List.of(unifiedFile, auroraFile)) {
            final Path source = dev.iyanz.sourbycraft.config.ConfigSources.existing(file);
            if (source == null) continue;
            try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
                final var config = dev.iyanz.sourbycraft.config.ConfigSources.parse(source, reader);
                for (final String key : FabricSettings.KEYS) {
                    final Object value = config.get(key);
                    if (value != null) values.put(key, value);
                }
            } catch (final Exception unreadable) {
                SourbyLogger.warn("Aurora Redis Fabric could not read "
                    + dev.iyanz.sourbycraft.config.ConfigParseErrors.describe(source, unreadable)
                    + "; the fabric stays disabled");
                return FabricSettings.DEFAULT;
            }
        }
        final FabricSettings.Parsed parsed = FabricSettings.parse(values);
        for (final String key : parsed.invalidKeys()) {
            SourbyLogger.warn("Aurora config key '" + key + "' is invalid; the Aurora Redis Fabric uses its default");
        }
        return parsed.settings();
    }
}
