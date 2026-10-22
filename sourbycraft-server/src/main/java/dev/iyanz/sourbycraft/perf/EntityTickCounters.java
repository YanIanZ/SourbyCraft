package dev.iyanz.sourbycraft.perf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * Entity ticks per world since boot, fed once per region entity loop.
 *
 * <p>Minecraft feature patch 0052 hooks the region entity tick loop of {@code ServerLevel.tick}
 * ({@code forEachTickingEntity}): before the loop it zeroes a plain {@code int} on the region's own
 * per-world data, inside the loop it increments that field right before each
 * {@code guardEntityTick(tickNonPassenger, entity)}, and after the loop it calls
 * {@link #regionTicked(String, int, long)} once with the count and the loop's wall time.</p>
 *
 * <p>What counts as an entity tick: a non-passenger entity handed to {@code tickNonPassenger},
 * including entities that Paper's activation range then only "inactive ticks". Not counted:
 * entities skipped because the tick manager froze them, removed or despawned entities, and
 * passengers (they are ticked inside their vehicle's tick). The loop time includes the despawn
 * checks and the passengers' ticks; it excludes activation-range computation and block entities.</p>
 *
 * <p>Overhead: per entity, one increment of a plain field that only the owning region thread
 * touches (no atomic, no fence, no allocation; the loop's lambda already captured objects, so
 * capturing the region data adds no allocation). Per region and world tick: two
 * {@link System#nanoTime()} reads, one map lookup by world name, three {@link LongAdder} adds and
 * two {@link LongAccumulator} updates. Always on; not benchmarked.</p>
 *
 * <p>Per region: {@link Snapshot#maxEntitiesInOneLoop()} and {@link Snapshot#maxLoopNanos()} are
 * the largest single region's loop; there is no per-region breakdown, because regions merge and
 * split and have no stable identity to key on.</p>
 */
public final class EntityTickCounters {

    public static final EntityTickCounters GLOBAL = new EntityTickCounters();

    public record Snapshot(String world, long entityTicks, long loops, long loopNanos,
                           long maxEntitiesInOneLoop, long maxLoopNanos) {

        public double meanEntitiesPerLoop() {
            return this.loops == 0L ? 0.0 : (double) this.entityTicks / this.loops;
        }

        public double meanLoopMillis() {
            return this.loops == 0L ? 0.0 : this.loopNanos / 1_000_000.0 / this.loops;
        }

        public double maxLoopMillis() {
            return this.maxLoopNanos / 1_000_000.0;
        }
    }

    private static final class World {
        final LongAdder entityTicks = new LongAdder();
        final LongAdder loops = new LongAdder();
        final LongAdder loopNanos = new LongAdder();
        final LongAccumulator maxEntities = new LongAccumulator(Math::max, 0L);
        final LongAccumulator maxNanos = new LongAccumulator(Math::max, 0L);
    }

    private final ConcurrentHashMap<String, World> worlds = new ConcurrentHashMap<>();

    public void record(final String world, final int ticked, final long nanos) {
        World counters = this.worlds.get(world);
        if (counters == null) {
            counters = this.worlds.computeIfAbsent(world, ignored -> new World());
        }
        final long entities = Math.max(0, ticked);
        final long duration = Math.max(0L, nanos);
        counters.entityTicks.add(entities);
        counters.loops.increment();
        counters.loopNanos.add(duration);
        counters.maxEntities.accumulate(entities);
        counters.maxNanos.accumulate(duration);
    }

    /** One snapshot per world that ran an entity loop since boot, sorted by world name. */
    public List<Snapshot> snapshot() {
        final List<Snapshot> out = new ArrayList<>(this.worlds.size());
        this.worlds.forEach((name, c) -> out.add(new Snapshot(name, c.entityTicks.sum(), c.loops.sum(),
            c.loopNanos.sum(), c.maxEntities.get(), c.maxNanos.get())));
        out.sort(Comparator.comparing(Snapshot::world));
        return List.copyOf(out);
    }

    // Hook for patch 0052: one call per region entity loop.
    public static void regionTicked(final String world, final int ticked, final long nanos) {
        GLOBAL.record(world, ticked, nanos);
    }
}
