package dev.iyanz.sourbycraft.antixray;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import dev.iyanz.sourbycraft.util.VirtualExecutor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-player async ray-trace driver for the anti-xray visibility cache.
 *
 * <p>SourbyCraft port of RayTraceAntiXray's {@code RayTraceCallable}.
 * For each ore position the chunk-packet obfuscator wants a verdict on,
 * the worker submits a {@code level.clip} line-of-sight check on
 * {@link VirtualExecutor}. The result lands in {@link VisibilityCache}
 * and the next chunk-packet build consults the cache.
 *
 * <p>Worker is single-shot per ore: callers submit a (player, ore) pair,
 * the worker dispatches on a virtual thread, and writes the result.
 * No timer / scheduler is built in — the integration point picks the
 * cadence (e.g. on every chunk send, on player move, on tick).
 *
 * <p>Submission is non-blocking and idempotent: re-submitting the same
 * pair before the previous run lands re-runs the check (acceptable
 * cost; chunk-send dedup is the integrator's job).
 */
public final class RayTraceWorker {

    /** When true the worker is enabled. Read on every submit so operator can flip at runtime. */
    public static final AtomicBoolean ENABLED = new AtomicBoolean(false);

    private RayTraceWorker() {}

    /** Async check: from player eye to ore centre. Result lands in {@link VisibilityCache}. */
    public static void submit(final ServerPlayer player, final BlockPos orePos) {
        if (!ENABLED.get() || player == null || orePos == null) return;
        final ServerLevel level = player.level();
        if (level == null) return;
        final java.util.UUID playerId = player.getUUID();
        final Vec3 eye = player.getEyePosition();
        final Vec3 ore = Vec3.atCenterOf(orePos);
        final long key = orePos.asLong();
        VirtualExecutor.run(() -> {
            try {
                if (OcclusionUtil.isVisible(level, eye, ore)) {
                    VisibilityCache.markVisible(playerId, key);
                }
            } catch (Throwable t) {
                SourbyLogger.warn("RayTraceWorker check failed: " + t.getMessage());
            }
        });
    }
}
