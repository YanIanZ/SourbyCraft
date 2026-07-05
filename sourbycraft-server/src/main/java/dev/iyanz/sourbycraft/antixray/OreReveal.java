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

    /** Per-player pending hidden-ore positions (BlockPos.asLong). Main-thread only. */
    private static final Map<UUID, LongOpenHashSet> PENDING = new ConcurrentHashMap<>();

    /**
     * Per-chunk exposed-ore scan cache. Which ores in a chunk are cave-exposed is
     * player-independent, so the ~4096-block/section scan runs ONCE per chunk and every
     * player/send reuses the result — instead of re-scanning on the main thread for every
     * chunk send (the pre-cache behaviour that collapsed TPS at high player counts).
     * Invalidated precisely on block-change + chunk-unload; a TTL bounds staleness from
     * non-event changes. Main-thread only (onChunkSent + all invalidation events).
     */
    private static final Map<ServerLevel, Long2ObjectOpenHashMap<CacheEntry>> SCAN_CACHE = new java.util.IdentityHashMap<>();
    private record CacheEntry(long[] exposed, long tick) {}
    private static final long[] EMPTY = new long[0];
    /** Safety cap so a missed unload event can't grow a level's cache unbounded. */
    private static final int MAX_CACHED_CHUNKS_PER_LEVEL = 16384;

    private OreReveal() {}

    public static void register(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new OreReveal(), plugin);
        long interval = Math.max(1, SourbyCraftConfig.raytraceIntervalTicks);
        Bukkit.getScheduler().runTaskTimer(plugin, OreReveal::tickCycle, interval, interval);
        plugin.getLogger().info("[antixray] ore reveal " + (RayTraceWorker.ENABLED.get() ? "ENABLED" : "disabled")
            + " (interval=" + interval + "t distance=" + SourbyCraftConfig.raytraceDistance
            + " checks/cycle=" + SourbyCraftConfig.raytraceMaxChecksPerCycle + ")");
        if (RayTraceWorker.ENABLED.get()) {
            boolean anyPaperAntiXray = false;
            for (org.bukkit.World w : Bukkit.getWorlds()) {
                if (((org.bukkit.craftbukkit.CraftWorld) w).getHandle().paperConfig().anticheat.antiXray.enabled) {
                    anyPaperAntiXray = true;
                    break;
                }
            }
            if (!anyPaperAntiXray) {
                plugin.getLogger().warning("[antixray] raytrace enabled but no world has paper anti-xray enabled — "
                    + "ore reveal is a complementary layer and stays inert until anticheat.anti-xray.enabled: true "
                    + "in paper-world-defaults.yml (buried ores would leak anyway without the Paper engine).");
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

        final UUID pid = player.getUUID();
        final LongOpenHashSet pending = PENDING.computeIfAbsent(pid, id -> new LongOpenHashSet());
        final int maxPending = SourbyCraftConfig.raytraceMaxPendingPerPlayer;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (final long key : exposed) {
            if (pending.size() >= maxPending) break;   // budget full: remaining ores stay visible (fail-open)
            if (VisibilityCache.isVisible(pid, key)) continue; // already confirmed visible
            if (!pending.add(key)) continue;           // already hidden + pending for this player
            pos.set(key);
            player.connection.send(new ClientboundBlockUpdatePacket(pos.immutable(), fakeState(level, pos.getY())));
        }
    }

    /** Player-independent exposed-ore scan, cached per chunk with a TTL. Main thread only. */
    private static long[] getOrComputeExposed(final ServerLevel level, final LevelChunk chunk,
                                              final java.util.Set<Block> extraHidden, final boolean fluidObscures) {
        final long chunkKey = chunk.getPos().pack();
        final Long2ObjectOpenHashMap<CacheEntry> perLevel =
            SCAN_CACHE.computeIfAbsent(level, l -> new Long2ObjectOpenHashMap<>());
        final long now = level.getGameTime();
        final CacheEntry cached = perLevel.get(chunkKey);
        if (cached != null && (now - cached.tick()) < SourbyCraftConfig.raytraceCacheTtlTicks) {
            return cached.exposed();
        }
        if (perLevel.size() > MAX_CACHED_CHUNKS_PER_LEVEL) perLevel.clear(); // bound memory; forces cheap re-scan
        final long[] exposed = scanExposed(level, chunk, extraHidden, fluidObscures);
        perLevel.put(chunkKey, new CacheEntry(exposed, now));
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

    // --- cache invalidation (all main thread) ---

    private static void invalidateChunk(final ServerLevel level, final int chunkX, final int chunkZ) {
        final Long2ObjectOpenHashMap<CacheEntry> perLevel = SCAN_CACHE.get(level);
        if (perLevel != null) perLevel.remove(net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ));
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
    }

    private static boolean isCandidate(final BlockState state, final java.util.Set<Block> extraHidden) {
        // MC 26.2: only GOLD/IRON/COPPER_ORES remain BlockTags fields; the rest moved to BlockItemTags.<x>.block()
        if (state.is(net.minecraft.tags.BlockItemTags.COAL_ORES.block()) || state.is(BlockTags.IRON_ORES) || state.is(BlockTags.COPPER_ORES)
            || state.is(BlockTags.GOLD_ORES) || state.is(net.minecraft.tags.BlockItemTags.REDSTONE_ORES.block()) || state.is(net.minecraft.tags.BlockItemTags.EMERALD_ORES.block())
            || state.is(net.minecraft.tags.BlockItemTags.LAPIS_ORES.block()) || state.is(net.minecraft.tags.BlockItemTags.DIAMOND_ORES.block())
            || state.is(Blocks.NETHER_QUARTZ_ORE) || state.is(Blocks.ANCIENT_DEBRIS)) {
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
            if (pending == null || pending.isEmpty()) continue;
            final ServerLevel level = player.level();
            final Vec3 eye = player.getEyePosition();
            int budget = SourbyCraftConfig.raytraceMaxChecksPerCycle;
            final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
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
}
