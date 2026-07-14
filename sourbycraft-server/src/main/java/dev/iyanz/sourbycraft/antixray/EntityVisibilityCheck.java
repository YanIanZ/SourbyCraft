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
 * <h2>Nametag + glow (ESP) coverage</h2>
 *
 * <p>This gate returns its verdict into {@code visibleToPlayer}, which
 * decides whether the player is added to the tracker's {@code seenBy}
 * set. An entity the client is NOT tracking is not rendered AT ALL — so
 * hiding an occluded mob here also suppresses its <em>nametag</em> and
 * its <em>glowing outline</em> (both are client-side renders of a tracked
 * entity, and a glow outline is precisely the ESP vector that would
 * otherwise show through walls). No extra packet suppression is needed:
 * killing the track kills the nametag and the glow with it.
 *
 * <p>Sync rather than async: tracker updates already run at a 1-in-N
 * cadence, so the per-call occlusion cost (one DDA traversal bounded by
 * tracking range, now see-through-aware via {@link OcclusionUtil}) is
 * acceptable. Glass / leaves no longer over-hide a mob a player can
 * genuinely see through, because the shared occluder test switched from
 * the collision shape to {@code isViewBlocking}.
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
    // 3 blocks (was 8): within this hard radius an entity is always shown (mount/join grace).
    // Beyond it the raytrace decides — so a mob 4-8 blocks away behind a wall is now HIDDEN from
    // ESP instead of shown, closing the "through-wall shell" a Wurst client saw at close range.
    private static final double NEAR_DISTANCE_SQUARED = 3.0 * 3.0;

    // --- Verdict cache + hysteresis -------------------------------------------------------------
    // The tracker calls this gate per tracked-entity x nearby-player EVERY TICK (ChunkMap
    // moonrise$tick -> updatePlayer), so uncached rays are the single largest recurring CPU source
    // in the authored layer (up to 3 DDA rays per pair per tick). Memoize the verdict per
    // (player, entityId) for TTL_NANOS, and require HIDE_STREAK consecutive occluded verdicts
    // before flipping a previously-visible entity to hidden — LOS boundary flapping would
    // otherwise untrack/retrack (full spawn+metadata packet bursts) every tick for mobs pacing
    // behind corners. Stale-visible lasts <= TTL (no ESP value: the entity WAS visible <500ms
    // ago); stale-hidden is fail-safe.
    //
    // Value packing: bits[63..3] = System.nanoTime() & ~7 (timestamp), bits[2..1] = occluded
    // streak (0-3), bit[0] = shown verdict. Inner maps are touched from multiple region threads
    // (a player near two regions' entities) -> synchronized(inner), the same discipline as
    // OreReveal.PENDING. Bounded per player; evicted via clear() from OreReveal's quit /
    // world-change handlers, plus TTL staleness.
    private static final java.util.Map<java.util.UUID, it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap> VERDICTS =
        new java.util.concurrent.ConcurrentHashMap<>();
    private static final long TTL_NANOS = 500_000_000L; // 10 ticks
    private static final int MAX_PER_PLAYER = 4096;
    private static final int HIDE_STREAK = 3;

    /** Evict a player's verdicts (quit / world change). */
    public static void clear(final java.util.UUID playerId) {
        VERDICTS.remove(playerId);
    }

    /** Memory-pressure hook: drop all verdicts — they rebuild within one TTL (500ms). */
    public static void trimForMemoryPressure() {
        VERDICTS.clear();
    }

    private EntityVisibilityCheck() {}

    /**
     * Logged-once guard. The tracker gate runs synchronously in {@code ChunkMap.TrackedEntity#updatePlayer}
     * on the entity-spawn/track path (which the client also drives while finishing a join). A throw here
     * must never break entity tracking — it fails open to "visible" (show the entity), logging only the
     * first failure so a recurring bug does not spam the console per tracked entity.
     */
    private static final AtomicBoolean FAILED_LOGGED = new AtomicBoolean(false);

    public static boolean isVisibleSync(final ServerPlayer player, final Entity entity) {
        // Defensive: fail-open to "visible" on any error — a bug in the occlusion gate must never hide an
        // entity that should be shown or break the entity tracker / spawn path (which also runs during a
        // client join). The safe default is to show the entity. Logged once.
        try {
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

            Vec3 eye = player.getEyePosition();
            AABB bb = entity.getBoundingBox();
            Vec3 centre = new Vec3((bb.minX + bb.maxX) * 0.5, (bb.minY + bb.maxY) * 0.5, (bb.minZ + bb.maxZ) * 0.5);

            // Near-distance bypass FIRST (the common case around a player) — computed once,
            // before the world-config map lookup so near entities pay neither.
            final double dsq = eye.distanceToSqr(centre);
            if (dsq <= NEAR_DISTANCE_SQUARED) return true;

            // SourbyCraft S4 - per-world gate + range (world-settings.<world>.anticheat.anti-xray)
            final dev.iyanz.sourbycraft.SourbyCraftWorldConfig wc =
                dev.iyanz.sourbycraft.SourbyCraftWorldConfig.get((net.minecraft.server.level.ServerLevel) player.level());
            if (!wc.entityObfuscation) return true;

            // Beyond the configured range the tracker's own range governs; skip the clip cost.
            final double range = wc.entityObfuscationRange;
            if (dsq > range * range) return true;

            // Cached verdict within TTL: no rays at all for the hit path.
            final it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap verdicts =
                VERDICTS.computeIfAbsent(player.getUUID(), id -> new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap());
            final long now = System.nanoTime();
            final long entityId = entity.getId();
            long prev;
            synchronized (verdicts) {
                prev = verdicts.getOrDefault(entityId, Long.MIN_VALUE);
                if (prev != Long.MIN_VALUE && now - (prev & ~7L) < TTL_NANOS) {
                    return (prev & 1L) != 0L;
                }
            }

            // 3 sample points — centre + top + feet — gives a cheap approximation
            // of full-AABB visibility without 8-corner ray tracing.
            boolean rayVisible = OcclusionUtil.isVisible(player.level(), eye, centre);
            if (!rayVisible) {
                Vec3 top = new Vec3(centre.x, bb.maxY - 0.05, centre.z);
                rayVisible = OcclusionUtil.isVisible(player.level(), eye, top);
            }
            if (!rayVisible) {
                Vec3 feet = new Vec3(centre.x, bb.minY + 0.05, centre.z);
                rayVisible = OcclusionUtil.isVisible(player.level(), eye, feet);
            }

            // Hysteresis: a previously-shown entity needs HIDE_STREAK consecutive occluded
            // verdicts before we actually hide it (kills untrack/retrack packet churn at LOS
            // boundaries). A fresh ray hit always shows immediately.
            final boolean shown;
            int streak = prev == Long.MIN_VALUE ? HIDE_STREAK : (int) ((prev >>> 1) & 3L);
            final boolean prevShown = prev != Long.MIN_VALUE && (prev & 1L) != 0L;
            if (rayVisible) {
                shown = true;
                streak = 0;
            } else if (prevShown && streak < HIDE_STREAK - 1) {
                shown = true;
                streak++;
            } else {
                shown = false;
                streak = HIDE_STREAK;
            }
            synchronized (verdicts) {
                if (verdicts.size() >= MAX_PER_PLAYER) verdicts.clear(); // bound memory; rebuilt in <= TTL
                verdicts.put(entityId, (now & ~7L) | ((long) Math.min(streak, 3) << 1) | (shown ? 1L : 0L));
            }
            return shown;
        } catch (Throwable t) {
            if (FAILED_LOGGED.compareAndSet(false, true)) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn("[SourX] entity-visibility check failed — "
                    + "showing entity (fail-open); further occurrences suppressed. Cause: " + t);
            }
            return true;
        }
    }
}
