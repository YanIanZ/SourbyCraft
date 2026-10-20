package dev.iyanz.sourbycraft.perf;

import java.util.concurrent.atomic.LongAdder;

/**
 * Process-wide chunk lifecycle transitions since boot, all worlds together.
 *
 * <p>Fed by Minecraft feature patch 0051, one call at the top of each lifecycle callback of
 * the chunk system's {@code BaseChunkSystemHooks} (Moonrise): a chunk holder is created or
 * deleted; a chunk becomes FULL (border) or stops being FULL; it starts or stops ticking; it starts
 * or stops entity ticking. A transition is counted when its callback is entered, so a callback that
 * then throws still counts. Always on (one {@link LongAdder} increment per transition, overhead not
 * benchmarked). Shown in {@code /perf chunks} (command/PerfTelemetryRows).</p>
 *
 * <p>The current levels ({@link Snapshot#fullChunks()} and the rest) are in minus out. Each counter
 * is read on its own, so while chunks move the levels can be off by the transitions that happened
 * during the read; they are not an atomic world census.</p>
 */
public final class ChunkLifecycleCounters {

    public static final ChunkLifecycleCounters GLOBAL = new ChunkLifecycleCounters();

    public enum Transition {
        HOLDER_CREATED, HOLDER_DELETED, FULL, NOT_FULL, TICKING, NOT_TICKING, ENTITY_TICKING, NOT_ENTITY_TICKING
    }

    public record Snapshot(long holdersCreated, long holdersDeleted, long fullLoads, long fullUnloads,
                           long tickingStarts, long tickingStops, long entityTickingStarts, long entityTickingStops) {

        public long holders() {
            return this.holdersCreated - this.holdersDeleted;
        }

        public long fullChunks() {
            return this.fullLoads - this.fullUnloads;
        }

        public long tickingChunks() {
            return this.tickingStarts - this.tickingStops;
        }

        public long entityTickingChunks() {
            return this.entityTickingStarts - this.entityTickingStops;
        }
    }

    private final LongAdder[] counts = new LongAdder[Transition.values().length];

    public ChunkLifecycleCounters() {
        for (int i = 0; i < this.counts.length; i++) {
            this.counts[i] = new LongAdder();
        }
    }

    public void record(final Transition transition) {
        this.counts[transition.ordinal()].increment();
    }

    private long sum(final Transition transition) {
        return this.counts[transition.ordinal()].sum();
    }

    public Snapshot snapshot() {
        return new Snapshot(this.sum(Transition.HOLDER_CREATED), this.sum(Transition.HOLDER_DELETED),
            this.sum(Transition.FULL), this.sum(Transition.NOT_FULL),
            this.sum(Transition.TICKING), this.sum(Transition.NOT_TICKING),
            this.sum(Transition.ENTITY_TICKING), this.sum(Transition.NOT_ENTITY_TICKING));
    }

    // Hook for patch 0051: one short call per callback.
    public static void count(final Transition transition) {
        GLOBAL.record(transition);
    }
}
