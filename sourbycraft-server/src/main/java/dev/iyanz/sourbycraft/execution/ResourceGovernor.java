package dev.iyanz.sourbycraft.execution;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Aurora Resource Governor: fixed budgets for the work SourbyCraft itself puts on the machine,
 * as specified in {@code docs/architecture/aurora-resource-governor.md}.
 *
 * <p>It governs only lanes SourbyCraft owns. Region tick threads, chunk workers, Netty event loops
 * and the engine's background pool belong to upstream and are sized there; the governor neither
 * resizes nor reports them (lane CPU attribution in {@code /perf lanes} does that). Budgets are
 * static: nothing here watches load and adjusts itself, which the Aurora no-auto-tuning rule
 * forbids.</p>
 */
public final class ResourceGovernor {

    /** A governed lane and its default budget. */
    public enum Lane {
        /**
         * Async work of plugins running through the Aurora Bridge. Bounded so legacy plugins cannot
         * turn unlimited concurrent async tasks into unlimited threads.
         */
        BRIDGE_IO("Bridge I/O", "SourbyCraft-BridgeIO-",
            Math.max(2, Runtime.getRuntime().availableProcessors() / 4), 256),
        /** Aurora World Fabric commits. One thread: commits to one store are serialized anyway. */
        STORAGE("Storage", "SourbyCraft-Storage-", 1, 64);

        private final String display;
        private final String threadPrefix;
        private final int threads;
        private final int queue;

        Lane(final String display, final String threadPrefix, final int threads, final int queue) {
            this.display = display;
            this.threadPrefix = threadPrefix;
            this.threads = threads;
            this.queue = queue;
        }

        public String display() {
            return this.display;
        }

        public String threadPrefix() {
            return this.threadPrefix;
        }
    }

    /** The process's governor. */
    public static final ResourceGovernor GLOBAL = new ResourceGovernor();

    private final Map<Lane, GovernedLane> lanes = new ConcurrentHashMap<>();

    /** The lane, created on first use with its budget. */
    public GovernedLane lane(final Lane lane) {
        return this.lanes.computeIfAbsent(lane,
            l -> new GovernedLane(l.display, l.threadPrefix, l.threads, l.queue));
    }

    /** Stats for lanes that have been used, in declaration order. */
    public List<GovernedLane.Stats> stats() {
        return java.util.Arrays.stream(Lane.values())
            .map(this.lanes::get).filter(java.util.Objects::nonNull).map(GovernedLane::stats).toList();
    }

    /** Stops every lane, waiting up to {@code timeoutMillis} each. */
    public void shutdown(final long timeoutMillis) throws InterruptedException {
        for (final GovernedLane lane : this.lanes.values()) {
            lane.shutdown(timeoutMillis);
        }
    }
}
