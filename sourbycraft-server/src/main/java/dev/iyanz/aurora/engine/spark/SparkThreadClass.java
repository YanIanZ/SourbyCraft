package dev.iyanz.aurora.engine.spark;

import dev.iyanz.sourbycraft.awf.AwfMaintenanceDriver;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What a thread is for, decided from its name, as spark sees it.
 *
 * <p>Spark identifies a thread by id and name only: a {@code ThreadDumper} selects threads by
 * ({@code id}, {@code name}) and a {@code ThreadGrouper} labels samples by the same pair. So a
 * thread class here is a statement about <em>names</em>, never about what a thread is doing at the
 * moment. In particular the global tick and the region ticks run on the same
 * {@code Folia Region Scheduler Thread #n} threads and cannot be told apart in a spark view; that is
 * why {@link #REGION_TICK} says "region and global tick".</p>
 *
 * <p>Names are pinned by {@code SparkThreadClassTest} against the factories that create them. A
 * thread the table does not know is {@link #OTHER}; it is never folded into a neighbouring class.
 * Virtual threads (the Sourby I/O lane, {@code SourbyCraft-IO-n}) are not visible to spark at all.</p>
 */
public enum SparkThreadClass {

    /** The region scheduler's threads. The global tick runs here too; see the class comment. */
    REGION_TICK("Region and global tick", "Folia Region Scheduler Thread #\\d+"),

    /**
     * The chunk system's worker pool: {@code <brand> Common Worker #n} (the brand is
     * {@code PlatformHooks.getBrand()}, so it is matched as any word) or {@code ls_wg worker #n}
     * with the aggressive chunk system.
     */
    CHUNK_WORKER("Chunk workers", "\\S+ Common Worker #\\d+", "ls_wg worker #\\d+"),

    /** The chunk system's I/O pool: {@code <brand> I/O Worker #n} or {@code ls_wg/io worker #n}. */
    CHUNK_IO("Chunk I/O", "\\S+ I/O Worker #\\d+", "ls_wg/io worker #\\d+"),

    /** Sourby governor lanes that run on platform threads: storage, plugin bridge I/O and startup analysis. */
    SOURBY_LANE("Sourby lanes", "SourbyCraft-Storage-.+", "SourbyCraft-BridgeIO-\\d+", "SourbyCraft-Startup-\\d+"),

    /** The async pathfinding pool, {@code SourbyCraft-AsyncPath-n}. */
    ASYNC_PATH("Async path pool", "SourbyCraft-AsyncPath-\\d+"),

    /** The Aurora World Fabric maintenance driver: one thread. */
    AWF_MAINTENANCE("AWF maintenance", Pattern.quote(AwfMaintenanceDriver.THREAD_NAME)),

    /** Netty's event loops, {@code Netty <type> IO #n} with type Epoll, Kqueue, NIO or Unix Domain Socket: packet encode, decode and socket work. */
    NETWORK("Network", "Netty .+ IO #\\d+"),

    /** Anything not recognised. */
    OTHER("Other");

    private static final Pattern POOL_SUFFIX = Pattern.compile("^(.*?)[-# ]+\\d+$");
    private static final SparkThreadClass[] VALUES = values();

    private final String label;
    private final List<Pattern> patterns;

    SparkThreadClass(final String label, final String... regexes) {
        this.label = label;
        this.patterns = java.util.Arrays.stream(regexes).map(regex -> Pattern.compile(regex, Pattern.CASE_INSENSITIVE)).toList();
    }

    /** The name an operator reads in a report. */
    public String label() {
        return this.label;
    }

    /** The regular expressions (whole-name, case-insensitive) a thread of this class matches; empty for {@link #OTHER}. */
    public List<String> patterns() {
        return this.patterns.stream().map(Pattern::pattern).toList();
    }

    /** The class of a thread by name; {@link #OTHER} for {@code null}, blank or unknown names. */
    public static SparkThreadClass of(final String threadName) {
        if (threadName == null || threadName.isEmpty()) {
            return OTHER;
        }
        for (final SparkThreadClass threadClass : VALUES) {
            for (final Pattern pattern : threadClass.patterns) {
                if (pattern.matcher(threadName).matches()) {
                    return threadClass;
                }
            }
        }
        return OTHER;
    }

    /**
     * The group spark's default {@code ThreadGrouper} ({@code BY_POOL}) puts a thread in: the name
     * without a trailing {@code -n}, {@code #n} or {@code  n}. This repeats spark's own rule so the
     * report can list the labels the viewer will show; {@code SparkThreadClassTest} compares it with
     * spark's implementation.
     */
    public static String poolOf(final String threadName) {
        final java.util.regex.Matcher matcher = POOL_SUFFIX.matcher(threadName);
        return matcher.matches() ? matcher.group(1).trim() : threadName;
    }
}
