package dev.iyanz.sourbycraft.tick;

import dev.iyanz.sourbycraft.async.AsyncWorkerPool;
import net.minecraft.world.entity.Entity;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class BatchPhysicsTicker {

    private final AsyncWorkerPool<ParallelTickSnapshot, ParallelTickDiff> pool;

    public BatchPhysicsTicker(
        ExecutorService workerExec,
        int queueCap,
        int circuitThreshold,
        long circuitCooldownMs,
        double watchdogMultiplier,
        Predicate<Entity> physicsPhase
    ) {
        this.pool = new AsyncWorkerPool<>(
            "parallel-tick",
            workerExec,
            queueCap,
            circuitThreshold,
            circuitCooldownMs,
            watchdogMultiplier,
            snap -> {
                Entity e = snap.entity();
                boolean needsInteract;
                try { needsInteract = physicsPhase.test(e); }
                catch (Throwable t) { return ParallelTickDiff.done(e); }
                return needsInteract ? ParallelTickDiff.needsInteract(e) : ParallelTickDiff.done(e);
            }
        );
    }

    public boolean submit(ParallelTickSnapshot snap) { return pool.submit(snap); }
    public void drainDiffs(Consumer<ParallelTickDiff> consumer) { pool.drainDiffs(consumer); }
    public AsyncWorkerPool<ParallelTickSnapshot, ParallelTickDiff> pool() { return pool; }
    public void shutdown() { pool.shutdown(); }
}
