package dev.iyanz.sourbycraft.config;

import java.util.List;
import java.util.Objects;

/** Typed, immutable settings for implemented Aurora behavior only. Parsing never writes config. */
public record AuroraConfig(Entity entity) {
    public static final String ASYNC_PATH_KEY = "aurora.entity.async-pathfinding";
    public static final String LEGACY_ASYNC_PATH_KEY = "perf.ai.async-pathfinding";
    public static final AuroraConfig DEFAULT = new AuroraConfig(new Entity(false));
    public static final Setting ASYNC_PATH = new Setting(ASYNC_PATH_KEY, Lifecycle.LIVE);

    public AuroraConfig { Objects.requireNonNull(entity, "entity"); }

    public record Entity(boolean asyncPathfinding) {}
    public enum Lifecycle { LIVE, RESTART_REQUIRED, IMMUTABLE_FOR_RUN }
    public record Setting(String key, Lifecycle lifecycle) {}

    public record Parsed(AuroraConfig config, List<String> invalidKeys, List<String> deprecatedKeys) {
        public Parsed {
            Objects.requireNonNull(config, "config");
            invalidKeys = List.copyOf(invalidKeys);
            deprecatedKeys = List.copyOf(deprecatedKeys);
        }
    }

    public static Parsed parse(final ConfigSnapshot snapshot) {
        final var values = snapshot.values();
        // Malformed namespace containers must not expose a legacy true underneath them.
        for (final String parent : List.of("aurora", "aurora.entity")) {
            if (values.containsKey(parent)) return new Parsed(DEFAULT, List.of(parent), List.of());
        }
        final boolean modern = values.containsKey(ASYNC_PATH_KEY);
        final String key = modern ? ASYNC_PATH_KEY : LEGACY_ASYNC_PATH_KEY;
        final Object value = values.get(key);
        final List<String> deprecated = !modern && value != null ? List.of(key) : List.of();
        if (value == null) return new Parsed(DEFAULT, List.of(), List.of());
        if (!(value instanceof Boolean enabled)) return new Parsed(DEFAULT, List.of(key), deprecated);
        return new Parsed(new AuroraConfig(new Entity(enabled)), List.of(), deprecated);
    }

    /** Counts only implemented Aurora settings, never upstream or cached utility settings. */
    public String reloadSummary(final AuroraConfig previous) {
        final int changed = entity.asyncPathfinding() == previous.entity.asyncPathfinding() ? 0 : 1;
        return "Aurora: " + changed + " live change(s) applied; restart required: none "
            + "(implemented Aurora keys only)";
    }
}
