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

    public static boolean canSee(final ServerPlayer player, final double x, final double y, final double z) {
        if (player == null) return true;
        if (!ENABLED.get()) return true;
        if (player.level() == null) return true;
        Vec3 eye = player.getEyePosition();
        // Near-distance bypass keeps the player's own footstep / breath / interact particles
        // visible even when the camera momentarily sits "behind" a wall as the chunk packets
        // stream in (e.g. join / dimension transition / SWM world load).
        if (eye.distanceToSqr(x, y, z) <= NEAR_DISTANCE_SQUARED) return true;
        Vec3 target = new Vec3(x, y, z);
        return OcclusionUtil.isVisible(player.level(), eye, target);
    }
}
