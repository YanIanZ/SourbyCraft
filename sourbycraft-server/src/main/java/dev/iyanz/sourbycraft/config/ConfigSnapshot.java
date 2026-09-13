package dev.iyanz.sourbycraft.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable flattened utility configuration; created at explicit load/save boundaries only. */
public record ConfigSnapshot(Map<String, Object> values) {
    public ConfigSnapshot { values = Map.copyOf(values); }

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
