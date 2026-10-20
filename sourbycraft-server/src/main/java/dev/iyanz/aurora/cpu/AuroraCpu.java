package dev.iyanz.aurora.cpu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How much of the machine Aurora may use, read early enough to size the region scheduler.
 *
 * <p>This exists because of boot order, not preference. The region tick-thread count is decided
 * inside {@code GlobalConfiguration}'s load, which runs before
 * {@code SourbyCraftBootstrap.init()} — so asking {@code SourbyCraftConfig} here would always
 * get the unloaded default, silently, and the setting would appear to do nothing. This reads the
 * one key it needs, directly, at the moment it is needed.</p>
 *
 * <p>It parses a single line rather than loading the whole TOML: the full configuration layer
 * has its own lifecycle, publishes snapshots at explicit boundaries, and warns about shadowed
 * keys. Dragging all of that into pre-bootstrap would make config loading depend on an order it
 * currently does not, to read one integer.</p>
 *
 * <p>Precedence, deliberately stated because two files can now speak about the same thing:</p>
 *
 * <ol>
 *   <li>{@code threaded-regions.threads: N} in {@code paper-global.yml} — an operator naming the
 *       region thread count directly. Never overridden here.</li>
 *   <li>{@code aurora.cpu.cores} in {@code sourbycraft_config/aurora.toml} — how many processors
 *       Aurora may use in total.</li>
 *   <li>Neither set: every available processor.</li>
 * </ol>
 */
public final class AuroraCpu {

    private AuroraCpu() {}

    /** Value meaning "decide from the machine". */
    public static final int AUTO = 0;

    private static final Path AURORA_CONFIG = Path.of("sourbycraft_config", "aurora.toml");

    /** {@code cores = N} inside the {@code [aurora.cpu]} table, tolerating leading whitespace. */
    private static final Pattern CORES = Pattern.compile(
        "^\\s*\\[aurora\\.cpu\\]\\s*$(?:(?!^\\s*\\[).)*?^\\s*cores\\s*=\\s*(\\d+)\\s*$",
        Pattern.MULTILINE | Pattern.DOTALL);

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
        try {
            if (!Files.isRegularFile(AURORA_CONFIG)) {
                return AUTO;                       // First boot; the file is seeded later.
            }
            final Matcher matcher = CORES.matcher(Files.readString(AURORA_CONFIG));
            if (!matcher.find()) {
                return AUTO;
            }
            final int configured = Integer.parseInt(matcher.group(1));
            if (configured <= 0) {
                return AUTO;
            }
            return Math.min(configured, Runtime.getRuntime().availableProcessors());
        } catch (final IOException | NumberFormatException unreadable) {
            // A malformed budget must not stop a server booting. Auto is the documented
            // default, and the configuration layer reports the bad value once it loads.
            return AUTO;
        }
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
