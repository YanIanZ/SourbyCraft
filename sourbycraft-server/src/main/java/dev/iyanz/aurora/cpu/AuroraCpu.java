package dev.iyanz.aurora.cpu;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * How much of the machine Aurora may use, read early enough to size the region scheduler.
 *
 * <p>This exists because of boot order, not preference. The region tick-thread count is decided
 * inside {@code GlobalConfiguration}'s load, which runs before
 * {@code SourbyCraftBootstrap.init()} — so asking {@code SourbyCraftConfig} here would always
 * get the unloaded default, silently, and the setting would appear to do nothing. This reads the
 * one key it needs, directly, at the moment it is needed.</p>
 *
 * <p>It parses the two YAML files and reads one key rather than going through the full
 * configuration layer: that layer has its own lifecycle, publishes snapshots at explicit
 * boundaries, and warns about shadowed keys. Dragging all of that into pre-bootstrap would make
 * config loading depend on an order it currently does not, to read one integer.</p>
 *
 * <p>Precedence, deliberately stated because two files can now speak about the same thing:</p>
 *
 * <ol>
 *   <li>{@code threaded-regions.threads: N} in {@code paper-global.yml} — an operator naming the
 *       region thread count directly. Never overridden here.</li>
 *   <li>{@code aurora.cpu.cores} — how many processors Aurora may use in total. Read from
 *       {@code sourbycraft_config/aurora.yml} over {@code sourbycraft_config/sourbycraft_global_config.yml},
 *       the layering {@code SourbyCraftConfig} applies (each file, or its {@code .toml} predecessor
 *       until that is converted). A file that cannot be read is skipped, as that layer skips it: it
 *       costs only its own keys.</li>
 *   <li>Neither set: every available processor.</li>
 * </ol>
 */
public final class AuroraCpu {

    private AuroraCpu() {}

    /** Value meaning "decide from the machine". */
    public static final int AUTO = 0;

    private static final Path AURORA_CONFIG = Path.of("sourbycraft_config", "aurora.yml");
    private static final Path UNIFIED_CONFIG = Path.of("sourbycraft_config", "sourbycraft_global_config.yml");

    private static volatile int cached = Integer.MIN_VALUE;

    /**
     * The configured processor budget, or {@link #AUTO}.
     *
     * <p>Read once. A value larger than the machine is clamped: promising more parallelism than
     * there are processors only adds context switching, and a region ticks on one thread, so the
     * extra threads would idle anyway.</p>
     *
     * @return the configured core count, clamped to the machine, or {@link #AUTO}
     */
    public static int configuredCores() {
        int value = cached;
        if (value != Integer.MIN_VALUE) {
            return value;
        }
        value = read();
        cached = value;
        return value;
    }

    private static int read() {
        // The global file first, Aurora's own file over it: a key left in the global file counts
        // only where aurora.yml does not set it, as in SourbyCraftConfig's snapshot.
        Object value = null;
        for (final Path logical : List.of(UNIFIED_CONFIG, AURORA_CONFIG)) {
            final Path file = dev.iyanz.sourbycraft.config.ConfigSources.existing(logical);
            if (file == null) {
                continue;                      // First boot; the files are seeded later.
            }
            try {
                final Object found = dev.iyanz.sourbycraft.config.ConfigSources.read(file)
                    .get(dev.iyanz.sourbycraft.config.AuroraConfig.CPU_CORES_KEY);
                if (found != null) {
                    value = found;
                }
            } catch (final IOException | RuntimeException unreadable) {
                // A malformed file must not stop a server booting. As in SourbyCraftConfig, a file that
                // cannot be read costs only its own keys: the other file still counts, and with neither
                // setting the key the budget is auto. The configuration layer names the bad file and line
                // once it loads.
            }
        }
        if (!(value instanceof Number number)) {
            return AUTO;
        }
        final int configured = number.intValue();
        if (configured <= 0) {
            return AUTO;
        }
        return Math.min(configured, Runtime.getRuntime().availableProcessors());
    }

    /**
     * Region tick threads to start when {@code paper-global.yml} does not name a count.
     *
     * @return the Aurora budget when set, otherwise every available processor; at least 1
     */
    public static int regionTickThreads() {
        final int configured = configuredCores();
        return Math.max(1, configured == AUTO ? Runtime.getRuntime().availableProcessors() : configured);
    }

    /** What the server should say it did, so the resolved number is never a mystery. */
    public static String describe() {
        final int configured = configuredCores();
        return configured == AUTO
            ? "aurora.cpu.cores unset; using all " + Runtime.getRuntime().availableProcessors()
              + " available processor(s)"
            : "aurora.cpu.cores = " + configured + " of "
              + Runtime.getRuntime().availableProcessors() + " available processor(s)";
    }

    /** Test seam: forgets the cached read. Not for runtime use. */
    static void resetForTest() {
        cached = Integer.MIN_VALUE;
    }
}
