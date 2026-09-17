package dev.iyanz.sourbycraft.execution;

/**
 * The lanes a SourbyCraft process actually runs work on.
 *
 * <p>A lane is a subsystem's share of the machine, not a core. The JVM cannot pin a thread to a
 * core — there is no portable affinity API and none at all on macOS — so what can be owned is how
 * many threads each subsystem gets, and the operating system places them. "One core for world
 * load" is really "the chunk lane is sized for one core's worth of work".</p>
 *
 * <p>Lanes are not a partition of gameplay. A region tick thread runs the <em>whole</em> tick for
 * the region it owns — entities, blocks, and any plugin handler fired there — because region-owned
 * state may only be mutated by its owning thread. Gameplay cannot be split into a "world" lane and
 * a "plugin" lane; work leaves the region lane only when it needs nothing the region owns, which is
 * what {@link OwnerHandoff} exists to police.</p>
 *
 * <p>Threads are attributed by name because most pools here belong to upstream and cannot be asked
 * what they are. That makes the mapping a guess about strings, so it is pinned by tests against the
 * thread names a real run actually produced, and anything unrecognised lands in {@link #OTHER}
 * rather than being silently folded into a neighbour.</p>
 */
public enum ExecutionLane {

    /** Gameplay owned by a region: entities, blocks, and plugin handlers fired from them. */
    REGION_TICK("Region tick", "Folia Region Scheduler Thread", "Region Scheduler Thread"),

    /**
     * The chunk system's worker pool: generation, lighting and chunk loading.
     *
     * <p>Moonrise's {@code WORKER_POOL}, sized by {@code Paper.WorkerThreadCount}. This is the
     * lane that answers "how much does world load cost".</p>
     */
    CHUNK_WORKER("Chunk workers", "Paper Common Worker"),

    /**
     * The engine's general background pool, {@code Util.backgroundExecutor()}.
     *
     * <p>Deliberately not folded into {@link #CHUNK_WORKER}. It is a different pool — a
     * deprioritised ForkJoinPool sized from the core count, not from
     * {@code Paper.WorkerThreadCount} — and it carries whatever the engine hands it rather than
     * chunk work specifically. Counting the two together made a chunk-worker A/B read four and
     * twelve threads where the setting said two and six, which is how a lane measurement starts
     * misleading the tuning it exists to inform.</p>
     */
    BACKGROUND("Engine background", "Worker-Main"),

    /** Reading and writing world data. */
    WORLD_IO("World I/O", "Dimension-Data-IO-Worker", "Paper I/O Worker", "SourbyCraft-IO"),

    /** Packet encode, decode and socket work. */
    NETWORK("Network", "Netty Epoll IO", "Netty Kqueue IO", "Netty NIO IO", "Netty Server IO"),

    /** Plugin work that already runs off the region: async tasks and command completion. */
    PLUGIN_ASYNC("Plugin async", "Paper Async Task Handler", "Paper Async Command Builder",
        "Craft Scheduler Thread"),

    /** Sourby-owned computation on snapshots, which touches nothing a region owns. */
    ASYNC_COMPUTE("Async compute", "SourbyCraft-AsyncPath"),

    /** Measuring the server, which must never be mistaken for the server working. */
    TELEMETRY("Telemetry", "SourbyCraft-PerformanceCollector", "spark-"),

    /** The collector's own threads. Not the server's work, but it is the server's cost. */
    GARBAGE_COLLECTION("Garbage collection", "GC Thread", "G1 Conc", "G1 Refine", "G1 Service",
        "G1 Main Marker"),

    /** Everything unrecognised, kept visible rather than folded into a neighbouring lane. */
    OTHER("Other");

    private final String display;
    private final String[] prefixes;

    ExecutionLane(final String display, final String... prefixes) {
        this.display = display;
        this.prefixes = prefixes;
    }

    public String display() {
        return this.display;
    }

    /**
     * The lane a thread belongs to, by name.
     *
     * @param threadName the thread's name, or {@code null}
     * @return the matching lane, or {@link #OTHER} when nothing matches
     */
    public static ExecutionLane of(final String threadName) {
        if (threadName == null || threadName.isEmpty()) {
            return OTHER;
        }
        for (final ExecutionLane lane : VALUES) {
            for (final String prefix : lane.prefixes) {
                if (threadName.startsWith(prefix)) {
                    return lane;
                }
            }
        }
        return OTHER;
    }

    private static final ExecutionLane[] VALUES = values();
}
