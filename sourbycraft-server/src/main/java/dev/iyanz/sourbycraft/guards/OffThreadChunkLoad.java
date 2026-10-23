package dev.iyanz.sourbycraft.guards;

import dev.iyanz.sourbycraft.execution.region.RegionChunkLoading;
import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * {@code World#getChunkAt(x, z)} called from a thread that ticks nothing: a Bukkit async task, a bridged plugin's
 * async lane ({@code SourbyCraft-BridgeIO-*}), a plugin's own executor (paper feature patch 0028).
 *
 * <p>On Paper such a call is legal: the calling thread blocks while the chunk is loaded. The region-threading base
 * refused it ("Async chunk retrieval") for every thread that does not own the chunk, although the NMS path it wraps
 * ({@code ServerChunkCache#syncLoad}) already serves a non-tick thread by scheduling the load and waiting. Legacy
 * plugins rely on the Paper behaviour: SuperiorSkyblock2 2026.3's {@code ChunksProvider.loadChunk} calls
 * {@code getChunkAt} on its async island-preparation task as soon as {@code isChunkLoaded} is true, and every refused
 * call dropped one island chunk from the operation (Sourby Demo, r48.2, {@code /is create}, 2026-10-22).
 *
 * <p>Owned rule, for a thread that is not a tick thread ({@link RegionOwnership#isTickThread()} false) only:
 * <ul>
 *   <li>a chunk already loaded at FULL is returned at once; nothing is loaded and nothing waits;</li>
 *   <li>otherwise the load is scheduled through the chunk system, which runs it for the owning region, and the calling
 *       thread waits at most {@link #TIMEOUT_SECONDS} seconds. A timeout (also a shut-down chunk system, which never
 *       completes the load), an interrupt or a failed load throw {@link IllegalStateException} naming the world and chunk; the call never hangs.</li>
 * </ul>
 * Every tick thread (a region, the global tick, the startup thread) keeps the base's refusal unchanged: a tick thread
 * never blocks on a chunk load and never reaches another region's chunk synchronously. The returned
 * {@code CraftChunk} holds no chunk reference; each later block or entity access through it is checked by its own
 * guard, so the chunk's contents stay owned by its region.
 *
 * <p>The messages here deliberately carry none of the region-violation markers {@code ViolationClassifier} looks for:
 * a timed-out wait is a slow load, not an ownership violation, and does not count toward a bridged plugin's
 * quarantine.
 */
public final class OffThreadChunkLoad {

    /** Upper bound on one wait. A FULL load or generation of one chunk takes milliseconds to a few seconds. */
    static final long TIMEOUT_SECONDS = 30L;

    /** Schedules one chunk load and calls back with the chunk, or {@code null} when it could not be loaded. */
    @FunctionalInterface
    interface Loader<T> {
        void load(Consumer<T> onLoaded);
    }

    private OffThreadChunkLoad() {
    }

    /** Whether the owned rule applies: the current thread ticks nothing. */
    public static boolean applies() {
        return !RegionOwnership.current().isTickThread();
    }

    /**
     * The FULL chunk at ({@code chunkX}, {@code chunkZ}), loading or generating it when needed; for a thread that
     * {@link #applies()} only.
     *
     * @param priority the chunk system's load priority, an opaque token from the patch call site (BLOCKING, as
     *                 {@code ServerChunkCache#syncLoad} uses for a waiting caller)
     */
    public static LevelChunk fullChunk(final ServerLevel level, final int chunkX, final int chunkZ, final Object priority) {
        return await(level.getChunkSource().getChunkNow(chunkX, chunkZ),
            onLoaded -> RegionChunkLoading.current().loadChunksAsync(level, chunkX, chunkX, chunkZ, chunkZ,
                ChunkStatus.FULL, priority,
                chunks -> onLoaded.accept(chunks.size() == 1 && chunks.get(0) instanceof LevelChunk full ? full : null)),
            "chunk (" + chunkX + ", " + chunkZ + ") of world '" + level.getWorld().getName() + "'",
            TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS));
    }

    static <T> T await(final T loaded, final Loader<T> loader, final String what,
                       final long timeoutNanos) {
        if (loaded != null) {
            return loaded;
        }
        final CompletableFuture<T> done = new CompletableFuture<>();
        loader.load(done::complete);
        final T chunk;
        try {
            chunk = done.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (final TimeoutException timedOut) {
            throw new IllegalStateException("Timed out after " + TimeUnit.NANOSECONDS.toSeconds(timeoutNanos)
                + " s waiting for " + what + " to load for World#getChunkAt on thread '" + Thread.currentThread().getName()
                + "'; use World#getChunkAtAsync");
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + what + " to load", interrupted);
        } catch (final ExecutionException failed) {
            throw new IllegalStateException("Loading " + what + " failed", failed.getCause());
        }
        if (chunk == null) {
            throw new IllegalStateException("Chunk not loaded when requested: " + what);
        }
        return chunk;
    }
}
