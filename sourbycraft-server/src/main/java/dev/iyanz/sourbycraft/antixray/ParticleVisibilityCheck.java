package dev.iyanz.sourbycraft.antixray;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sync line-of-sight gate for vanilla particle broadcast.
 *
 * <p>Wired into the NMS patch on {@code ServerLevel#sendParticles}
 * (private per-player overload) so that when the toggle is on, a
 * particle emitted behind solid blocks from the receiver's POV is
 * dropped before {@code player.connection.send(packet)}. Saves both
 * the network frame and the wallhack signal that ore-pillar /
 * door-frame particles would otherwise leak.
 *
 * <p>The check is intentionally a single ray (eye -> particle origin).
 * Particle clouds spawn a volume around that origin, but the origin
 * is the canonical reference position the client uses for distance
 * culling, so a single ray is the cheapest correct approximation.
 *
 * <h2>Folia threading</h2>
 *
 * <p>{@code ServerLevel#sendParticles} runs on the <em>region</em>
 * thread that owns the emitting area, which is the thread with
 * exclusive ownership of the blocks the {@code level.clip} ray in
 * {@link OcclusionUtil#isVisible} traverses — region-local read, no
 * lock. The only shared state is {@link #ENABLED} (a lock-free
 * {@code AtomicBoolean}), so this gate never serializes concurrent
 * region ticks. Cheap in the hot packet path: one DDA ray only after
 * the near-distance bypass and the toggle read.
 */
public final class ParticleVisibilityCheck {

    public static final AtomicBoolean ENABLED = new AtomicBoolean(false);

    private ParticleVisibilityCheck() {}

    /** Particles closer than 8 blocks to the player's eye are always shown. */
    private static final double NEAR_DISTANCE_SQUARED = 8.0 * 8.0;

    /** Beyond this the gate fails open — no 512-block cross-region rays for forced particles. */
    private static final double GATE_RANGE_SQUARED = 64.0 * 64.0;

    /**
     * Logged-once guard. The particle gate runs synchronously in {@code ServerLevel#sendParticles};
     * a throw must never break particle broadcast. Fails open to "can see" (send the particle),
     * logging only the first failure.
     */
    private static final AtomicBoolean FAILED_LOGGED = new AtomicBoolean(false);

    public static boolean canSee(final ServerPlayer player, final double x, final double y, final double z) {
        // Defensive: fail-open to "visible" on any error — a bug in the particle occlusion gate must
        // never drop a legitimate particle or break the particle-send path. Logged once.
        try {
            if (player == null) return true;
            if (!ENABLED.get()) return true;
            if (player.level() == null) return true;
            Vec3 eye = player.getEyePosition();
            // Near-distance bypass keeps the player's own footstep / breath / interact particles
            // visible even when the camera momentarily sits "behind" a wall as the chunk packets
            // stream in (e.g. join / dimension transition / SWM world load).
            final double dsq = eye.distanceToSqr(x, y, z);
            if (dsq <= NEAR_DISTANCE_SQUARED) return true;
            // Clamp the gate: force-particles (overrideLimiter) reach 512 blocks — a 512-step DDA per
            // receiver is needless cost and the ray would cross region borders / unloaded chunks.
            // Beyond 64 blocks fail open (send): distance culling on the client governs there anyway.
            if (dsq > GATE_RANGE_SQUARED) return true;
            Vec3 target = new Vec3(x, y, z);
            return OcclusionUtil.isVisible(player.level(), eye, target);
        } catch (Throwable t) {
            if (FAILED_LOGGED.compareAndSet(false, true)) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn("[SourX] particle-visibility check failed — "
                    + "sending particle (fail-open); further occurrences suppressed. Cause: " + t);
            }
            return true;
        }
    }
}
