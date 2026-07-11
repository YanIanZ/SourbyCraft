package dev.iyanz.sourbycraft.antixray;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sync line-of-sight gate for the entity tracker.
 *
 * <p>Extends the SourbyCraft RayTraceAntiXray port to mobs, item drops
 * and similar trackable entities. When the toggle is on, the entity
 * tracker hook (NMS patch on
 * {@code ChunkMap.TrackedEntity#updatePlayer}) calls
 * {@link #isVisibleSync(ServerPlayer, Entity)} before adding the
 * player to the tracked set, so an entity sitting behind a wall stays
 * client-invisible.
 *
 * <h2>Exemptions</h2>
 *
 * <p>A blanket "occluded → hide" rule blinds players to a lot of
 * legitimate gameplay surface. We never hide:
 * <ul>
 *   <li>Other players. Hiding players via anti-xray creates a giant
 *       PvP advantage and breaks the whole player-list ↔ render-list
 *       contract.</li>
 *   <li>The {@code Display} entity family (TextDisplay, ItemDisplay,
 *       BlockDisplay) which is the modern hologram backbone. These
 *       have near-zero AABBs and their visual extent reaches far
 *       beyond the AABB centre, so a single ray to the centre is
 *       worthless.</li>
 *   <li>{@link ArmorStand}s — used as legacy holograms / NPC bodies
 *       by ~every skyblock plugin (DecentHolograms, Citizens, custom
 *       shops). Hiding them collapses the visible UI.</li>
 *   <li>{@link HangingEntity} (ItemFrame, GlowItemFrame, Painting) —
 *       mounted on a block by definition, so the centre ray ALWAYS
 *       hits the block they're attached to and the entity would
 *       always be hidden.</li>
 *   <li>Entities within {@code NEAR_DISTANCE_SQUARED} blocks of the
 *       player's eye. Avoids edge cases on world join, dimension
 *       transitions, and SWM world load where the player's eye position
 *       and the entity centre are technically separated by a single
 *       boundary block that has not yet streamed to the client.</li>
 * </ul>
 *
 * <p>Sync rather than async: tracker updates already run at a 1-in-N
 * cadence, so the per-call {@code level.clip} cost (one DDA traversal
 * bounded by tracking range) is acceptable.
 *
 * <h2>Folia threading</h2>
 *
 * <p>On the Folia base {@code ChunkMap.TrackedEntity#updatePlayer}
 * runs on the <em>region</em> thread that owns the tracked entity, not
 * a single main thread. That is exactly the thread with exclusive
 * ownership of the entity's chunks, so the {@code level.clip} ray in
 * {@link OcclusionUtil#isVisible} reads only region-local block state —
 * no cross-region access, no lock. The only shared state touched is
 * {@link #ENABLED} (a lock-free {@code AtomicBoolean}) and
 * {@link dev.iyanz.sourbycraft.SourbyCraftWorldConfig#get} (a
 * Folia-safe {@code ConcurrentHashMap} lookup, same as the ore core),
 * so concurrent region ticks never serialize on this check.
 */
public final class EntityVisibilityCheck {

    public static final AtomicBoolean ENABLED = new AtomicBoolean(false);

    /** Entities closer than 8 blocks to the player's eye are always shown. */
    private static final double NEAR_DISTANCE_SQUARED = 8.0 * 8.0;

    private EntityVisibilityCheck() {}

    public static boolean isVisibleSync(final ServerPlayer player, final Entity entity) {
        if (player == null || entity == null) return true;
        if (player == entity) return true;
        if (!ENABLED.get()) return true;
        if (player.level() != entity.level()) return true;

        // Exempt entity classes whose gameplay role is broken by occlusion hiding.
        // Cheap instanceof checks stay ahead of the world-config map lookup.
        if (entity instanceof Player) return true;
        if (entity instanceof Display) return true;
        if (entity instanceof ArmorStand) return true;
        if (entity instanceof HangingEntity) return true;

        // SourbyCraft S4 - per-world gate + range (world-settings.<world>.anticheat.anti-xray)
        final dev.iyanz.sourbycraft.SourbyCraftWorldConfig wc =
            dev.iyanz.sourbycraft.SourbyCraftWorldConfig.get((net.minecraft.server.level.ServerLevel) player.level());
        if (!wc.entityObfuscation) return true;

        Vec3 eye = player.getEyePosition();
        AABB bb = entity.getBoundingBox();
        Vec3 centre = new Vec3((bb.minX + bb.maxX) * 0.5, (bb.minY + bb.maxY) * 0.5, (bb.minZ + bb.maxZ) * 0.5);

        // Near-distance bypass: avoid edge cases on join / dimension / SWM load.
        if (eye.distanceToSqr(centre) <= NEAR_DISTANCE_SQUARED) return true;

        // Beyond the configured range the tracker's own range governs; skip the clip cost.
        final double range = wc.entityObfuscationRange;
        if (eye.distanceToSqr(centre) > range * range) return true;

        // 3 sample points — centre + top + feet — gives a cheap approximation
        // of full-AABB visibility without 8-corner ray tracing.
        if (OcclusionUtil.isVisible(player.level(), eye, centre)) return true;
        Vec3 top = new Vec3(centre.x, bb.maxY - 0.05, centre.z);
        if (OcclusionUtil.isVisible(player.level(), eye, top)) return true;
        Vec3 feet = new Vec3(centre.x, bb.minY + 0.05, centre.z);
        return OcclusionUtil.isVisible(player.level(), eye, feet);
    }
}
