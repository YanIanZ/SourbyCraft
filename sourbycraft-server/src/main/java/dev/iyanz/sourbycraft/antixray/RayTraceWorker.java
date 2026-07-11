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
 * <p>SourbyCraft port of RayTraceAntiXray's {@code RayTraceCallable}.
 * For each ore position the chunk-packet obfuscator wants a verdict on,
 * the worker submits line-of-sight checks on {@link VirtualExecutor}.
 * The result lands in {@link VisibilityCache} and the next chunk-packet
 * build consults the cache.
 *
 * <p><b>Face/corner targeting (anti-bypass):</b> a single ray to the ore
 * CENTRE misses partially-exposed ores — if only one face pokes into a
 * cave, the centre is buried behind the ore's own solid half or behind a
 * neighbour, so a centre-only test would leave a genuinely-visible ore
 * hidden (and, symmetrically, a cheat that can see a sliver of an ore
 * around a corner is what we must catch). The worker therefore probes the
 * ore centre first (cheapest, the common fully-exposed case) and then, on
 * a miss, each of the 6 face-midpoints that face the player. The ore is
 * marked visible as soon as ANY probe reaches it. All probes run on the
 * async virtual thread, so this adds NO main-thread / per-packet cost —
 * only more work inside the already-off-thread worker, bounded to at most
 * 1 + 3 rays (only the up-to-3 faces oriented toward the eye are tested).
 *
 * <p>Worker is single-shot per ore: callers submit a (player, ore) pair,
 * the worker dispatches on a virtual thread, and writes the result.
 * No timer / scheduler is built in — the integration point picks the
 * cadence (e.g. on every chunk send, on player move, on tick).
 */
public final class RayTraceWorker {

    /** When true the worker is enabled. Read on every submit so operator can flip at runtime. */
    public static final AtomicBoolean ENABLED = new AtomicBoolean(false);

    /** Inset from the block face so the probe point sits just outside the ore, not on its own surface. */
    private static final double FACE_INSET = 0.5 + 1.0e-3;

    private RayTraceWorker() {}

    /** Async check: from player eye to ore. Result lands in {@link VisibilityCache} when any probe sees it. */
    public static void submit(final ServerPlayer player, final BlockPos orePos) {
        if (!ENABLED.get() || player == null || orePos == null) return;
        final ServerLevel level = player.level();
        if (level == null) return;
        final java.util.UUID playerId = player.getUUID();
        // getEyePosition() already accounts for the player's pose (standing/sneaking/swimming eye height),
        // so a sneaking player's lowered eye is used verbatim — no manual sneak adjustment needed.
        final Vec3 eye = player.getEyePosition();
        final Vec3 centre = Vec3.atCenterOf(orePos);
        final boolean fluidObscures = dev.iyanz.sourbycraft.SourbyCraftConfig.fluidObscures
            && dev.iyanz.sourbycraft.SourbyCraftWorldConfig.get(level).fluidObscures;
        final long key = orePos.asLong();
        final int ox = orePos.getX(), oy = orePos.getY(), oz = orePos.getZ();
        VirtualExecutor.run(() -> {
            try {
                // Centre first: the common fully-exposed case, one ray.
                if (OcclusionUtil.isVisible(level, eye, centre, fluidObscures)) {
                    VisibilityCache.markVisible(playerId, key);
                    return;
                }
                // Partially-exposed fallback: probe the face-midpoints that face the eye. Only the
                // up-to-3 faces whose outward normal points toward the player can possibly be seen,
                // so we skip the 3 back faces. Any hit reveals the ore.
                for (final Direction dir : Direction.values()) {
                    // Face oriented toward the eye? (dot of face normal with eye->centre-reversed direction).
                    final double faceCx = ox + 0.5 + dir.getStepX() * FACE_INSET;
                    final double faceCy = oy + 0.5 + dir.getStepY() * FACE_INSET;
                    final double faceCz = oz + 0.5 + dir.getStepZ() * FACE_INSET;
                    // Only test a face the eye is on the outside of (avoids the 3 guaranteed-occluded back faces).
                    final double toFaceX = faceCx - eye.x, toFaceY = faceCy - eye.y, toFaceZ = faceCz - eye.z;
                    if (toFaceX * dir.getStepX() + toFaceY * dir.getStepY() + toFaceZ * dir.getStepZ() >= 0.0) {
                        continue; // eye is behind this face -> cannot see it
                    }
                    if (OcclusionUtil.isVisible(level, eye, new Vec3(faceCx, faceCy, faceCz), fluidObscures)) {
                        VisibilityCache.markVisible(playerId, key);
                        return;
                    }
                }
            } catch (Throwable t) {
                SourbyLogger.warn("RayTraceWorker check failed: " + t.getMessage());
            }
        });
    }
}
