package dev.iyanz.sourbycraft.execution;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Aurora Resource Governor: fixed budgets for the work SourbyCraft itself puts on the machine,
 * as specified in {@code docs/architecture/aurora-resource-governor.md}.
 *
 * <p>It governs only lanes SourbyCraft owns (bridge async I/O, AWF storage commits, async
 * pathfinding). Region tick threads, chunk workers, Netty event loops
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
        BRIDGE_IO("Bridge I/O", "SourbyCraft-BridgeIO-"),
        /** Aurora World Fabric commits. One thread by default: commits to one store are serialized anyway. */
        STORAGE("Storage", "SourbyCraft-Storage-"),
        /**
         * Async pathfinding solves ({@code perf/AsyncPathProcessor}): CPU-bound A* over an immutable
         * snapshot. Budget max(1, processors / 4) threads below normal priority and a queue of
         * {@link #PATHFINDING_QUEUE}; not operator-configurable. A full queue is refused and the
         * navigation runs its synchronous recompute instead, so rejections here are routine
         * backpressure, counted once as the async path's {@code refused}.
         */
        PATHFINDING("Pathfinding", "SourbyCraft-AsyncPath-");

        private final String display;
        private final String threadPrefix;

        Lane(final String display, final String threadPrefix) {
            this.display = display;
            this.threadPrefix = threadPrefix;
        }

        public String display() {
            return this.display;
        }

        public String threadPrefix() {
            return this.threadPrefix;
        }
    }

    /** Queue capacity of the {@link Lane#PATHFINDING} lane. */
    public static final int PATHFINDING_QUEUE = 1024;

    /** Threads of the {@link Lane#PATHFINDING} lane: a quarter of the processors, at least one. */
    public static int pathfindingThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() / 4);
    }

    /** The process's governor. */
    public static final ResourceGovernor GLOBAL = new ResourceGovernor();

    private final Map<Lane, GovernedLane> lanes = new ConcurrentHashMap<>();
    private volatile dev.iyanz.sourbycraft.config.AuroraConfig.Scheduler budgets =
        dev.iyanz.sourbycraft.config.AuroraConfig.Scheduler.DEFAULT;

    /**
     * Sets the budgets lanes are created with. A lane that already exists keeps its budget until
     * restart (RESTART_REQUIRED); the config reload summary says so.
     */
    public void configure(final dev.iyanz.sourbycraft.config.AuroraConfig.Scheduler scheduler) {
        this.budgets = java.util.Objects.requireNonNull(scheduler, "scheduler");
    }

    /** The budgets lanes are (or will be) created with. */
    public dev.iyanz.sourbycraft.config.AuroraConfig.Scheduler budgets() {
        return this.budgets;
    }

    /** The lane, created on first use with its configured budget. */
    public GovernedLane lane(final Lane lane) {
        return this.lanes.computeIfAbsent(lane, this::create);
    }

    /**
     * The lane, replaced by a fresh one with the current budget if it has been shut down. For an
     * owner whose lifecycle restarts (async pathfinding can be stopped and started again); the
     * other lanes stay terminal after {@link #shutdown}.
     */
    public GovernedLane restart(final Lane lane) {
        return this.lanes.compute(lane, (l, existing) ->
            existing != null && !existing.isShutdown() ? existing : this.create(l));
    }

    private GovernedLane create(final Lane l) {
        final var b = this.budgets;
        return switch (l) {
            case BRIDGE_IO -> new GovernedLane(l.display, l.threadPrefix, b.resolvedBridgeIoThreads(), b.bridgeIoQueue());
            case STORAGE -> new GovernedLane(l.display, l.threadPrefix, b.resolvedStorageThreads(), b.storageQueue());
            case PATHFINDING -> new GovernedLane(l.display, l.threadPrefix, pathfindingThreads(), PATHFINDING_QUEUE,
                Thread.NORM_PRIORITY - 1);
        };
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
