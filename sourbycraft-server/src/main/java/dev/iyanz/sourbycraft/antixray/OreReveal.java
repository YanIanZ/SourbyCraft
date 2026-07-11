package dev.iyanz.sourbycraft.antixray;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.SourbyCraftWorldConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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
 * Exposed-ore hide/reveal layer above Paper's anti-xray engine.
 *
 * <p>Paper engine-mode 1 hides ores that are fully enclosed; ores exposed to a
 * cave surface still leak through walls. This layer hides those on chunk send
 * (per-player fake block updates) and reveals each one when the existing
 * {@link RayTraceWorker} confirms actual line of sight, or instantly within
 * {@value #NEAR_DISTANCE} blocks (mining UX).
 *
 * <p>All state main-thread except {@link VisibilityCache} (concurrent) which
 * is the async worker handoff. Disabled ({@code antixray.raytrace.enabled:
 * false}) → {@link #onChunkSent} is a single volatile read.</p>
 */
public final class OreReveal implements Listener {

    private static final double NEAR_DISTANCE = 8.0;
    private static final double NEAR_DISTANCE_SQUARED = NEAR_DISTANCE * NEAR_DISTANCE;

    /**
     * Per-player pending hidden-ore positions (BlockPos.asLong).
     *
     * <p><b>Folia:</b> chunk sends for one player can be driven from multiple region threads, and
     * {@link #tickCycle} runs on the global-region thread, so this outer map is concurrent AND each
     * per-player {@link LongOpenHashSet} value is guarded by {@code synchronized(pending)} at every
     * touch — a fastutil set is not safe under concurrent structural mutation.
     */
    private static final Map<UUID, LongOpenHashSet> PENDING = new ConcurrentHashMap<>();

    /**
     * Per-chunk exposed-ore scan cache. Which ores in a chunk are cave-exposed is
     * player-independent, so the ~4096-block/section scan runs ONCE per chunk and every
     * player/send reuses the result — instead of re-scanning for every chunk send (the
     * pre-cache behaviour that collapsed TPS at high player counts). Invalidated precisely on
     * block-change + chunk-unload; a TTL bounds staleness from non-event changes.
     *
     * <p><b>Folia:</b> {@link #onChunkSent} runs on the region thread owning that chunk and the
     * invalidation events fire on region threads too, so the outer map is a {@link ConcurrentHashMap}
     * (keyed by {@link ServerLevel} identity — server levels are singletons, identity == equals) and
     * each per-level {@link Long2ObjectOpenHashMap} value is guarded by {@code synchronized} on that
     * value at every read/write. Lock granularity is per-level, so distinct worlds never contend and
     * region ticks are not serialized by a global lock.
     */
    private static final Map<ServerLevel, Long2ObjectOpenHashMap<CacheEntry>> SCAN_CACHE = new ConcurrentHashMap<>();
    private record CacheEntry(long[] exposed, long tick) {}
    private static final long[] EMPTY = new long[0];
    /** Safety cap so a missed unload event can't grow a level's cache unbounded. */
    private static final int MAX_CACHED_CHUNKS_PER_LEVEL = 16384;

    private OreReveal() {}

    public static void register(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new OreReveal(), plugin);
        long interval = Math.max(1, SourbyCraftConfig.raytraceIntervalTicks);
        // Folia: no Bukkit global scheduler exists. Drive tickCycle from the global-region scheduler
        // (the same handle the perf-engine actuators use). tickCycle only reads the online-player list
        // + sends per-player packets, which is region-safe from the global-region thread.
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(
            org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE,
            task -> tickCycle(), interval, interval);
        plugin.getLogger().info("[antixray] ore reveal " + (RayTraceWorker.ENABLED.get() ? "ENABLED" : "disabled")
            + " (interval=" + interval + "t distance=" + SourbyCraftConfig.raytraceDistance
            + " checks/cycle=" + SourbyCraftConfig.raytraceMaxChecksPerCycle + ")");
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
                plugin.getLogger().warning("[antixray] raytrace enabled but no world has paper anti-xray enabled — "
                    + "ore reveal is a complementary layer and stays inert until anticheat.anti-xray.enabled: true "
                    + "in paper-world-defaults.yml (enable antixray.baritone-defense to seed it automatically; "
                    + "buried ores would leak anyway without the Paper engine).");
            }
        }
    }

    /** NMS hook (PlayerChunkSender.sendChunk, main thread, after the chunk packet went out). */
    public static void onChunkSent(final ServerPlayer player, final LevelChunk chunk) {
        if (!RayTraceWorker.ENABLED.get() || player == null || chunk == null) return;
        final ServerLevel level = (ServerLevel) chunk.getLevel();
        if (!level.chunkPacketBlockController.shouldModify(player, chunk)) return; // respect paper.antixray.bypass
        final SourbyCraftWorldConfig wc = SourbyCraftWorldConfig.get(level);
        final boolean fluidObscures = SourbyCraftConfig.fluidObscures && wc.fluidObscures;
        final java.util.Set<Block> extraHidden = wc.allBlocks
            ? java.util.Set.copyOf(level.paperConfig().anticheat.antiXray.hiddenBlocks) : java.util.Set.of();

        // Player-independent scan, computed once per chunk and cached. Per-send work is now
        // just iterating the (small) exposed-ore list + a per-player pending/visibility check.
        final long[] exposed = getOrComputeExposed(level, chunk, extraHidden, fluidObscures);
        if (exposed.length == 0) return;
        hideExposedFor(player, level, exposed);
    }

    /**
     * Hide the given exposed-ore positions for one player (fake block updates), skipping ores already
     * confirmed visible or already pending. Shared by {@link #onChunkSent} and the block-change re-hide
     * path so both use identical semantics and the same per-player pending budget.
     */
    private static void hideExposedFor(final ServerPlayer player, final ServerLevel level, final long[] exposed) {
        final UUID pid = player.getUUID();
        final LongOpenHashSet pending = PENDING.computeIfAbsent(pid, id -> new LongOpenHashSet());
        final int maxPending = SourbyCraftConfig.raytraceMaxPendingPerPlayer;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        // Folia: guard the per-player fastutil set — tickCycle (global-region thread) and other region
        // sends for this player mutate it concurrently. Sends are issued inside the monitor; the packet
        // queue is thread-safe and the loop is bounded by maxPending so the critical section stays short.
        synchronized (pending) {
            for (final long key : exposed) {
                if (pending.size() >= maxPending) break;   // budget full: remaining ores stay visible (fail-open)
                if (VisibilityCache.isVisible(pid, key)) continue; // already confirmed visible
                if (!pending.add(key)) continue;           // already hidden + pending for this player
                pos.set(key);
                player.connection.send(new ClientboundBlockUpdatePacket(pos.immutable(), fakeState(level, pos.getY())));
            }
        }
    }

    /**
     * Player-independent exposed-ore scan, cached per chunk with a TTL.
     *
     * <p><b>Folia:</b> the fastutil {@code perLevel} map is guarded by {@code synchronized(perLevel)}
     * for every read/write, but the (expensive, ~4096-block) {@link #scanExposed} runs OUTSIDE that
     * monitor so the per-level lock is never held during a scan — otherwise concurrent region sends
     * on the same world would serialize. A benign double-scan can happen if two region threads miss
     * the cache for the same chunk simultaneously; both write the same result (last writer wins).
     */
    private static long[] getOrComputeExposed(final ServerLevel level, final LevelChunk chunk,
                                              final java.util.Set<Block> extraHidden, final boolean fluidObscures) {
        final long chunkKey = chunk.getPos().pack();
        final Long2ObjectOpenHashMap<CacheEntry> perLevel =
            SCAN_CACHE.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>());
        final long now = level.getGameTime();
        synchronized (perLevel) {
            final CacheEntry cached = perLevel.get(chunkKey);
            if (cached != null && (now - cached.tick()) < SourbyCraftConfig.raytraceCacheTtlTicks) {
                return cached.exposed();
            }
            if (perLevel.size() > MAX_CACHED_CHUNKS_PER_LEVEL) perLevel.clear(); // bound memory; forces cheap re-scan
        }
        final long[] exposed = scanExposed(level, chunk, extraHidden, fluidObscures);
        synchronized (perLevel) {
            perLevel.put(chunkKey, new CacheEntry(exposed, now));
        }
        return exposed;
    }

    /** Full one-shot scan: every cave-exposed hidden-ore position in the chunk (BlockPos.asLong). */
    private static long[] scanExposed(final ServerLevel level, final LevelChunk chunk,
                                      final java.util.Set<Block> extraHidden, final boolean fluidObscures) {
        final int chunkX = chunk.getPos().x() << 4;
        final int chunkZ = chunk.getPos().z() << 4;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final LevelChunkSection[] sections = chunk.getSections();
        final it.unimi.dsi.fastutil.longs.LongArrayList out = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (int idx = 0; idx < sections.length; idx++) {
            final LevelChunkSection section = sections[idx];
            if (section == null || section.hasOnlyAir()) continue;
            if (!section.getStates().maybeHas(state -> isCandidate(state, extraHidden))) continue;
            final int yBase = chunk.getSectionYFromSectionIndex(idx) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final BlockState state = section.getBlockState(x, y, z);
                        if (!isCandidate(state, extraHidden)) continue;
                        final int wx = chunkX + x, wy = yBase + y, wz = chunkZ + z;
                        if (!isExposed(level, chunk, wx, wy, wz, fluidObscures, pos)) continue;
                        out.add(BlockPos.asLong(wx, wy, wz));
                    }
                }
            }
        }
        return out.isEmpty() ? EMPTY : out.toLongArray();
    }

    // --- cache invalidation (Folia: region threads; per-level fastutil map guarded by its own monitor) ---

    private static void invalidateChunk(final ServerLevel level, final int chunkX, final int chunkZ) {
        final Long2ObjectOpenHashMap<CacheEntry> perLevel = SCAN_CACHE.get(level);
        if (perLevel == null) return;
        synchronized (perLevel) {
            perLevel.remove(net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ));
        }
    }

    /** A block change can alter exposure of ores up to 1 block away, so also drop bordering chunks. */
    private static void invalidateAt(final org.bukkit.block.Block b) {
        if (!RayTraceWorker.ENABLED.get()) return;
        final ServerLevel level = ((org.bukkit.craftbukkit.CraftWorld) b.getWorld()).getHandle();
        final int bx = b.getX(), bz = b.getZ();
        final int cx = bx >> 4, cz = bz >> 4;
        invalidateChunk(level, cx, cz);
        if ((bx & 15) == 0)  invalidateChunk(level, cx - 1, cz);
        if ((bx & 15) == 15) invalidateChunk(level, cx + 1, cz);
        if ((bz & 15) == 0)  invalidateChunk(level, cx, cz - 1);
        if ((bz & 15) == 15) invalidateChunk(level, cx, cz + 1);
        // Anti-bypass: breaking a wall next to a buried ore FRESHLY EXPOSES it. Vanilla already sent the
        // real block-update for the broken block, and the newly-exposed ore is now visible to every client
        // that has the chunk — but nothing re-sends the chunk, so without this the ore would leak until the
        // player leaves and returns. Re-scan the affected chunk (cache was just invalidated above) and
        // re-hide any now-exposed ore for nearby tracking players that have not confirmed line-of-sight.
        reHideAround(level, cx, cz);
    }

    /**
     * Re-hide freshly-exposed ores in one chunk for the players tracking it. Runs on the region thread of
     * the block change (the event thread). Cost: one exposed-scan (cache-invalidated, so recomputed once)
     * plus, per nearby player, the same cheap pending/visibility loop {@link #onChunkSent} runs — no
     * raytrace here. Players who have genuine line-of-sight will re-reveal it on the next {@link #tickCycle}.
     *
     * <p><b>Folia:</b> iterate the SERVER player-list snapshot (an immutable list, same source
     * {@link #tickCycle} uses) rather than {@code level.players()} — the latter is a live list mutated by
     * other region threads and unsafe to iterate here. Each candidate is filtered to this world + a chunk
     * radius before the send; packet sends are thread-safe.
     */
    private static void reHideAround(final ServerLevel level, final int chunkX, final int chunkZ) {
        final net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkIfLoaded(chunkX, chunkZ);
        if (chunk == null) return;
        final SourbyCraftWorldConfig wc = SourbyCraftWorldConfig.get(level);
        final boolean fluidObscures = SourbyCraftConfig.fluidObscures && wc.fluidObscures;
        final java.util.Set<Block> extraHidden = wc.allBlocks
            ? java.util.Set.copyOf(level.paperConfig().anticheat.antiXray.hiddenBlocks) : java.util.Set.of();
        final long[] exposed = getOrComputeExposed(level, chunk, extraHidden, fluidObscures);
        if (exposed.length == 0) return;
        for (final ServerPlayer player : net.minecraft.server.MinecraftServer.getServer().getPlayerList().getPlayers()) {
            if (player.level() != level) continue; // different world
            if (!level.chunkPacketBlockController.shouldModify(player, chunk)) continue; // respect paper.antixray.bypass
            // Only players who actually have this chunk loaded on their client can leak it.
            final int pcx = player.chunkPosition().x(), pcz = player.chunkPosition().z();
            final int vd = player.getBukkitEntity().getViewDistance();
            if (Math.abs(pcx - chunkX) > vd || Math.abs(pcz - chunkZ) > vd) continue;
            hideExposedFor(player, level, exposed);
        }
    }

    private static boolean isCandidate(final BlockState state, final java.util.Set<Block> extraHidden) {
        // MC 26.2: only GOLD/IRON/COPPER_ORES remain BlockTags fields; the rest moved to BlockItemTags.<x>.block()
        if (state.is(net.minecraft.tags.BlockItemTags.COAL_ORES.block()) || state.is(BlockTags.IRON_ORES) || state.is(BlockTags.COPPER_ORES)
            || state.is(BlockTags.GOLD_ORES) || state.is(net.minecraft.tags.BlockItemTags.REDSTONE_ORES.block()) || state.is(net.minecraft.tags.BlockItemTags.EMERALD_ORES.block())
            || state.is(net.minecraft.tags.BlockItemTags.LAPIS_ORES.block()) || state.is(net.minecraft.tags.BlockItemTags.DIAMOND_ORES.block())
            || state.is(Blocks.NETHER_QUARTZ_ORE) || state.is(Blocks.ANCIENT_DEBRIS)) {
            return true;
        }
        // Liquids upgrade (operator ask): hide cave-exposed water/lava (source + flowing) like ores.
        // Guarded on antixray.hide-liquids (default ON). Uses the block's fluid state so both the
        // liquid BLOCK and any waterlogged/flowing state that carries a non-empty fluid is caught.
        if (SourbyCraftConfig.hideLiquids && !state.getFluidState().isEmpty()) {
            return true;
        }
        return !extraHidden.isEmpty() && extraHidden.contains(state.getBlock());
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
                final LevelChunk nc = level.getChunkIfLoaded(nx >> 4, nz >> 4);
                if (nc == null) continue; // unloaded neighbour counts as obscuring
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

    /** Main-thread cycle: reveal confirmed positions, submit raytraces for near pending ones. */
    private static void tickCycle() {
        if (!RayTraceWorker.ENABLED.get() || PENDING.isEmpty()) return;
        final double distance = SourbyCraftConfig.raytraceDistance;
        final double distanceSq = distance * distance;
        for (final ServerPlayer player : net.minecraft.server.MinecraftServer.getServer().getPlayerList().getPlayers()) {
            final LongOpenHashSet pending = PENDING.get(player.getUUID());
            if (pending == null) continue;
            final ServerLevel level = player.level();
            final Vec3 eye = player.getEyePosition();
            int budget = SourbyCraftConfig.raytraceMaxChecksPerCycle;
            final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            // Folia: iterate under the per-player monitor — onChunkSent (region threads) may add
            // to this set concurrently. iterator.remove() is only structurally safe with the writer
            // excluded. Reveal sends stay inside the monitor (packet queue is thread-safe).
            synchronized (pending) {
                if (pending.isEmpty()) continue;
                final LongIterator it = pending.iterator();
                while (it.hasNext()) {
                    final long key = it.nextLong();
                    pos.set(key);
                    final double dsq = eye.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                    final boolean confirmed = VisibilityCache.isVisible(player.getUUID(), key);
                    if (confirmed || dsq <= NEAR_DISTANCE_SQUARED) {
                        if (level.isLoaded(pos)) {
                            player.connection.send(new ClientboundBlockUpdatePacket(pos.immutable(), level.getBlockState(pos)));
                        }
                        it.remove();
                        continue;
                    }
                    if (dsq <= distanceSq && budget > 0) {
                        RayTraceWorker.submit(player, pos.immutable());
                        budget--;
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent e) { invalidateAt(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(org.bukkit.event.block.BlockPlaceEvent e) { invalidateAt(e.getBlock()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(org.bukkit.event.block.BlockExplodeEvent e) {
        if (!RayTraceWorker.ENABLED.get()) return;
        for (org.bukkit.block.Block b : e.blockList()) invalidateAt(b);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(org.bukkit.event.entity.EntityExplodeEvent e) {
        if (!RayTraceWorker.ENABLED.get()) return;
        for (org.bukkit.block.Block b : e.blockList()) invalidateAt(b);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(org.bukkit.event.world.ChunkUnloadEvent e) {
        final ServerLevel level = ((org.bukkit.craftbukkit.CraftWorld) e.getWorld()).getHandle();
        invalidateChunk(level, e.getChunk().getX(), e.getChunk().getZ());
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

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        final UUID id = e.getPlayer().getUniqueId();
        PENDING.remove(id);
        VisibilityCache.clear(id);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        // BlockPos long keys are world-agnostic — never leak across dimensions.
        final UUID id = e.getPlayer().getUniqueId();
        PENDING.remove(id);
        VisibilityCache.clear(id);
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
        // Cross-world teleports are handled by onWorldChange; only same-world moves matter here.
        if (from.getWorld() != null && !from.getWorld().equals(to.getWorld())) return;
        if (from.distanceSquared(to) < NEAR_DISTANCE_SQUARED) return; // trivial move -> occlusion unchanged
        final UUID id = e.getPlayer().getUniqueId();
        PENDING.remove(id);
        VisibilityCache.clear(id);
    }
}
