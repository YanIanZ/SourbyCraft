package dev.iyanz.sourbycraft.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable flattened utility configuration; created at explicit load/save boundaries only. */
public record ConfigSnapshot(Map<String, Object> values) {
    public ConfigSnapshot { values = Map.copyOf(values); }

    /**
     * One snapshot from several files, later files winning.
     *
     * <p>How Aurora keeps its own configuration file while still reading an older deployment's
     * unified one: the legacy file is supplied first and the Aurora file overlays it, so a server
     * that has never seen the new file keeps working and one that has takes the new value.</p>
     *
     * @param layers lowest precedence first
     * @return the merged snapshot
     */
    public static ConfigSnapshot layered(final ConfigSnapshot... layers) {
        final Map<String, Object> merged = new LinkedHashMap<>();
        for (final ConfigSnapshot layer : layers) {
            if (layer != null) {
                merged.putAll(layer.values());
            }
        }
        return new ConfigSnapshot(merged);
    }

    public static ConfigSnapshot copyOf(final UnmodifiableConfig config) {
        final Map<String, Object> values = new LinkedHashMap<>();
        flatten(config, "", values);
        return new ConfigSnapshot(values);
    }

    private static void flatten(final UnmodifiableConfig config, final String prefix,
                                final Map<String, Object> output) {
        for (final var entry : config.entrySet()) {
            final String key = prefix + entry.getKey();
            final Object value = entry.getValue();
            if (value instanceof UnmodifiableConfig nested) flatten(nested, key + ".", output);
            else if (value != null) output.put(key, freeze(value));
        }
    }

    private static Object freeze(final Object value) {
        if (value instanceof UnmodifiableConfig config) return copyOf(config).values();
        if (value instanceof List<?> list) return list.stream().map(ConfigSnapshot::freeze).toList();
        return value; // TOML scalar types are immutable.
    }
}
