package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.util.VirtualExecutor;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * P5 Async Chunk Pipeline.
 *
 * <p>Lightweight bridge to {@link VirtualExecutor} for off-main-thread
 * chunk and entity-tracker computation. Provides a single entry point
 * for NMS patches (and plugins) to push expensive read-only chunk work
 * onto a virtual-thread pool.
 *
 * <p>Pure helper — does NOT introduce new threading semantics on its
 * own. Callers must ensure the task is side-effect free with respect
 * to the main-thread tick loop; the result CompletableFuture should be
 * consumed back on the main thread via
 * {@link net.minecraft.server.MinecraftServer#execute(Runnable)}.
 *
 * <p>Moonrise already owns the chunk-system async pipeline, so this
 * class is intentionally minimal — it complements rather than replaces
 * Moonrise.
 */
public final class AsyncChunkPipeline {

    private AsyncChunkPipeline() {}

    /** Submit a read-only chunk-or-tracker computation; returns a future for main-thread consumption. */
    public static <T> CompletableFuture<T> submit(final Supplier<T> readOnlyTask) {
        return VirtualExecutor.supply(readOnlyTask::get);
    }

    /** Fire-and-forget read-only side-task. */
    public static void run(final Runnable readOnlyTask) {
        VirtualExecutor.run(readOnlyTask);
    }
}
