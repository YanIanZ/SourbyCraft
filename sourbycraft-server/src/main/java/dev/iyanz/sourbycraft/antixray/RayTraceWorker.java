package dev.iyanz.sourbycraft.antixray;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import dev.iyanz.sourbycraft.util.VirtualExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-player async ray-trace driver for the anti-xray visibility cache.
 *
 * <p>SourX engine — SourbyCraft's port of stonar96/RayTraceAntiXray's {@code RayTraceCallable}.
 * For each ore position the reveal cycle wants a verdict on, the worker
 * runs line-of-sight checks on {@link VirtualExecutor}. The result lands
 * in {@link VisibilityCache} and the next reveal cycle consults it.
 *
 * <p><b>Face/corner targeting (anti-bypass):</b> a single ray to the ore
 * CENTRE misses partially-exposed ores — if only one face pokes into a
 * cave, the centre is buried behind the ore's own solid half or behind a
 * neighbour, so a centre-only test would leave a genuinely-visible ore
 * hidden (and, symmetrically, a cheat that can see a sliver of an ore
 * around a corner is what we must catch). The worker therefore probes the
 * ore centre first (cheapest, the common fully-exposed case) and then, on
 * a miss, each of the ore's own face-midpoints that face the player. The
 * ore is marked visible as soon as ANY probe reaches it. All probes run on
 * the async virtual thread; bounded to at most 1 + 3 rays per ore.
 *
 * <p><b>Batched:</b> the reveal cycle hands over its whole per-player
 * worklist in ONE virtual-thread task per cycle (not one task per ore) —
 * same latency, ~10x fewer scheduler round-trips.
 *
 * <p><b>Epoch-guarded:</b> the player's {@link VisibilityCache#epoch} is
 * captured at submit; results landing after a teleport/world-change/quit
 * clear are discarded by {@code markVisible}, closing the stale-eye leak.
 *
 * <p><b>Block reads:</b> {@link OcclusionUtil} reads loaded chunks only
 * (unloaded = occluding, fail-safe hidden) and never touches region-local
 * world data, so rays are legal from any thread; the palette read is racy
 * versus the owning region (accepted: worst case one wrong verdict for one
 * cycle, self-correcting).
 */
public final class RayTraceWorker {

    /** When true the worker is enabled. Read on every submit so operator can flip at runtime. */
    public static final AtomicBoolean ENABLED = new AtomicBoolean(false);

    /**
     * Inset from the block face so the probe point sits just INSIDE the ore's own face — NOT
     * outside it. An outward inset (+epsilon) would place the probe in the NEIGHBOUR block, and
     * {@link OcclusionUtil}'s target-block self-exemption would then exempt that neighbour: a
     * solid block covering the player-facing side would never occlude, revealing the ore through
     * a 1-block wall (verified leak). Inside the ore, the exempted block is the ore itself and a
     * covering neighbour correctly blocks the ray.
     */
    private static final double FACE_INSET = 0.5 - 1.0e-3;

    /** Direction.values() clones the array per call — probe loop runs per occluded ore, forever. */
    private static final Direction[] DIRECTIONS = Direction.values();

    // Failure logging: full stack once per throwable class, then a 10s-throttled one-liner —
    // a persistent fault must not turn into a per-ray WARN flood ("check failed: null" spam).
    private static final java.util.Set<String> LOGGED_FAILURES =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicLong LAST_WARN_NANOS = new java.util.concurrent.atomic.AtomicLong();

    private static void logFailure(final Throwable t) {
        if (LOGGED_FAILURES.add(t.getClass().getName())) {
            SourbyLogger.error("RayTraceWorker check failed (first occurrence of "
                + t.getClass().getSimpleName() + ", further ones throttled)", t);
            return;
        }
        final long now = System.nanoTime();
        final long last = LAST_WARN_NANOS.get();
        if (now - last > 10_000_000_000L && LAST_WARN_NANOS.compareAndSet(last, now)) {
            SourbyLogger.warn("RayTraceWorker check failed: " + t);
        }
    }

    private RayTraceWorker() {}

    /**
     * Async check of a whole per-cycle worklist: from the player's eye to each ore. Confirmed
     * positions land in {@link VisibilityCache}. Call from the thread owning the player (the
     * reveal cycle's region hop) so the eye read is sound.
     *
     * @param keys  packed BlockPos keys; only the first {@code count} entries are read
     */
    public static void submitBatch(final ServerPlayer player, final long[] keys, final int count) {
        if (!ENABLED.get() || player == null || keys == null || count <= 0) return;
        final ServerLevel level = player.level();
        if (level == null) return;
        final java.util.UUID playerId = player.getUUID();
        final long epoch = VisibilityCache.epoch(playerId);
        // getEyePosition() already accounts for the player's pose (standing/sneaking/swimming eye
        // height), so a sneaking player's lowered eye is used verbatim.
        final Vec3 eye = player.getEyePosition();
        final boolean fluidObscures = dev.iyanz.sourbycraft.SourbyCraftConfig.fluidObscures
            && dev.iyanz.sourbycraft.SourbyCraftWorldConfig.get(level).fluidObscures;
        final long[] work = java.util.Arrays.copyOf(keys, count);
        VirtualExecutor.run(() -> {
            try {
                for (final long key : work) {
                    if (checkOne(level, eye, key, fluidObscures)) {
                        VisibilityCache.markVisible(playerId, key, epoch);
                    }
                }
            } catch (Throwable t) {
                logFailure(t);
            }
        });
    }

    /** One ore: centre ray, then the up-to-3 player-facing face-midpoint probes. */
    private static boolean checkOne(final ServerLevel level, final Vec3 eye, final long key,
                                    final boolean fluidObscures) {
        final int ox = BlockPos.getX(key), oy = BlockPos.getY(key), oz = BlockPos.getZ(key);
        final Vec3 centre = new Vec3(ox + 0.5, oy + 0.5, oz + 0.5);
        // Centre first: the common fully-exposed case, one ray.
        if (OcclusionUtil.isVisible(level, eye, centre, fluidObscures)) {
            return true;
        }
        // Partially-exposed fallback: probe the face-midpoints that face the eye. Only the
        // up-to-3 faces whose outward normal points toward the player can possibly be seen,
        // so we skip the 3 back faces. Any hit reveals the ore.
        for (final Direction dir : DIRECTIONS) {
            final double faceCx = ox + 0.5 + dir.getStepX() * FACE_INSET;
            final double faceCy = oy + 0.5 + dir.getStepY() * FACE_INSET;
            final double faceCz = oz + 0.5 + dir.getStepZ() * FACE_INSET;
            // Only test a face the eye is on the outside of (avoids the 3 guaranteed-occluded back faces).
            final double toFaceX = faceCx - eye.x, toFaceY = faceCy - eye.y, toFaceZ = faceCz - eye.z;
            if (toFaceX * dir.getStepX() + toFaceY * dir.getStepY() + toFaceZ * dir.getStepZ() >= 0.0) {
                continue; // eye is behind this face -> cannot see it
            }
            if (OcclusionUtil.isVisible(level, eye, new Vec3(faceCx, faceCy, faceCz), fluidObscures)) {
                return true;
            }
        }
        return false;
    }
}
