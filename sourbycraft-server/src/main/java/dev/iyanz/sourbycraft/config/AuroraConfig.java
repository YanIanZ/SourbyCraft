package dev.iyanz.sourbycraft.config;

import java.util.List;
import java.util.Objects;

/** Typed, immutable settings for implemented Aurora behavior only. Parsing never writes config. */
public record AuroraConfig(Entity entity, Diagnostics diagnostics) {
    public static final String ASYNC_PATH_KEY = "aurora.entity.async-pathfinding";
    public static final String LEGACY_ASYNC_PATH_KEY = "perf.ai.async-pathfinding";
    public static final String LANE_SAMPLING_KEY = "aurora.diagnostics.lane-sampling";
    public static final AuroraConfig DEFAULT = new AuroraConfig(new Entity(false), new Diagnostics(true));
    public static final Setting ASYNC_PATH = new Setting(ASYNC_PATH_KEY, Lifecycle.LIVE);
    public static final Setting LANE_SAMPLING = new Setting(LANE_SAMPLING_KEY, Lifecycle.LIVE);

    public AuroraConfig {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(diagnostics, "diagnostics");
    }

    public record Entity(boolean asyncPathfinding) {}

    /**
     * Settings for measuring the server.
     *
     * <p>{@code laneSampling} attributes per-thread CPU to execution lanes once a second. It
     * defaults on because it is what answers "where did the machine's time go", and it is cheap
     * under load — but it is not free: on an idle server the telemetry lane costs more than the
     * region lane, so a host running many idle worlds has a reason to turn it off.</p>
     */
    public record Diagnostics(boolean laneSampling) {}
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
        for (final String parent : List.of("aurora", "aurora.entity", "aurora.diagnostics")) {
            if (values.containsKey(parent)) return new Parsed(DEFAULT, List.of(parent), List.of());
        }
        final boolean modern = values.containsKey(ASYNC_PATH_KEY);
        final String pathKey = modern ? ASYNC_PATH_KEY : LEGACY_ASYNC_PATH_KEY;
        final Object pathValue = values.get(pathKey);
        final List<String> deprecated = !modern && pathValue != null ? List.of(pathKey) : List.of();

        final List<String> invalid = new java.util.ArrayList<>();
        boolean asyncPathfinding = DEFAULT.entity().asyncPathfinding();
        if (pathValue != null) {
            if (pathValue instanceof Boolean enabled) {
                asyncPathfinding = enabled;
            } else {
                invalid.add(pathKey);
            }
        }

        // An unreadable setting falls back to the default rather than to off: silently disabling
        // the thing that explains where CPU went is worse than ignoring a typo.
        boolean laneSampling = DEFAULT.diagnostics().laneSampling();
        final Object laneValue = values.get(LANE_SAMPLING_KEY);
        if (laneValue != null) {
            if (laneValue instanceof Boolean enabled) {
                laneSampling = enabled;
            } else {
                invalid.add(LANE_SAMPLING_KEY);
            }
        }

        // Each key falls back on its own. A typo in one setting must not silently revert another
        // the operator set deliberately -- only a malformed namespace container, handled above,
        // discards everything, because then nothing underneath it can be trusted.
        return new Parsed(new AuroraConfig(new Entity(asyncPathfinding), new Diagnostics(laneSampling)),
            List.copyOf(invalid), deprecated);
    }

    /** Counts only implemented Aurora settings, never upstream or cached utility settings. */
    public String reloadSummary(final AuroraConfig previous) {
        int changed = 0;
        if (entity.asyncPathfinding() != previous.entity.asyncPathfinding()) changed++;
        if (diagnostics.laneSampling() != previous.diagnostics.laneSampling()) changed++;
        return "Aurora: " + changed + " live change(s) applied; restart required: none "
            + "(implemented Aurora keys only)";
    }
}
