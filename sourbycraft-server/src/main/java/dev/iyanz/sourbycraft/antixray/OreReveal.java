package dev.iyanz.sourbycraft.antixray;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.SourbyCraftWorldConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SourbyEngine — SourbyCraft's DYNAMIC ray-trace anti-xray engine.
 *
 * <p>Where the earlier SourX layer revealed an ore permanently the first time the player got
 * line-of-sight to it (a persistent cache), SourbyEngine treats visibility as a live state that is
 * continuously re-validated. This closes the "peek once, x-ray forever" hole: an ore is shown ONLY
 * while the player can actually see it, and is re-hidden the moment sight is lost.
 *
 * <p>Exposed-ore hide/reveal layer above Paper's anti-xray engine.
 *
 * <p>Paper engine-mode 1 hides ores that are fully enclosed; ores exposed to a cave surface still
 * leak through walls. This layer hides those on chunk send (per-player fake block updates) and:
 * <ul>
 *   <li><b>reveals</b> each one when {@link RayTraceWorker} confirms actual line of sight, or
 *       instantly within {@value #NEAR_DISTANCE} blocks (mining UX);</li>
 *   <li><b>re-hides</b> it again once the player has lost sight of it for
 *       {@link VisibilityCache#HIDE_STREAK} consecutive re-validations (hysteresis kills flicker) —
 *       so walking behind a wall re-conceals the ore instead of leaving it permanently x-rayable.</li>
 * </ul>
 * See {@link #revealOnRegion} for the two-phase reveal/re-hide state machine.
 *
 * <h2>Folia threading model</h2>
 * <ul>
 *   <li>{@link #onChunkSent} — region thread owning the sent chunk.</li>
 *   <li>{@link #onNearbyReveal} — region thread of a block change (NMS hook, post-change).</li>
 *   <li>{@link #tickCycle} — global-region thread, which only READS the player list and hops the
 *       actual reveal work onto each player's owning region via the entity scheduler
 *       ({@link #revealOnRegion}); the global thread never touches world block state.</li>
 *   <li>{@link RayTraceWorker} — virtual threads, loaded-chunk-only reads.</li>
 * </ul>
 * Per-player {@link #PENDING} sets and the per-level scan cache are guarded by their own
 * monitors; packet sends are thread-safe.
 */
public final class OreReveal implements Listener {

    // 3 blocks (was 8): within this the ore is revealed without a raytrace (mining grace — you are
    // about to break through to it). Beyond it ONLY a raytrace-confirmed line-of-sight reveals it,
    // so ores 4-8 blocks behind a wall stay hidden from a nearby Wurst xray instead of showing.
    private static final double NEAR_DISTANCE = 3.0;
    private static final double NEAR_DISTANCE_SQUARED = NEAR_DISTANCE * NEAR_DISTANCE;

    /**
     * Per-player SourbyEngine visibility state — a faithful adaptation of stonar96/RayTraceAntiXray's
     * per-player {@code chunks}/{@code ChunkBlocks} model. {@code blocks} maps every tracked hideable
     * position (BlockPos.asLong) to its current client state: {@code true} = spoofed to stone (the
     * x-ray view), {@code false} = revealed real. Chunk sends / block changes add positions as hidden;
     * the reveal cycle raytraces from a single eye snapshot and flips them.
     */
    private static final class RevealState {
        final it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap blocks = new it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap();
        volatile long epoch;             // bumped on teleport/world-change/quit; stale async results dropped
        RevealState() { blocks.defaultReturnValue(true); }
    }

    /** Per-player reveal state. The {@code blocks} map is guarded by {@code synchronized(state.blocks)}. */
    private static final Map<UUID, RevealState> STATE = new ConcurrentHashMap<>();

    private static RevealState stateFor(final UUID id) {
        return STATE.computeIfAbsent(id, k -> new RevealState());
    }

    /**
     * Per-chunk exposed scan cache — which ores/fluids in a chunk are cave-exposed is
     * player-independent, so the ~4096-block/section scan runs ONCE per chunk and every
     * player/send reuses the result. Keyed by {@link ServerLevel} identity; evicted on chunk
     * unload, world unload, block-change invalidation, TTL, and a size cap.
     */
    private static final Map<ServerLevel, Long2ObjectOpenHashMap<CacheEntry>> SCAN_CACHE = new ConcurrentHashMap<>();
    /** Ores and fluids are tracked separately so liquids get their own (smaller) pending budget. */
    private record CacheEntry(long[] ores, long[] fluids, long tick) {}
    private static final long[] EMPTY = new long[0];
    private static final CacheEntry EMPTY_ENTRY = new CacheEntry(EMPTY, EMPTY, 0L);
    /** Safety cap so a missed unload event can't grow a level's cache unbounded. */
    private static final int MAX_CACHED_CHUNKS_PER_LEVEL = 16384;

    /**
     * Cached per-level immutable copy of Paper's hidden-blocks set (all-blocks mode). The paper
     * world config is immutable after world construction; without this every chunk send pays a
     * ~60-entry Set.copyOf. PerWorldHolder gives world-unload eviction for free.
     */
    private static final dev.iyanz.sourbycraft.core.PerWorldHolder<java.util.Set<Block>> EXTRA_HIDDEN =
        new dev.iyanz.sourbycraft.core.PerWorldHolder<>();

    private OreReveal() {}

    /**
     * Memory-pressure hook (perf engine, RED/EMERGENCY): drop half of every level's scan cache.
     * Purely a soft cache — entries rebuild lazily on the next chunk send; freeing them trades a
     * few re-scans for immediately reclaimable heap when the sensor says memory is the problem.
     */
    public static void trimCachesForMemoryPressure() {
        for (final Long2ObjectOpenHashMap<CacheEntry> perLevel : SCAN_CACHE.values()) {
            synchronized (perLevel) {
                final var it = perLevel.values().iterator();
                for (int i = 0, target = perLevel.size() / 2; i < target && it.hasNext(); i++) {
                    it.next();
                    it.remove();
                }
            }
        }
    }

    public static void register(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new OreReveal(), plugin);
        long interval = Math.max(1, SourbyCraftConfig.raytraceIntervalTicks);
        // Folia: no Bukkit global scheduler exists. Drive tickCycle from the global-region scheduler;
        // it only reads the player list and hops per-player work to owning regions.
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE,
            task -> tickCycle(), interval, interval);
        plugin.getLogger().info("[SourbyEngine] RayTraceAntiXray-model anti-xray "
            + (RayTraceWorker.ENABLED.get() ? "ENABLED" : "disabled")
            + " (interval=" + interval + "t ray-distance=" + SourbyCraftConfig.raytraceDistance
            + " rehide-distance=" + SourbyCraftConfig.rehideDistance
            + " frustum-cull=" + SourbyCraftConfig.frustumCulling + ")");
        if (RayTraceWorker.ENABLED.get()) {
            // This runs from the post-config actuator hook, which fires BEFORE DedicatedServer#loadLevel
            // builds any world — so Bukkit.getWorlds() is (almost) always empty here and a live
            // paperConfig() check would be a false negative. Consult the on-disk paper-world-defaults.yml
            // that every world's controller is about to be built from (already reflecting the
            // baritone-defense engine-mode-2 seed if it ran); fall back to any already-loaded world.
            boolean anyPaperAntiXray = PaperAntiXrayDefense.willPaperAntiXrayBeEnabled();
            if (!anyPaperAntiXray) {
                for (org.bukkit.World w : Bukkit.getWorlds()) {
                    if (((org.bukkit.craftbukkit.CraftWorld) w).getHandle().paperConfig().anticheat.antiXray.enabled) {
                        anyPaperAntiXray = true;
                        break;
                    }
                }
            }
            if (!anyPaperAntiXray) {
                plugin.getLogger().warning("[SourX] raytrace enabled but no world has paper anti-xray enabled — "
                    + "ore reveal is a complementary layer and stays inert until anticheat.anti-xray.enabled: true "
                    + "in paper-world-defaults.yml (enable antixray.baritone-defense to seed it automatically; "
                    + "buried ores would leak anyway without the Paper engine).");
            }
        }
    }

    /**
     * Logged-once guard: a bug in this anti-xray layer must NEVER break the chunk send it hooks
     * (the join chunk-burst runs through here for every spawn chunk). {@link #onChunkSent} catches
     * everything and returns the SAFE default (the real chunk was already sent by the NMS caller
     * before this hook — we simply skip the ore-hide overlay), logging the first failure only so a
     * recurring bug does not spam the console once per chunk during a join.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean CHUNK_SENT_FAILED_LOGGED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /** NMS hook (PlayerChunkSender.sendChunk, region thread, after the chunk packet went out). */
    public static void onChunkSent(final ServerPlayer player, final LevelChunk chunk) {
        // Defensive: this runs synchronously in the chunk-send path for EVERY chunk (incl. the spawn
        // chunks a client needs to finish "Joining world"). A throw here would propagate into the NMS
        // sender and stall/abort the join chunk-burst — the real chunk was already sent, so anything
        // going wrong in the ore-hide overlay must fail-open (leave the chunk unmodified), never break
        // the send. Logged once.
        try {
            if (!RayTraceWorker.ENABLED.get() || player == null || chunk == null) return;
            final ServerLevel level = (ServerLevel) chunk.getLevel();
            if (!level.chunkPacketBlockController.shouldModify(player, chunk)) return; // respect paper.antixray.bypass
            final SourbyCraftWorldConfig wc = SourbyCraftWorldConfig.get(level);
            final boolean fluidObscures = SourbyCraftConfig.fluidObscures && wc.fluidObscures;

            // Player-independent scan, computed once per chunk and cached. CACHE HIT: apply inline
            // (cheap — iterate the small exposed lists). CACHE MISS: the ~98k-block section scan used
            // to run HERE, on the region thread, inside the chunk-send burst — with two players
            // exploring, a continuous stream of fresh chunks each paid a multi-ms scan on the ticking
            // thread (a measurable slice of the sub-20-TPS exploration grind). The miss path now
            // scans on a virtual thread and applies the hide when done. Safety: ORES are already
            // stone in the chunk packet itself (paper patch 0020), so the async gap leaks nothing
            // for ores; chests/liquids get their stone update a few ms later — an acceptable window
            // (the same class of gap as Paper's own async obfuscation executor).
            final java.util.Set<Block> extraHidden = extraHiddenFor(level, wc);
            final CacheEntry cached = getCachedExposed(level, chunk);
            if (cached != null) {
                if (cached.ores().length == 0 && cached.fluids().length == 0) return;
                hideExposedFor(player, level, cached.ores(), cached.fluids());
                return;
            }
            dev.iyanz.sourbycraft.util.VirtualExecutor.run(() -> {
                try {
                    final CacheEntry exposed = getOrComputeExposed(level, chunk, extraHidden, fluidObscures);
                    if (exposed.ores().length == 0 && exposed.fluids().length == 0) return;
                    if (player.hasDisconnected()) return;
                    hideExposedFor(player, level, exposed.ores(), exposed.fluids());
                } catch (Throwable t) {
                    if (CHUNK_SENT_FAILED_LOGGED.compareAndSet(false, true)) {
                        dev.iyanz.sourbycraft.util.SourbyLogger.warn("[SourbyEngine] async chunk scan failed — "
                            + "chunk left unmodified. Further occurrences suppressed. Cause: " + t);
                    }
                }
            });
        } catch (Throwable t) {
            if (CHUNK_SENT_FAILED_LOGGED.compareAndSet(false, true)) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn("[SourX] onChunkSent failed — leaving chunk "
                    + "unmodified (ores not hidden this send); anti-xray overlay disabled for this failure "
                    + "mode. Further occurrences are suppressed. Cause: " + t);
            }
        }
    }

    private static java.util.Set<Block> extraHiddenFor(final ServerLevel level, final SourbyCraftWorldConfig wc) {
        if (!wc.allBlocks) return java.util.Set.of();
        return EXTRA_HIDDEN.computeIfAbsent(level.getWorld().getName(),
            n -> java.util.Set.copyOf(level.paperConfig().anticheat.antiXray.hiddenBlocks));
    }

    /**
     * Track the given exposed positions as HIDDEN for one player and spoof them to stone.
     *
     * <p>RayTraceAntiXray model: on chunk send (and on block-change re-exposure) every hideable
     * position is (re)marked {@code hidden=true} and a stone update is sent, matching the state the
     * client just received — ores are already stone from the packet (the update is a harmless
     * belt-and-suspenders), chests/liquids (which Paper's packet engine does not touch) are actually
     * hidden by it. The per-player reveal cycle then reveals only the positions with real line of
     * sight. Re-marking a currently-revealed position as hidden is correct: the chunk packet carried
     * the real state again, so the client would otherwise see it — the next cycle re-reveals it if
     * still visible.
     *
     * <p>Fluids get a smaller budget slice so an ocean-sized fluid set can't starve the ore budget.
     */
    private static void hideExposedFor(final ServerPlayer player, final ServerLevel level,
                                       final long[] ores, final long[] fluids) {
        final RevealState state = stateFor(player.getUUID());
        final int maxPending = SourbyCraftConfig.raytraceMaxPendingPerPlayer;
        final int maxFluidPending = Math.max(16, maxPending / 4);
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final BlockState stoneState = fakeState(level, 0);
        final BlockState deepState = fakeState(level, -1);
        // Batch stone updates into ONE multi-block-change packet per 16³ section (a join chunk-burst
        // collapses hundreds of packets into a handful; the client applies a section atomically).
        final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap<BlockState>> bySection =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        synchronized (state.blocks) {
            for (final long key : ores) {
                if (state.blocks.size() >= maxPending && !state.blocks.containsKey(key)) break; // budget full: fail-open
                state.blocks.put(key, true);
                pos.set(key);
                accumulateHide(bySection, pos, pos.getY() < 0 ? deepState : stoneState);
            }
            int fluidBudget = maxFluidPending;
            for (final long key : fluids) {
                if (fluidBudget-- <= 0) break;
                if (state.blocks.size() >= maxPending && !state.blocks.containsKey(key)) break;
                state.blocks.put(key, true);
                pos.set(key);
                accumulateHide(bySection, pos, pos.getY() < 0 ? deepState : stoneState);
            }
        }
        for (final var e : bySection.long2ObjectEntrySet()) {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket(
                net.minecraft.core.SectionPos.of(e.getLongKey()), e.getValue()));
        }
    }

    /** Add one (position -> fake state) hide to its 16³ section bucket (keyed by SectionPos.asLong). */
    private static void accumulateHide(
            final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap<BlockState>> bySection,
            final BlockPos.MutableBlockPos pos, final BlockState fake) {
        final long sectionKey = net.minecraft.core.SectionPos.of(pos).asLong();
        bySection.computeIfAbsent(sectionKey, k -> new it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap<>())
            .put(net.minecraft.core.SectionPos.sectionRelativePos(pos), fake);
    }

    /**
     * Player-independent exposed scan, cached per chunk with a TTL. The expensive scan runs OUTSIDE
     * the per-level monitor so concurrent region sends on the same world never serialize on a scan;
     * a benign double-scan writes the same result twice.
     */
    /** Cache-only lookup (no scan): fresh entry or {@code null} on miss/stale. Region-thread cheap. */
    private static CacheEntry getCachedExposed(final ServerLevel level, final LevelChunk chunk) {
        final Long2ObjectOpenHashMap<CacheEntry> perLevel = SCAN_CACHE.get(level);
        if (perLevel == null) return null;
        final long now = level.getGameTime();
        synchronized (perLevel) {
            final CacheEntry cached = perLevel.get(chunk.getPos().pack());
            return cached != null && (now - cached.tick()) < SourbyCraftConfig.raytraceCacheTtlTicks ? cached : null;
        }
    }

    private static CacheEntry getOrComputeExposed(final ServerLevel level, final LevelChunk chunk,
                                                  final java.util.Set<Block> extraHidden, final boolean fluidObscures) {
        final long chunkKey = chunk.getPos().pack();
        final Long2ObjectOpenHashMap<CacheEntry> perLevel =
            SCAN_CACHE.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>());
        final long now = level.getGameTime();
        synchronized (perLevel) {
            final CacheEntry cached = perLevel.get(chunkKey);
            if (cached != null && (now - cached.tick()) < SourbyCraftConfig.raytraceCacheTtlTicks) {
                return cached;
            }
            if (perLevel.size() > MAX_CACHED_CHUNKS_PER_LEVEL) {
                // Evict HALF, not all: a full clear() converts the following minutes of chunk sends
                // back into full scans in one storm (50 players at vd 10 legitimately exceed the cap).
                // Hash-order eviction is fine — entries are TTL-bounded anyway.
                final var it = perLevel.values().iterator();
                for (int i = 0, target = perLevel.size() / 2; i < target && it.hasNext(); i++) {
                    it.next();
                    it.remove();
                }
            }
        }
        final CacheEntry entry = scanExposed(level, chunk, extraHidden, fluidObscures, now);
        synchronized (perLevel) {
            perLevel.put(chunkKey, entry);
        }
        return entry;
    }

    /** Full one-shot scan: every cave-exposed hidden ore + hidden fluid position in the chunk. */
    private static CacheEntry scanExposed(final ServerLevel level, final LevelChunk chunk,
                                          final java.util.Set<Block> extraHidden, final boolean fluidObscures,
                                          final long now) {
        final int chunkX = chunk.getPos().x() << 4;
        final int chunkZ = chunk.getPos().z() << 4;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final LevelChunkSection[] sections = chunk.getSections();
        final LongArrayList ores = new LongArrayList();
        final LongArrayList fluids = new LongArrayList();
        final java.util.Set<Block> oreSet = oreCandidates();
        final boolean hideLiquids = SourbyCraftConfig.hideLiquids;
        final boolean hideBE = SourbyCraftConfig.hideBlockEntities;
        final java.util.Set<Block> beSet = beCandidates();
        // Match Paper's own engine coverage: above max-block-height Paper hides nothing, and
        // surface ores are instantly LOS-confirmable — scanning higher sections buys no defense.
        final int maxY = level.paperConfig().anticheat.antiXray.maxBlockHeight;
        for (int idx = 0; idx < sections.length; idx++) {
            final LevelChunkSection section = sections[idx];
            if (section == null || section.hasOnlyAir()) continue;
            final int yBase = chunk.getSectionYFromSectionIndex(idx) << 4;
            if (yBase > maxY) continue;
            if (!section.getStates().maybeHas(state -> isCandidate(state, oreSet, extraHidden, beSet, hideBE, hideLiquids))) continue;
            for (int y = 0; y < 16; y++) {
                if (yBase + y > maxY) break;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final BlockState state = section.getBlockState(x, y, z);
                        final Block block = state.getBlock();
                        final int wx = chunkX + x, wy = yBase + y, wz = chunkZ + z;
                        // Block-entities (chests, spawners, …) leak through walls via the client BE list,
                        // so hide ALL of them regardless of occlusion — no isExposed gate. Paper never
                        // hid these, so there is no occluded-set overlap to defer to.
                        if (hideBE && beSet.contains(block)) {
                            ores.add(BlockPos.asLong(wx, wy, wz));
                            continue;
                        }
                        final boolean fluid = !state.getFluidState().isEmpty()
                            && !oreSet.contains(block) && !extraHidden.contains(block);
                        if (fluid) {
                            if (!hideLiquids) continue;
                            // C3: only CAVE fluids are hidden. A fluid at/above the column's solid
                            // roof (OCEAN_FLOOR heightmap ignores fluids) is sky-driven — oceans,
                            // rivers, surface lakes — and turning those to stone both looks broken
                            // and floods the pending budget until real cave ores leak (fail-open).
                            if (yBase + y >= chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z)) continue;
                        } else if (!oreSet.contains(block)
                            && (extraHidden.isEmpty() || !extraHidden.contains(block))) {
                            continue;
                        }
                        if (!isExposed(level, chunk, wx, wy, wz, fluidObscures, pos)) continue;
                        (fluid ? fluids : ores).add(BlockPos.asLong(wx, wy, wz));
                    }
                }
            }
        }
        return new CacheEntry(ores.isEmpty() ? EMPTY : ores.toLongArray(),
            fluids.isEmpty() ? EMPTY : fluids.toLongArray(), now);
    }

    // --- cache invalidation + post-change re-hide (Folia: region threads) ---

    private static void invalidateChunk(final ServerLevel level, final int chunkX, final int chunkZ) {
        final Long2ObjectOpenHashMap<CacheEntry> perLevel = SCAN_CACHE.get(level);
        if (perLevel == null) return;
        synchronized (perLevel) {
            perLevel.remove(net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ));
        }
    }

    /** A block change can alter exposure of ores up to 1 block away, so also drop bordering chunks. */
    private static void invalidateAround(final ServerLevel level, final int bx, final int bz) {
        final int cx = bx >> 4, cz = bz >> 4;
        invalidateChunk(level, cx, cz);
        if ((bx & 15) == 0)  invalidateChunk(level, cx - 1, cz);
        if ((bx & 15) == 15) invalidateChunk(level, cx + 1, cz);
        if ((bz & 15) == 0)  invalidateChunk(level, cx, cz - 1);
        if ((bz & 15) == 15) invalidateChunk(level, cx, cz + 1);
    }

    /**
     * Logged-once guard for the block-change NMS hook — same fail-open contract as
     * {@link #onChunkSent}: a bug here must never break {@code Level.setBlock}.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean NEARBY_REVEAL_FAILED_LOGGED =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * NMS hook (ChunkPacketBlockControllerAntiXray.updateNearbyBlocks — every block change Paper's
     * engine reacts to, INCLUDING piston moves, fluid flow, falling blocks and raw dig-start
     * packets, which fire no Bukkit event at all). Runs on the region thread of the change,
     * AFTER the change is applied — so unlike the Bukkit events (which fire pre-change), the
     * 6-neighbour exposure check below sees the post-change world. Paper's own updateNearbyBlocks
     * re-broadcasts the REAL state of every engine-hidden block within update-radius, defeating
     * our overlay; this re-hides what it just revealed for players without confirmed LOS.
     *
     * <p>Anti-bypass: without this hook a cheat client can strip the overlay by spamming dig-start
     * packets along a tunnel wall (verified vector).
     */
    public static void onNearbyReveal(final ServerLevel level, final BlockPos changed) {
        try {
            if (!RayTraceWorker.ENABLED.get() || level == null || changed == null) return;
            invalidateAround(level, changed.getX(), changed.getZ());
            reHideNeighbors(level, changed.getX(), changed.getY(), changed.getZ());
        } catch (Throwable t) {
            if (NEARBY_REVEAL_FAILED_LOGGED.compareAndSet(false, true)) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn("[SourX] onNearbyReveal failed — skipping "
                    + "re-hide for this change; further occurrences suppressed. Cause: " + t);
            }
        }
    }

    /**
     * Targeted post-change re-hide: exposure of a position only changes when a block ADJACENT to it
     * changes, so checking the changed block's 6 neighbours replaces the previous full-chunk re-scan
     * (~98k block reads -> <=6) and naturally covers chunk borders (the old whole-chunk path missed
     * ores exposed in the NEIGHBOUR chunk entirely).
     */
    private static void reHideNeighbors(final ServerLevel level, final int bx, final int by, final int bz) {
        final SourbyCraftWorldConfig wc = SourbyCraftWorldConfig.get(level);
        final boolean fluidObscures = SourbyCraftConfig.fluidObscures && wc.fluidObscures;
        final java.util.Set<Block> extraHidden = extraHiddenFor(level, wc);
        final java.util.Set<Block> oreSet = oreCandidates();
        final boolean hideLiquids = SourbyCraftConfig.hideLiquids;
        final boolean hideBE = SourbyCraftConfig.hideBlockEntities;
        final java.util.Set<Block> beSet = beCandidates();
        final int maxY = level.paperConfig().anticheat.antiXray.maxBlockHeight;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        LongArrayList newlyExposed = null;
        // The CHANGED block itself: a freshly placed/updated block-entity (chest, spawner, …) must be
        // hidden regardless of occlusion — it leaks through walls via the client BE list. Ores/fluids
        // are handled by the neighbour-exposure sweep below (their own change re-scans the chunk).
        if (hideBE && by <= maxY && by >= level.getMinY() && by <= level.getMaxY()) {
            final LevelChunk cc = level.getChunkIfLoaded(bx >> 4, bz >> 4);
            if (cc != null && beSet.contains(cc.getBlockState(pos.set(bx, by, bz)).getBlock())) {
                newlyExposed = new LongArrayList(6);
                newlyExposed.add(BlockPos.asLong(bx, by, bz));
            }
        }
        for (final net.minecraft.core.Direction dir : DIRECTIONS) {
            final int nx = bx + dir.getStepX(), ny = by + dir.getStepY(), nz = bz + dir.getStepZ();
            if (ny < level.getMinY() || ny > level.getMaxY() || ny > maxY) continue;
            final LevelChunk nc = level.getChunkIfLoaded(nx >> 4, nz >> 4);
            if (nc == null) continue;
            final BlockState state = nc.getBlockState(pos.set(nx, ny, nz));
            final Block block = state.getBlock();
            // A block-entity neighbour is already hidden occlusion-independently from chunk send, so a
            // neighbour change never newly-exposes it; only ores/fluids gain exposure here.
            if (hideBE && beSet.contains(block)) continue;
            final boolean fluid = !state.getFluidState().isEmpty()
                && !oreSet.contains(block) && !extraHidden.contains(block);
            if (fluid) {
                if (!hideLiquids) continue;
                if (ny >= nc.getHeight(Heightmap.Types.OCEAN_FLOOR, nx & 15, nz & 15)) continue; // sky fluid
            } else if (!isCandidate(state, oreSet, extraHidden, beSet, hideBE, false)) {
                continue;
            }
            if (!isExposed(level, nc, nx, ny, nz, fluidObscures, pos)) continue;
            if (newlyExposed == null) newlyExposed = new LongArrayList(6);
            newlyExposed.add(BlockPos.asLong(nx, ny, nz));
        }
        if (newlyExposed == null) return;
        final long[] keys = newlyExposed.toLongArray();
        final int cx = bx >> 4, cz = bz >> 4;
        final LevelChunk chunk = level.getChunkIfLoaded(cx, cz);
        if (chunk == null) return;
        for (final ServerPlayer player : net.minecraft.server.MinecraftServer.getServer().getPlayerList().getPlayers()) {
            if (player.level() != level) continue;
            if (!level.chunkPacketBlockController.shouldModify(player, chunk)) continue; // respect paper.antixray.bypass
            // Only players who actually hold this chunk client-side can leak it.
            final int pcx = player.chunkPosition().x(), pcz = player.chunkPosition().z();
            final int vd = player.getBukkitEntity().getViewDistance();
            if (Math.abs(pcx - cx) > vd || Math.abs(pcz - cz) > vd) continue;
            hideExposedFor(player, level, keys, EMPTY);
        }
    }

    /**
     * Lazily-built union of all vanilla ore tags + fixed ore blocks: one identity-hash contains()
     * per scanned block instead of up to 10 sequential tag lookups (the common case — stone —
     * previously missed all ten). Tags are bound long before the first chunk send; benign
     * double-build race on the volatile publish.
     */
    private static volatile it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<Block> ORE_CANDIDATES;

    private static it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<Block> oreCandidates() {
        var s = ORE_CANDIDATES;
        if (s == null) {
            final var set = new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<Block>(64);
            for (final Block b : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
                final BlockState st = b.defaultBlockState();
                // MC 26.2: only GOLD/IRON/COPPER_ORES remain BlockTags fields; the rest moved to BlockItemTags.<x>.block()
                if (st.is(net.minecraft.tags.BlockItemTags.COAL_ORES.block()) || st.is(BlockTags.IRON_ORES) || st.is(BlockTags.COPPER_ORES)
                    || st.is(BlockTags.GOLD_ORES) || st.is(net.minecraft.tags.BlockItemTags.REDSTONE_ORES.block()) || st.is(net.minecraft.tags.BlockItemTags.EMERALD_ORES.block())
                    || st.is(net.minecraft.tags.BlockItemTags.LAPIS_ORES.block()) || st.is(net.minecraft.tags.BlockItemTags.DIAMOND_ORES.block())
                    || st.is(Blocks.NETHER_QUARTZ_ORE) || st.is(Blocks.ANCIENT_DEBRIS)) {
                    set.add(b);
                }
            }
            ORE_CANDIDATES = s = set;
        }
        return s;
    }

    private static volatile it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<Block> BE_CANDIDATES;

    /**
     * Loot / dungeon-indicator BLOCK-ENTITIES that xray + ChestESP read straight from the client's
     * chunk block-entity list — so they leak through walls even when fully enclosed, which is exactly
     * why they are hidden regardless of cave-exposure (unlike ores, where Paper's engine already hides
     * the occluded set). Hidden via SourbyEngine's stone block-update (removes the client-side
     * block-entity), revealed on line of sight. Never added to Paper's hiddenBlocks — palette
     * obfuscation leaves the block-entity in the packet and glitches the chest.
     */
    private static it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<Block> beCandidates() {
        var s = BE_CANDIDATES;
        if (s == null) {
            final var set = new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<Block>(64);
            java.util.Collections.addAll(set,
                Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST, Blocks.BARREL,
                Blocks.HOPPER, Blocks.DISPENSER, Blocks.DROPPER,
                Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
                Blocks.BREWING_STAND, Blocks.BEACON,
                Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.VAULT,
                Blocks.DECORATED_POT);
            for (final Block b : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
                if (b instanceof net.minecraft.world.level.block.ShulkerBoxBlock) set.add(b);
            }
            BE_CANDIDATES = s = set;
        }
        return s;
    }

    /** Candidate = ore, block-entity (when enabled), operator-extra hidden block, or (when enabled) any fluid carrier. */
    private static boolean isCandidate(final BlockState state,
                                       final java.util.Set<Block> oreSet,
                                       final java.util.Set<Block> extraHidden,
                                       final java.util.Set<Block> beSet,
                                       final boolean hideBE,
                                       final boolean includeFluids) {
        final Block block = state.getBlock();
        if (oreSet.contains(block)) return true;
        if (hideBE && beSet.contains(block)) return true;
        if (includeFluids && !state.getFluidState().isEmpty()) return true;
        return !extraHidden.isEmpty() && extraHidden.contains(block);
    }

    /** Exposed = any of the 6 neighbours is non-occluding (and, with fluid-obscures, not a fluid). */
    private static boolean isExposed(final ServerLevel level, final LevelChunk chunk,
                                     final int x, final int y, final int z,
                                     final boolean fluidObscures, final BlockPos.MutableBlockPos pos) {
        for (net.minecraft.core.Direction dir : DIRECTIONS) {
            final int nx = x + dir.getStepX(), ny = y + dir.getStepY(), nz = z + dir.getStepZ();
            if (ny < level.getMinY() || ny > level.getMaxY()) continue;
            pos.set(nx, ny, nz);
            final BlockState neighbor;
            if ((nx >> 4) == chunk.getPos().x() && (nz >> 4) == chunk.getPos().z()) {
                neighbor = chunk.getBlockState(pos);
            } else {
                // Depth-1 read of a border chunk possibly owned by an adjacent region: racy palette
                // read, worst case one wrong exposure verdict cached until TTL — accepted (an
                // unloaded neighbour counts as obscuring, the fail-safe direction).
                final LevelChunk nc = level.getChunkIfLoaded(nx >> 4, nz >> 4);
                if (nc == null) continue;
                neighbor = nc.getBlockState(pos);
            }
            if (neighbor.canOcclude()) continue;
            if (fluidObscures && !neighbor.getFluidState().isEmpty()) continue;
            return true;
        }
        return false;
    }

    private static final net.minecraft.core.Direction[] DIRECTIONS = net.minecraft.core.Direction.values();

    private static BlockState fakeState(final ServerLevel level, final int y) {
        return switch (level.getWorld().getEnvironment()) {
            case NETHER -> Blocks.NETHERRACK.defaultBlockState();
            case THE_END -> Blocks.END_STONE.defaultBlockState();
            default -> y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
        };
    }

    /**
     * Global-region cycle: for every player that has tracked hideable blocks, hop to the player's
     * OWNING region, snapshot the eye position + look direction there (Folia-sound entity read), and
     * submit ONE async ray-trace task. The global thread only reads the player list + the concurrent
     * STATE map — never world/block state.
     */
    private static void tickCycle() {
        if (!RayTraceWorker.ENABLED.get() || STATE.isEmpty()) return;
        final boolean frustum = SourbyCraftConfig.frustumCulling;
        for (final ServerPlayer player : net.minecraft.server.MinecraftServer.getServer().getPlayerList().getPlayers()) {
            final RevealState state = STATE.get(player.getUUID());
            if (state == null) continue;
            synchronized (state.blocks) { if (state.blocks.isEmpty()) continue; }
            player.getBukkitEntity().getScheduler().run(
                org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE,
                task -> {
                    try {
                        final ServerLevel level = player.level();
                        if (level == null) return;
                        final Vec3 eye = player.getEyePosition();           // Folia-sound: owning region
                        final Vec3 look = player.getViewVector(1.0f);       // look direction for frustum cull
                        final long epoch = state.epoch;
                        final boolean fluidObscures = SourbyCraftConfig.fluidObscures
                            && SourbyCraftWorldConfig.get(level).fluidObscures;
                        // Off the region thread: the whole ray sweep + packet sends. Reads use
                        // getChunkIfLoaded only (thread-safe), matching RayTraceAntiXray's async model.
                        dev.iyanz.sourbycraft.util.VirtualExecutor.run(() ->
                            rayTraceAndApply(player, state, level, epoch, eye, look, frustum, fluidObscures));
                    } catch (Throwable t) {
                        if (CHUNK_SENT_FAILED_LOGGED.compareAndSet(false, true)) {
                            dev.iyanz.sourbycraft.util.SourbyLogger.warn("[SourbyEngine] reveal cycle failed — "
                                + "suppressing further logs. Cause: " + t);
                        }
                    }
                },
                null);
        }
    }

    /**
     * SourbyEngine per-player ray-trace + diff — a faithful adaptation of RayTraceAntiXray's
     * {@code RayTraceCallable}. Runs on a virtual thread from ONE consistent eye snapshot:
     * <ul>
     *   <li>Every tracked block within {@code rehideDistance} is line-of-sight tested with
     *       {@link BlockOcclusionCulling} (biased "visible when in doubt", so borderline blocks stay
     *       revealed rather than flicker). Between {@code rehideDistance} and {@code rayTraceDistance}
     *       a block is treated as not-visible → re-hidden. Beyond a prune radius it is dropped.</li>
     *   <li>The result is diffed against the block's current client state: newly visible + hidden →
     *       reveal (real block update); no longer visible + shown → hide (stone update). Unchanged
     *       blocks send nothing — no per-tick churn, so no flicker.</li>
     * </ul>
     * Because there is a single snapshot and NO per-cycle ray budget, a directly-visible chest/ore is
     * always revealed in one cycle (the old budget starved it into staying stone) and there is no
     * stale-eye vs fresh-eye disagreement (the old two-phase split flickered).
     */
    private static void rayTraceAndApply(final ServerPlayer player, final RevealState state,
                                         final ServerLevel level, final long epoch,
                                         final Vec3 eye, final Vec3 look,
                                         final boolean frustum, final boolean fluidObscures) {
        final long[] keys;
        final boolean[] hidden;
        synchronized (state.blocks) {
            final int n = state.blocks.size();
            if (n == 0) return;
            keys = new long[n];
            hidden = new boolean[n];
            int i = 0;
            for (final var e : state.blocks.long2BooleanEntrySet()) { keys[i] = e.getLongKey(); hidden[i] = e.getBooleanValue(); i++; }
        }
        final double rayTraceDistance = SourbyCraftConfig.raytraceDistance;
        final double rayTraceDistanceSq = rayTraceDistance * rayTraceDistance;
        final double rehideDistance = Math.min(SourbyCraftConfig.rehideDistance, rayTraceDistance);
        final double rehideDistanceSq = rehideDistance * rehideDistance;
        final double pruneSq = Math.max(rayTraceDistanceSq * 2.25, 96 * 96); // 1.5x rayTraceDistance
        final double eyeX = eye.x, eyeY = eye.y, eyeZ = eye.z;
        final double lookX = look.x, lookY = look.y, lookZ = look.z;

        // Folia-safe occlusion oracle: loaded-chunk reads only, last-chunk cached; unloaded = occluding.
        final long[] chunkCache = { Long.MIN_VALUE };
        final net.minecraft.world.level.chunk.LevelChunk[] chunkRef = { null };
        final BlockPos.MutableBlockPos op = new BlockPos.MutableBlockPos();
        final int minY = level.getMinY(), maxY = level.getMaxY();
        final BlockOcclusionCulling culling = new BlockOcclusionCulling((bx, by, bz) -> {
            if (by < minY || by > maxY) return false;                 // void / above build = open
            final int cx = bx >> 4, cz = bz >> 4;
            final long ck = net.minecraft.world.level.ChunkPos.pack(cx, cz);
            if (ck != chunkCache[0]) {
                chunkCache[0] = ck;
                chunkRef[0] = level.getChunkIfLoaded(cx, cz);
            }
            final net.minecraft.world.level.chunk.LevelChunk c = chunkRef[0];
            if (c == null) return true;                               // unloaded = occluding (fail-safe)
            final BlockState st = c.getBlockState(op.set(bx, by, bz));
            if (st.isViewBlocking(level, op)) return true;
            return fluidObscures && !st.getFluidState().isEmpty();
        }, frustum);

        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.shorts.Short2ObjectOpenHashMap<BlockState>> hideBySection =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
        final BlockState stoneState = fakeState(level, 0);
        final BlockState deepState = fakeState(level, -1);
        // Collected mutations to apply back to the map after the sweep (keeps the lock off the rays).
        final LongArrayList reveals = new LongArrayList();   // key -> now visible (map=false)
        final LongArrayList rehides = new LongArrayList();   // key -> now hidden  (map=true)
        final LongArrayList prunes = new LongArrayList();

        for (int i = 0; i < keys.length; i++) {
            final long key = keys[i];
            final int bx = BlockPos.getX(key), by = BlockPos.getY(key), bz = BlockPos.getZ(key);
            final double centerX = bx + 0.5, centerY = by + 0.5, centerZ = bz + 0.5;
            final double diffX = eyeX - centerX, diffY = eyeY - centerY, diffZ = eyeZ - centerZ;
            final double distSq = diffX * diffX + diffY * diffY + diffZ * diffZ;
            if (distSq > pruneSq) { prunes.add(key); continue; }      // client has long since unloaded it
            if (distSq > rayTraceDistanceSq) continue;                // out of range: leave as-is this cycle
            boolean visible = false;
            if (distSq < rehideDistanceSq) {
                visible = culling.isVisible(bx, by, bz, centerX, centerY, centerZ, diffX, diffY, diffZ, distSq, lookX, lookY, lookZ);
            }
            if (visible) {
                if (hidden[i]) {
                    pos.set(bx, by, bz);
                    final net.minecraft.world.level.chunk.LevelChunk c = level.getChunkIfLoaded(bx >> 4, bz >> 4);
                    if (c != null) {
                        player.connection.send(new ClientboundBlockUpdatePacket(pos.immutable(), c.getBlockState(pos)));
                        reveals.add(key);
                    }
                }
            } else if (!hidden[i]) {
                pos.set(bx, by, bz);
                accumulateHide(hideBySection, pos, by < 0 ? deepState : stoneState);
                rehides.add(key);
            }
        }

        // Teleport / world change / quit since the snapshot → discard (state may be cleared/replaced).
        if (state.epoch != epoch) return;
        for (final var e : hideBySection.long2ObjectEntrySet()) {
            player.connection.send(new net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket(
                net.minecraft.core.SectionPos.of(e.getLongKey()), e.getValue()));
        }
        synchronized (state.blocks) {
            if (state.epoch != epoch) return;
            for (int i = 0; i < reveals.size(); i++) { final long k = reveals.getLong(i); if (state.blocks.containsKey(k)) state.blocks.put(k, false); }
            for (int i = 0; i < rehides.size(); i++) { final long k = rehides.getLong(i); if (state.blocks.containsKey(k)) state.blocks.put(k, true); }
            for (int i = 0; i < prunes.size(); i++) state.blocks.remove(prunes.getLong(i));
        }
    }

    // --- events: cache invalidation only. Bukkit block events fire BEFORE the change is applied,
    // so a synchronous exposure re-scan here would see the PRE-change world and miss the freshly
    // exposed ore; the post-change re-hide is driven by the NMS onNearbyReveal hook instead, which
    // also covers piston/fluid/falling-block/dig-start changes that fire no Bukkit event at all. ---

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent e) { invalidateBlock(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent e) { invalidateBlock(e.getBlock()); }

    private static void invalidateBlock(final org.bukkit.block.Block b) {
        if (!RayTraceWorker.ENABLED.get()) return;
        final ServerLevel level = ((org.bukkit.craftbukkit.CraftWorld) b.getWorld()).getHandle();
        invalidateAround(level, b.getX(), b.getZ());
    }

    /** Explosions: dedupe the affected chunk set first — one invalidation per chunk, not per block. */
    private static void invalidateExplosion(final org.bukkit.World world, final java.util.List<org.bukkit.block.Block> blocks) {
        if (!RayTraceWorker.ENABLED.get() || blocks.isEmpty()) return;
        final ServerLevel level = ((org.bukkit.craftbukkit.CraftWorld) world).getHandle();
        final LongOpenHashSet chunks = new LongOpenHashSet();
        for (final org.bukkit.block.Block b : blocks) {
            final int bx = b.getX(), bz = b.getZ();
            chunks.add(net.minecraft.world.level.ChunkPos.pack(bx >> 4, bz >> 4));
            if ((bx & 15) == 0)  chunks.add(net.minecraft.world.level.ChunkPos.pack((bx >> 4) - 1, bz >> 4));
            if ((bx & 15) == 15) chunks.add(net.minecraft.world.level.ChunkPos.pack((bx >> 4) + 1, bz >> 4));
            if ((bz & 15) == 0)  chunks.add(net.minecraft.world.level.ChunkPos.pack(bx >> 4, (bz >> 4) - 1));
            if ((bz & 15) == 15) chunks.add(net.minecraft.world.level.ChunkPos.pack(bx >> 4, (bz >> 4) + 1));
        }
        final Long2ObjectOpenHashMap<CacheEntry> perLevel = SCAN_CACHE.get(level);
        if (perLevel == null) return;
        synchronized (perLevel) {
            final LongIterator it = chunks.iterator();
            while (it.hasNext()) perLevel.remove(it.nextLong());
        }
        // Post-change re-hide of each destroyed position's neighbours is driven per-setBlock by the
        // NMS onNearbyReveal hook as the explosion applies — no O(blocks) full-chunk rescans here.
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent e) { invalidateExplosion(e.getBlock().getWorld(), e.blockList()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent e) { invalidateExplosion(e.getEntity().getWorld(), e.blockList()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(org.bukkit.event.world.ChunkUnloadEvent e) {
        final ServerLevel level = ((org.bukkit.craftbukkit.CraftWorld) e.getWorld()).getHandle();
        invalidateChunk(level, e.getChunk().getX(), e.getChunk().getZ());
    }

    /**
     * Drop this world's entire {@link #SCAN_CACHE} entry on world unload. {@link org.bukkit.event.world.ChunkUnloadEvent}
     * only removes per-chunk entries; a full world unload (e.g. an SWM island reset — world names are
     * reused, but each reload is a NEW {@link ServerLevel} instance) would otherwise leave the old
     * level's key + its per-level chunk map strongly referenced by this static map forever, since the
     * stale key is never looked up again. That is a per-unload memory leak that grows unbounded on a
     * server doing frequent world load/unload cycles. Evict the whole level entry here so an unloaded
     * world's scan cache is freed with the world.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(org.bukkit.event.world.WorldUnloadEvent e) {
        final ServerLevel level = ((org.bukkit.craftbukkit.CraftWorld) e.getWorld()).getHandle();
        SCAN_CACHE.remove(level);
    }

    /**
     * Anti-bypass (chunk-border): {@link #isExposed} treats an UNLOADED neighbour chunk as obscuring, so
     * a border ore whose only opening faces a not-yet-loaded neighbour is scanned as buried and never
     * hidden. When that neighbour finally loads, the ore becomes exposed to a cave the player can already
     * see from the adjacent chunk they hold — an xray leak. The newly-loaded chunk's OWN scan is fresh,
     * but its four cardinal neighbours were scanned earlier against a then-missing chunk, so invalidate
     * their border-affected caches. The cheap re-scan + re-hide happens on those neighbours' next send /
     * next block-change; we invalidate only (no eager re-hide) to keep chunk-load cost near zero.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent e) {
        if (!RayTraceWorker.ENABLED.get()) return;
        final ServerLevel level = ((org.bukkit.craftbukkit.CraftWorld) e.getWorld()).getHandle();
        final int cx = e.getChunk().getX(), cz = e.getChunk().getZ();
        invalidateChunk(level, cx - 1, cz);
        invalidateChunk(level, cx + 1, cz);
        invalidateChunk(level, cx, cz - 1);
        invalidateChunk(level, cx, cz + 1);
    }

    private static void clearPlayer(final UUID id) {
        final RevealState state = STATE.remove(id);
        if (state != null) {
            // Bump the epoch so any in-flight async ray sweep discards its results instead of sending
            // reveals into a stale eye (teleport/world-change/quit).
            state.epoch++;
            synchronized (state.blocks) { state.blocks.clear(); }
        }
        EntityVisibilityCheck.clear(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) { clearPlayer(e.getPlayer().getUniqueId()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        // BlockPos long keys are world-agnostic — never leak across dimensions.
        clearPlayer(e.getPlayer().getUniqueId());
    }

    /** Respawn relocates the eye without a PlayerTeleportEvent — same clear as a teleport. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent e) {
        if (!RayTraceWorker.ENABLED.get()) return;
        clearPlayer(e.getPlayer().getUniqueId());
    }

    /**
     * Anti-bypass: a same-world teleport (/tp across the map, ender pearl, plugin warp) moves the eye
     * without a dimension change, so {@link #onWorldChange} never fires. {@link VisibilityCache} still
     * holds positions confirmed visible from the OLD eye — and {@link #onChunkSent} SKIPS re-hiding any
     * ore still in that cache, so an ore that was visible from the old spot but is occluded from the new
     * spot would stay revealed = xray leak. Clear the player's confirmed-visible cache + pending on any
     * non-trivial teleport so every ore must be re-confirmed from the new eye. Gated on distance so
     * micro-teleports (mount dismount, tiny plugin nudges) don't thrash the cache for no security gain.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent e) {
        if (!RayTraceWorker.ENABLED.get()) return;
        final org.bukkit.Location from = e.getFrom(), to = e.getTo();
        if (to == null) return;
        // Cross-world teleports are handled by onWorldChange. A null from-world cannot be distance-
        // checked — treat it as non-trivial (clear) instead of throwing inside distanceSquared.
        if (from.getWorld() == null) { clearPlayer(e.getPlayer().getUniqueId()); return; }
        if (!from.getWorld().equals(to.getWorld())) return;
        if (from.distanceSquared(to) < NEAR_DISTANCE_SQUARED) return; // trivial move -> occlusion unchanged
        clearPlayer(e.getPlayer().getUniqueId());
    }
}
