package dev.iyanz.sourbycraft.perf;

import com.destroystokyo.paper.event.entity.EntityPathfindEvent;
import com.google.common.collect.ImmutableSet;
import dev.iyanz.aurora.level.SnapshotPathRegion;
import dev.iyanz.sourbycraft.execution.RegionOwnerHandoff;
import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.AmphibiousPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.bukkit.craftbukkit.util.CraftLocation;
import org.jspecify.annotations.Nullable;

/**
 * The periodic path recompute, offloaded off the region thread (minecraft feature patch 0006).
 *
 * <p>{@code PathNavigation.recomputePath} calls {@link #offload} once, behind
 * {@link AsyncPathProcessor#isEnabled()} ({@code aurora.entity.async-pathfinding}, default off), so with
 * the flag off the recompute is the vanilla synchronous one and this class is never entered. Everything
 * else that used to be written inside {@code PathNavigation} lives here: the admission checks, the
 * {@code EntityPathfindEvent}, the immutable block and mob snapshots, the submission and the
 * owner-thread delivery with its staleness checks. The navigation keeps only what has to touch its
 * private state: the pending flag, the isolated solver ({@code sourbyIsolatedPathFinder}), two lazy
 * reads ({@code sourbyMaxPathLength}, {@code sourbyVisitedNodesMultiplier}) and the write of an
 * accepted result ({@code sourbyApplyAsyncPath}).</p>
 *
 * <p>Setup runs on the region thread that owns the mob; {@link #offload} declines on any other thread.
 * Only the A* solve runs on the worker pool, and it holds neither the level nor the mob: block reads
 * come from the {@link SnapshotPathRegion}, mob reads from the {@link MobPathSnapshot} (audit ENG-8).
 * The owner handoff is built on the owner before the submission, so the completing worker only
 * schedules the delivery. Once a solve is admitted the mob keeps its current path until the result
 * lands, which is applied back on the mob's own region thread through that handoff. Every declined
 * offload, including a full or stopping pool, leaves the caller to run the vanilla synchronous
 * recompute for that slot; {@code /perf async} counts capacity declines as {@code declined} and a
 * refused submission as {@code refused}.</p>
 */
public final class AsyncPathRecompute {

    private AsyncPathRecompute() {}

    /**
     * Whether the navigation's solve would write to the mob, which no off-thread solve may do.
     *
     * <p>{@code Mob.pathfindingMalus} is a plain {@code EnumMap}. {@code AmphibiousNodeEvaluator.prepare}
     * writes WATER, WALKABLE and WATER_BORDER malus on the mob and {@code done()} restores two of them,
     * so a solve off the owning region thread both races the region's own reads and can restore a value
     * it never saved (this covers {@code AmphibiousPathNavigation} and its subclasses, the frog and
     * turtle navigations). {@code Sniffer} writes the malus from {@code onPathfindingStart}/{@code Done}.
     * Both keep the synchronous recompute. This list replaces per-class overrides in the upstream
     * sources; a new evaluator or mob that writes to the mob during a solve must be added here.</p>
     */
    public static boolean solveWritesMob(final PathNavigation navigation, final Mob mob) {
        return navigation instanceof AmphibiousPathNavigation || mob instanceof Sniffer;
    }

    /**
     * Tries to offload one periodic recompute. Called by {@code PathNavigation.recomputePath} only
     * when the flag is on, a recompute is due, {@code canUpdatePath()} holds and there is a target.
     *
     * @return {@code true} when a solve was submitted (the caller marks the recompute done and keeps
     *         the current path meanwhile); {@code false} when the caller must run the vanilla
     *         synchronous recompute now: the calling thread does not own the mob, there is no live
     *         path or a solve is already pending, the solve would write the mob, the mob is below
     *         the world, the pool has no capacity (counted as declined), the target is outside the
     *         border or the pathfind event was cancelled, or the submission was refused (counted
     *         as refused)
     */
    public static boolean offload(final PathNavigation navigation, final Mob mob, final Level level,
                                  final BlockPos targetPos, final int reachRange) {
        // Everything below reads the live mob, fires a Bukkit event, draws from the mob's random and
        // writes the navigation's pending flag, so it runs only on the mob's owner. A caller that
        // does not own it (with relaxed thread checks, a block update from a foreign thread
        // recomputes the navigations of the region that owns the block) gets the synchronous
        // recompute it would have run with the feature off.
        if (!RegionOwnership.current().isTickThreadFor(mob)) {
            return false;
        }
        // Only when the mob already has a live path to keep following meanwhile, and no solve of
        // this navigation is in flight.
        final Path current = navigation.getPath();
        if (current == null || current.isDone() || navigation.sourbyAsyncPathPending) {
            return false;
        }
        if (solveWritesMob(navigation, mob)) {
            return false;
        }
        // canUpdatePath() is not repeated here: recomputePath checked it on this thread in the
        // condition that leads to this call, with nothing in between.
        if (mob.getY() < level.getMinY()) {
            return false;
        }
        // Ask the pool for room before firing the event and paying for the snapshot: a declined
        // offload leaves the caller's synchronous recompute to run this slot, so a full pool
        // never costs a snapshot and never leaves the mob on its stale path. The decline is
        // counted under the mob's type, so saturation shows in /perf async.
        if (!AsyncPathProcessor.hasCapacity()) {
            AsyncPathProcessor.noteDeclined(MobPathSnapshot.typeKey(mob));
            return false;
        }
        // EntityPathfindEvent must fire on the region thread (Bukkit event), same as createPath.
        if (!mob.level().getWorldBorder().isWithinBounds(targetPos)
            || !new EntityPathfindEvent(mob.getBukkitEntity(), CraftLocation.toBukkit(targetPos, mob.level()), null).callEvent()) {
            return false;
        }
        final Set<BlockPos> targets = ImmutableSet.of(targetPos);
        final float maxPathLength = navigation.sourbyMaxPathLength();
        final BlockPos fromPos = mob.blockPosition();
        final int radius = (int) (maxPathLength + 8.0F);
        final SnapshotPathRegion snapshot = new SnapshotPathRegion(
            level, fromPos.offset(-radius, -radius, -radius), fromPos.offset(radius, radius, radius));
        // A fresh solver per solve: PathFinder/NodeEvaluator hold mutable A* buffers and must not be
        // shared with the region thread's own pathFinder.
        final PathFinder solver = navigation.sourbyIsolatedPathFinder(Mth.floor(maxPathLength * 16.0F));
        final float mult = navigation.sourbyVisitedNodesMultiplier();
        // Every mob field the evaluators read, captured here on the owner. The solve below is handed
        // this and the region snapshot only; it never sees `mob`, which only the completion uses.
        final MobPathSnapshot subject = MobPathSnapshot.captureDetached(mob);
        // The path the mob is following right now, as this solve's validity token. Every event that
        // invalidates the result replaces this object: the synchronous recompute (which still runs
        // while a solve is in flight, because the pending flag only skips the offload), moveTo
        // assigning or clearing a path, and stop(). The generation (patch 0047) covers the one
        // invalidation identity cannot see: a stop() that finds the path already null.
        final Path requested = navigation.getPath();
        final int requestedGeneration = navigation.sourbyNavigationGeneration();
        // Built here, on the owner: it reads the mob's Bukkit entity, which the completing worker
        // must not touch. The worker's callback below only schedules the delivery through it.
        final RegionOwnerHandoff handoff = RegionOwnerHandoff.forEntity(mob);
        navigation.sourbyAsyncPathPending = true;
        // Attributed to the snapshot's entity type, with no per-thread hand-off (AsyncPathTypeStats).
        final CompletableFuture<Path> solving =
            AsyncPathProcessor.trySubmit(subject, () -> solver.findPath(snapshot, subject, targets, maxPathLength, reachRange, mult));
        if (solving == null) {
            // Another region filled the pool since hasCapacity(): run the synchronous recompute
            // now instead of keeping the stale path. Rare; the pathfind event then fires twice.
            navigation.sourbyAsyncPathPending = false;
            return false;
        }
        solving.whenComplete((path, err) -> AsyncPathCompletion.deliver(
            handoff,
            owner -> deliver(navigation, mob, owner, requested, requestedGeneration, path, reachRange),
            () -> navigation.sourbyAsyncPathPending = false));
        return true;
    }

    /**
     * Applies a finished solve on the owner, if it is still the one the navigation asked for, and
     * counts the outcome ({@code /perf async} applied, or discarded by the first check that failed).
     * A discarded result is not resubmitted.
     */
    static void deliver(final PathNavigation navigation, final Mob mob, final Object owner,
                        final @Nullable Path requested, final int requestedGeneration,
                        final @Nullable Path path, final int reachRange) {
        // The region hands back the entity as it is now. A dimension transfer replaces the
        // underlying entity, so a solve computed for the old one must not be applied: this
        // navigation belongs to the entity that was replaced, and its level, position and target
        // are no longer the live ones.
        if (!AsyncPathValidity.sameSubject(owner, mob)) {
            AsyncPathProcessor.noteDelivery(AsyncPathProcessor.Delivery.STALE_SUBJECT);
            return;
        }
        // Something replaced the path while this solve was running, so the result describes a
        // route the navigation is no longer taking. Applying it would overwrite a newer synchronous
        // recompute, undo a retarget, or resurrect a navigation that was stopped.
        if (!AsyncPathValidity.stillRequested(navigation.getPath(), requested)) {
            AsyncPathProcessor.noteDelivery(AsyncPathProcessor.Delivery.STALE_PATH);
            return;
        }
        // A stop() that found the path already null (or any other invalidation) since the token
        // was taken: the path object cannot show it, the generation does.
        if (!AsyncPathValidity.sameGeneration(navigation.sourbyNavigationGeneration(), requestedGeneration)) {
            AsyncPathProcessor.noteDelivery(AsyncPathProcessor.Delivery.STALE_GENERATION);
            return;
        }
        if (path != null && path.getTarget() != null) {
            navigation.sourbyApplyAsyncPath(path, reachRange);
            AsyncPathProcessor.noteDelivery(AsyncPathProcessor.Delivery.APPLIED);
        } else {
            AsyncPathProcessor.noteDelivery(AsyncPathProcessor.Delivery.NO_PATH);
        }
    }

    /**
     * Puts the live evaluator back after {@code createPathFinder} built an isolated one.
     *
     * <p>{@code createPathFinder} assigns the evaluator it creates to {@code PathNavigation.nodeEvaluator}
     * as a side effect. That field is the live one: {@code setCanOpenDoors}, {@code canFloat} and the
     * other flag accessors read and write it, and the navigation's own {@code pathFinder} solves with
     * it. So the live evaluator is restored, and the new one takes over the live door, float and
     * fence flags, which a fresh evaluator would otherwise have at their defaults.</p>
     *
     * @return the evaluator the navigation must hold again ({@code live})
     */
    public static @Nullable NodeEvaluator restoreLiveEvaluator(final @Nullable NodeEvaluator live,
                                                               final @Nullable NodeEvaluator isolated) {
        if (isolated != null && live != null && isolated != live) {
            isolated.setCanPassDoors(live.canPassDoors());
            isolated.setCanOpenDoors(live.canOpenDoors());
            isolated.setCanFloat(live.canFloat());
            isolated.setCanWalkOverFences(live.canWalkOverFences());
        }
        return live;
    }
}
