package dev.iyanz.sourbycraft.perf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Entity ticks per world since boot (or since the world's first loop after it was dropped, below),
 * fed once per region entity loop.
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
 *
 * <p>Bounded: a world that is not loaded and whose loop count no pass has seen move for
 * {@link #IDLE_NANOS} is dropped, at a {@link #snapshot()} and before a world is counted for the first
 * time, so a server that keeps creating uniquely named worlds does not keep every one (audit PM-308).
 * Idleness is judged by those passes, so a world can stay listed longer than the idle time, never
 * shorter. Counts recorded into a dropped entry by a loop running at that very moment are lost.</p>
 */
public final class EntityTickCounters {

    public static final EntityTickCounters GLOBAL = new EntityTickCounters();

    /** How long an unloaded world's loop count must stand still before its entry is dropped. */
    static final long IDLE_NANOS = TimeUnit.MINUTES.toNanos(15L);

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
        // Only prune() reads and writes these, under the owner's lock: the loop count it last saw
        // and when that count last moved.
        long prunedLoops = -1L;
        long activeAtNanos;
    }

    private final ConcurrentHashMap<String, World> worlds = new ConcurrentHashMap<>();
    private final Predicate<String> loaded;
    private final LongSupplier nanoClock;

    public EntityTickCounters() {
        this(LoadedWorlds::byName, System::nanoTime);
    }

    /** @param loaded whether a world of that name is loaded; an entry of a loaded world is never dropped */
    EntityTickCounters(final Predicate<String> loaded, final LongSupplier nanoClock) {
        this.loaded = loaded;
        this.nanoClock = nanoClock;
    }

    public void record(final String world, final int ticked, final long nanos) {
        World counters = this.worlds.get(world);
        if (counters == null) {
            // Once per newly seen world, not per loop: keeps the map bounded even if nobody reads it.
            this.prune();
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

    /** One snapshot per world that ran an entity loop and has not been dropped (see the class doc), sorted by world name. */
    public List<Snapshot> snapshot() {
        this.prune();
        final List<Snapshot> out = new ArrayList<>(this.worlds.size());
        this.worlds.forEach((name, c) -> out.add(new Snapshot(name, c.entityTicks.sum(), c.loops.sum(),
            c.loopNanos.sum(), c.maxEntities.get(), c.maxNanos.get())));
        out.sort(Comparator.comparing(Snapshot::world));
        return List.copyOf(out);
    }

    /**
     * Drops the entries of worlds that are not loaded and whose loop count has not moved since a pass
     * at least {@link #IDLE_NANOS} ago. CPU only (a map walk and Bukkit's world-name lookup); it may
     * run on a region thread, once per newly seen world.
     */
    private synchronized void prune() {
        final long now = this.nanoClock.getAsLong();
        this.worlds.entrySet().removeIf(entry -> {
            final World counters = entry.getValue();
            final long loops = counters.loops.sum();
            if (loops != counters.prunedLoops) {
                counters.prunedLoops = loops;
                counters.activeAtNanos = now;
                return false;
            }
            return now - counters.activeAtNanos >= IDLE_NANOS && !this.loaded.test(entry.getKey());
        });
    }

    // Hook for patch 0052: one call per region entity loop.
    public static void regionTicked(final String world, final int ticked, final long nanos) {
        GLOBAL.record(world, ticked, nanos);
    }
}
