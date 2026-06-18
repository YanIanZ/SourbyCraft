package dev.iyanz.sourbycraft.antixray;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sync line-of-sight gate for the entity tracker.
 *
 * <p>Extends the SourbyCraft RayTraceAntiXray port to entities
 * (mobs, item drops, holograms via TextDisplay / NameTag-bearing
 * armor stands) and liquids surfaces. When the toggle is on, the
 * entity tracker hook (NMS patch on
 * {@code ChunkMap.TrackedEntity#updatePlayer}) calls
 * {@link #isVisibleSync(ServerPlayer, Entity)} before adding the
 * player to the tracked set, so an entity behind a wall stays
 * client-invisible.
 *
 * <p>Sync rather than async: tracker updates already run at a 1-in-N
 * cadence on the main thread, so the per-call {@code level.clip} cost
 * (one DDA traversal bounded by tracking range) is acceptable.
 * Particle / liquid gating reuses the same call.
 */
public final class EntityVisibilityCheck {

    public static final AtomicBoolean ENABLED = new AtomicBoolean(false);

    private EntityVisibilityCheck() {}

    /**
     * Returns {@code true} when the {@code entity} is on at least one
     * "test corner" of its bounding box reachable by a clear ray from
     * the {@code player}'s eye.  Vanilla mob + drop entities have AABBs
     * smaller than 2 blocks, so probing only the entity centre +
     * vertical extremes catches the common cases without ballooning
     * the per-tracker cost.
     */
    public static boolean isVisibleSync(final ServerPlayer player, final Entity entity) {
        if (player == null || entity == null) return true;
        if (player == entity) return true;
        if (!ENABLED.get()) return true;
        if (player.level() != entity.level()) return true;
        Vec3 eye = player.getEyePosition();
        AABB bb = entity.getBoundingBox();
        // 3 sample points — centre + top + feet — gives a cheap approximation
        // of full-AABB visibility without 8-corner ray tracing.
        Vec3 centre = new Vec3((bb.minX + bb.maxX) * 0.5, (bb.minY + bb.maxY) * 0.5, (bb.minZ + bb.maxZ) * 0.5);
        if (OcclusionUtil.isVisible(player.level(), eye, centre)) return true;
        Vec3 top = new Vec3(centre.x, bb.maxY - 0.05, centre.z);
        if (OcclusionUtil.isVisible(player.level(), eye, top)) return true;
        Vec3 feet = new Vec3(centre.x, bb.minY + 0.05, centre.z);
        return OcclusionUtil.isVisible(player.level(), eye, feet);
    }
}
