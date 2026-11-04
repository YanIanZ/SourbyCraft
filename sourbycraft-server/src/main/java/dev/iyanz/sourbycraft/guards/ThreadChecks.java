package dev.iyanz.sourbycraft.guards;

import dev.iyanz.sourbycraft.bridge.AuroraBridge;
import dev.iyanz.sourbycraft.bridge.BridgeRuntime;
import dev.iyanz.sourbycraft.config.AuroraConfig;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What a failed region-ownership check does: {@code aurora.bridge.thread-checks}.
 *
 * <p>Adapted from LightingLuminol (GPL-3.0; Bacteriawa, 2026-06-06; revision 72d51c4d, paper patch
 * {@code 0002-Base-Dirty-Patches} {@code TickThread} hunks and minecraft patch {@code 0001-Base-Dirty-Patches}
 * {@code RegionizedServer#ensureGlobalTickThread} / {@code RegionizedWorldData} hunks), where the throws are
 * commented out unconditionally. SourbyCraft keeps the checks and asks this class whether to throw, so the
 * behaviour is a setting and the hits stay visible in telemetry. Owner decision 2026-10-22 (verbatim): "Tiru
 * penuh LightingLuminol". See {@code docs/architecture/aurora-plugin-bridge.md}, "Thread checks", and the
 * owner-decisions section of {@code docs/DEVELOPMENT.md}.</p>
 *
 * <p>The mode is resolved once, at class init (RESTART_REQUIRED): the JVM property
 * {@code -Daurora.bridge.thread-checks=} wins, else the {@code aurora.bridge.thread-checks} key (read early
 * with the bridge's settings, {@code sourbycraft_config/aurora.yml} over the global YAML file), else the
 * owner's default {@code relaxed}.
 * Whatever is configured only takes effect while {@code aurora.bridge.mode} admits legacy plugins; with the
 * bridge off the checks stay enforced. {@link #RELAXED_SCOPE} is a {@code static final} so the extra locking
 * and the cross-region world-data lookups it guards fold away on an enforced server.</p>
 *
 * <p>Honesty: a server whose checks are relaxed is <b>unsafe and unqualified</b>. Region ownership is what
 * keeps two threads from writing one chunk section, one entity list or one player container at the same
 * time; relaxing it makes data races, chunk corruption that reaches disk, item duplication and deadlocks
 * possible. The checks only tell the operator that it happened.</p>
 */
public final class ThreadChecks {

    private static final Logger LOGGER = LoggerFactory.getLogger("Aurora");

    /** JVM property; overrides the {@code aurora.bridge.thread-checks} key in the YAML configuration. */
    public static final String PROPERTY = "aurora.bridge.thread-checks";
    /** JVM property: {@code true} attaches a stack trace to every relaxed-hit log line (debugging only). */
    public static final String TRACE_PROPERTY = "aurora.bridge.thread-checks.trace";

    /** How long one (plugin or thread, reason) log line suppresses the next. */
    static final long LOG_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(60L);
    /** Distinct reasons remembered for rate limiting outside bridged bodies; past this, new reasons share one window. */
    static final int MAX_TRACKED_REASONS = 256;

    /** What the operator configured (JVM property, else the YAML key, else the owner default). */
    private static final AuroraConfig.ThreadChecks CONFIGURED;
    /** What the server does: {@link #CONFIGURED} while the bridge admits plugins, else {@code ENFORCED}. */
    private static final AuroraConfig.ThreadChecks EFFECTIVE;
    /** Whether the bridge admits plugins this run (read early, same source as the bridge itself). */
    private static final boolean BRIDGE_ADMITS;

    /** LightingLuminol's scope: every ownership check is advisory. */
    public static final boolean RELAXED;
    /** Any relaxed scope: a foreign thread may reach region-local structures, so they must tolerate it. */
    public static final boolean RELAXED_SCOPE;

    private static final boolean TRACE = Boolean.getBoolean(TRACE_PROPERTY);
    private static final LongAdder RELAXED_HITS = new LongAdder();
    private static final LongAdder RELAXED_HITS_OUTSIDE_BRIDGE = new LongAdder();
    private static final LogWindows LOG_WINDOWS = new LogWindows(LOG_WINDOW_NANOS, MAX_TRACKED_REASONS);

    static {
        AuroraConfig.ThreadChecks configured;
        boolean admits;
        try {
            final AuroraConfig.Bridge early = AuroraBridge.earlySettings();
            configured = early.threadChecks();
            admits = early.mode() != AuroraConfig.BridgeMode.OFF;
        } catch (final Throwable unavailable) {
            // Unit tests and tools without a server directory: the owner default and no admission.
            configured = AuroraConfig.Bridge.DEFAULT.threadChecks();
            admits = false;
        }
        final AuroraConfig.ThreadChecks fromProperty = AuroraConfig.ThreadChecks.parse(System.getProperty(PROPERTY));
        if (fromProperty != null) configured = fromProperty;
        CONFIGURED = configured;
        BRIDGE_ADMITS = admits;
        EFFECTIVE = decide(configured, admits);
        RELAXED = EFFECTIVE == AuroraConfig.ThreadChecks.RELAXED;
        RELAXED_SCOPE = EFFECTIVE != AuroraConfig.ThreadChecks.ENFORCED;
    }

    private ThreadChecks() {}

    /** The effective mode: the configured one while the bridge admits plugins, else {@code ENFORCED}. */
    static AuroraConfig.ThreadChecks decide(final AuroraConfig.ThreadChecks configured, final boolean bridgeAdmits) {
        return bridgeAdmits ? configured : AuroraConfig.ThreadChecks.ENFORCED;
    }

    /**
     * Whether a failed check throws.
     *
     * @param configured the configured mode
     * @param bridgeAdmits whether the bridge admits legacy plugins this run
     * @param bridgedBodyOnThread whether a bridged plugin's task body is running on the calling thread
     */
    static boolean refuses(final AuroraConfig.ThreadChecks configured, final boolean bridgeAdmits,
                           final boolean bridgedBodyOnThread) {
        return switch (decide(configured, bridgeAdmits)) {
            case ENFORCED -> true;
            case BRIDGED -> !bridgedBodyOnThread;
            case RELAXED -> false;
        };
    }

    public static AuroraConfig.ThreadChecks configured() { return CONFIGURED; }

    public static AuroraConfig.ThreadChecks effective() { return EFFECTIVE; }

    /** One phrase for {@code /ver} and {@code /sourbycraft config}: the effective mode and why. */
    public static String describe() {
        if (EFFECTIVE == CONFIGURED) {
            return EFFECTIVE.configValue() + (RELAXED_SCOPE ? " (region-ownership safety off; unqualified)" : "");
        }
        return EFFECTIVE.configValue() + " (configured " + CONFIGURED.configValue() + ", inactive while aurora.bridge.mode = off)";
    }

    /** Ownership checks that did not throw because of this setting, all threads. */
    public static long relaxedHits() { return RELAXED_HITS.sum(); }

    /** Of those, hits outside any bridged task body (native plugins, event handlers, the server itself). */
    public static long relaxedHitsOutsideBridge() { return RELAXED_HITS_OUTSIDE_BRIDGE.sum(); }

    /**
     * Called by a failed ownership check with its diagnostic text. {@code true}: throw as before. {@code false}:
     * the hit was recorded (bridged plugin telemetry, or a rate-limited warning) and the caller proceeds on the
     * wrong thread.
     */
    public static boolean refuse(final String reason) {
        if (EFFECTIVE == AuroraConfig.ThreadChecks.ENFORCED) {
            return true;
        }
        final boolean bridged = BridgeRuntime.reportRelaxedViolation(reason);
        if (EFFECTIVE == AuroraConfig.ThreadChecks.BRIDGED && !bridged) {
            return true;
        }
        RELAXED_HITS.increment();
        if (!bridged) {
            RELAXED_HITS_OUTSIDE_BRIDGE.increment();
            logOutsideBridge(reason);
        }
        return false;
    }

    /**
     * Rate-limited: one line per reason per {@link #LOG_WINDOW_NANOS}, logged on the thread that hit it. The window
     * is keyed by the reason without its per-hit context ({@link LogWindows#baseReason}: TickThread appends the
     * thread, world and position of every hit), so a sweep across many positions logs once, not once per position;
     * the line itself carries the full text of the hit that opened the window.
     */
    private static void logOutsideBridge(final String reason) {
        final String text = reason == null ? "unknown" : reason;
        final long suppressed = LOG_WINDOWS.admit(LogWindows.baseReason(text), System.nanoTime());
        if (suppressed < 0) {
            return;
        }
        final String line = "Region-ownership check relaxed (aurora.bridge.thread-checks = " + EFFECTIVE.configValue()
            + "): " + text + "; thread=" + Thread.currentThread().getName()
            + ". The access proceeded on a thread that does not own that state; this server is not qualified."
            + LOG_WINDOWS.suppressedNote(suppressed);
        if (TRACE) {
            LOGGER.warn(line, new Throwable("relaxed thread check"));
        } else {
            LOGGER.warn(line);
        }
    }

    /**
     * One boot line, from {@code AuroraBridge} when it first runs (before the plugin scan): a WARN when the
     * checks are relaxed, an INFO when a relaxed configuration is inactive because the bridge is off.
     */
    public static void bootReport(final Consumer<String> warn, final Consumer<String> info) {
        if (RELAXED_SCOPE) {
            warn.accept("Aurora Bridge: region-ownership checks are " + EFFECTIVE.configValue().toUpperCase(Locale.ROOT)
                + " (aurora.bridge.thread-checks; owner decision 2026-10-22 \"Tiru penuh LightingLuminol\"). "
                + "Region-ownership safety is OFF for " + (RELAXED ? "every caller" : "bridged plugin task bodies")
                + ": data races, chunk corruption persisted to disk, item duplication and deadlocks are possible. "
                + "This server is NOT qualified. Set thread-checks = \"enforced\" to restore the checks (restart).");
        } else if (CONFIGURED != AuroraConfig.ThreadChecks.ENFORCED) {
            info.accept("Aurora Bridge: aurora.bridge.thread-checks = " + CONFIGURED.configValue()
                + " is inactive while aurora.bridge.mode = off; region-ownership checks are enforced.");
        }
    }

    /** For {@code ThreadChecksTest} only: clears the rate-limit memory. */
    static void resetLogMemoryForTesting() {
        LOG_WINDOWS.clear();
    }
}
