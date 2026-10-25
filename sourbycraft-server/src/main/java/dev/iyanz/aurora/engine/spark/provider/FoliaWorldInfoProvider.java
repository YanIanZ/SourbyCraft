package dev.iyanz.aurora.engine.spark.provider;

import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.common.util.TickThread;
import dev.iyanz.aurora.engine.spark.plugin.AbstractFoliaChunkInfo;
import dev.iyanz.aurora.engine.spark.plugin.ChunkRegionCenter;
import dev.iyanz.aurora.engine.threadedregions.profiler.RegionProfiler;
import dev.iyanz.aurora.engine.threadedregions.profiler.RegionScheduleHandlePinner;
import dev.iyanz.aurora.engine.util.Util;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import me.lucko.spark.paper.common.platform.world.CountMap;
import me.lucko.spark.paper.common.platform.world.WorldInfoProvider;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameRule;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftChunk;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.Contract;
import org.jspecify.annotations.Nullable;

public class FoliaWorldInfoProvider implements WorldInfoProvider {
    /** One deadline for a whole chunk poll: {@link FoliaChunkInfo#getEntityCounts} waits until then, not 5 s per chunk. */
    private static final long CHUNK_POLL_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(5L);
    private static final long PINNED_TIMEOUT_SECONDS = 5L;

    /** A chunk poll: its result, the worlds it queued tasks to (by id, so an unloaded world is not kept), and the completion of all its counts. */
    private record ChunkPoll(ChunksResult<FoliaChunkInfo> result, List<UUID> worlds, CompletableFuture<Void> done) {}

    /** A read queued to a profiled region, and which profile (pinner) it was for. */
    private record PinnedRead(RegionScheduleHandlePinner.RegionPinner pinner, CompletableFuture<?> done) {}

    // Process-wide, not per instance: spark creates a provider per use, and every one queues to the same
    // regions. Polls are serialised by the matching lock; the region tasks only complete the futures, and
    // a finished chunk poll is forgotten by compare-and-set, without the lock.
    private static final Object CHUNK_POLL_LOCK = new Object();
    private static final AtomicReference<@Nullable ChunkPoll> LAST_CHUNK_POLL = new AtomicReference<>();
    private static final Object PINNED_READ_LOCK = new Object();
    private static @Nullable PinnedRead lastPinnedRead;

    private final Server server;

    public FoliaWorldInfoProvider() {
        this(Bukkit.getServer());
    }

    /** Test seam: a provider over a given server instead of the process-wide {@code Bukkit.getServer()}. */
    FoliaWorldInfoProvider(final Server server) {
        this.server = server;
    }

    @Override
    public CountsResult pollCounts() {
        final RegionProfiler.ProfilingState profilingState = RegionProfiler.STATE.get();
        if (profilingState != null) {
            return getCountsFromState(profilingState);
        }

        // not profiling, gather global data
        final int players = this.server.getOnlinePlayers().size();
        int entities = 0;
        int chunks = 0;

        // Tickable block entities: each region publishes its count at the end of its tick and the world
        // sums them, with no lock and no thread hop (World#getTickableTileEntityCount, RegionStatistics).
        // Block entities that do not tick are not in it, so it is lower than the count of every block
        // entity in the ticking chunks that a region profile reports, and as old as each region's last
        // tick (engine-source guard #102; was a constant 0).
        int tileEntities = 0;

        for (final World world : this.server.getWorlds()) {
            entities += world.getEntityCount();
            tileEntities += world.getTickableTileEntityCount();
            chunks += world.getChunkCount();
        }

        return new CountsResult(players, entities, tileEntities, chunks);
    }

    @Override
    public ChunksResult<FoliaChunkInfo> pollChunks() {
        final ChunksResult<FoliaChunkInfo> data = new ChunksResult<>();

        final RegionProfiler.ProfilingState profilingState = RegionProfiler.STATE.get();
        if (profilingState != null) {
            return getChunksFromState(profilingState, data);
        }

        synchronized (CHUNK_POLL_LOCK) {
            final ChunkPoll previous = LAST_CHUNK_POLL.get();
            if (previous != null && !previous.done().isDone() && this.stillLoaded(previous.worlds())) {
                // A region has not run the previous poll's task yet (stalled or saturated): hand out the
                // same result, whose counts complete when it does, instead of queueing a second task
                // behind the first. Its deadline is the previous poll's (audit PM-245).
                return previous.result();
            }

            final long deadlineNanos = System.nanoTime() + CHUNK_POLL_TIMEOUT_NANOS;
            final List<UUID> worlds = new ArrayList<>();
            final List<CompletableFuture<?>> pending = new ArrayList<>();
            for (final World world : this.server.getWorlds()) {
                final ServerLevel level = ((CraftWorld) world).getHandle();
                worlds.add(world.getUID());
                final Map<Long, List<FoliaChunkInfo>> byRegion = new HashMap<>();
                final Map<Long, List<Chunk>> chunksByRegion = new HashMap<>();
                final Map<Long, ChunkRegionCenter> centerByRegion = new HashMap<>();
                final String worldKey = Util.getWorldName(world);
                final String worldCapitalized = Util.capitalize(worldKey);

                for (final Chunk chunk : world.getLoadedChunks()) {
                    final ChunkRegionCenter center = FoliaChunkInfo.resolveRegionCenter(level, chunk.getX(), chunk.getZ(), centerByRegion);
                    final FoliaChunkInfo info = new FoliaChunkInfo(chunk.getX(), chunk.getZ(), center, new CompletableFuture<>(), deadlineNanos);
                    byRegion.computeIfAbsent(center.id(), _ -> new ArrayList<>()).add(info);
                    chunksByRegion.computeIfAbsent(center.id(), _ -> new ArrayList<>()).add(chunk);
                }

                for (final Map.Entry<Long, List<FoliaChunkInfo>> entry : byRegion.entrySet()) {
                    final ChunkRegionCenter center = centerByRegion.getOrDefault(entry.getKey(), ChunkRegionCenter.UNKNOWN);
                    data.put(buildRegionKey(worldCapitalized, center), entry.getValue());
                    queueRegionCounts(level, chunksByRegion.get(entry.getKey()), entry.getValue());
                    for (final FoliaChunkInfo info : entry.getValue()) {
                        pending.add(info.entityCounts);
                    }
                }
            }

            final ChunkPoll poll = new ChunkPoll(data, List.copyOf(worlds), CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)));
            LAST_CHUNK_POLL.set(poll);
            // Kept only while a count is outstanding: once all have completed, the per-chunk results of
            // every loaded chunk are not held until the next poll. A compare-and-set, as this runs on the
            // region thread that completed the last count, and a newer poll is never cleared.
            poll.done().whenComplete((ignored, failure) -> LAST_CHUNK_POLL.compareAndSet(poll, null));
            return data;
        }
    }

    /** Whether every world of a previous poll is still loaded; a task queued to an unloaded world may never run. */
    private boolean stillLoaded(final List<UUID> worlds) {
        for (final UUID world : worlds) {
            if (this.server.getWorld(world) == null) {
                return false;
            }
        }
        return true;
    }

    /**
     * Queues ONE task to the region owning the first of {@code chunks} (all in one region when grouped)
     * that counts the entities of every chunk it owns. A chunk another region owns by then (the region
     * split or merged since the grouping) is handed to that region on its own. This replaced one
     * {@link Priority#BLOCKING} task per loaded chunk, which a stalled region let pile up by the thousand
     * on every spark poll (audit PM-245).
     */
    private static void queueRegionCounts(final ServerLevel level, final List<Chunk> chunks, final List<FoliaChunkInfo> infos) {
        final Chunk first = chunks.get(0);
        RegionizedServer.getInstance().taskQueue.queueOrExecuteTickTask(level, first.getX(), first.getZ(), () -> {
            for (int i = 0; i < chunks.size(); ++i) {
                final Chunk chunk = chunks.get(i);
                final CompletableFuture<CountMap<EntityType>> result = infos.get(i).entityCounts;
                if (TickThread.isTickThreadFor(level, chunk.getX(), chunk.getZ())) {
                    FoliaChunkInfo.complete(result, chunk);
                } else {
                    RegionizedServer.getInstance().taskQueue.queueOrExecuteTickTask(
                        level, chunk.getX(), chunk.getZ(), () -> FoliaChunkInfo.complete(result, chunk), Priority.BLOCKING
                    );
                }
            }
        }, Priority.BLOCKING);
    }

    @Override
    public GameRulesResult pollGameRules() {
        final GameRulesResult data = new GameRulesResult();
        boolean addDefaults = true; // add defaults in the first iteration

        for (final World world : this.server.getWorlds()) {
            for (final String gameRule : world.getGameRules()) {
                final GameRule<?> ruleObj = GameRule.getByName(gameRule);
                if (ruleObj == null) {
                    continue;
                }

                if (addDefaults) {
                    //noinspection deprecation
                    data.putDefault(gameRule, Objects.toString(world.getGameRuleDefault(ruleObj)));
                }

                data.put(gameRule, world.getName(), Objects.toString(world.getGameRuleValue(ruleObj)));
            }

            addDefaults = false;
        }

        return data;
    }

    @Override
    public Collection<DataPackInfo> pollDataPacks() {
        return this.server.getDatapackManager().getEnabledPacks().stream()
            .map(pack -> new DataPackInfo(
                pack.getName(),
                PlainTextComponentSerializer.plainText().serialize(pack.getDescription()), // was Component.text().append(..).content(), which is always ""
                pack.getSource().toString().toLowerCase(Locale.ROOT).replace("_", "")
            )).toList();
    }

    private ChunksResult<FoliaChunkInfo> getChunksFromState(final RegionProfiler.ProfilingState profilingState, final ChunksResult<FoliaChunkInfo> data) {
        if (profilingState.handlePinner() instanceof RegionScheduleHandlePinner.RegionPinner pinner) {
            final ServerLevel level = pinner.level();
            final List<FoliaChunkInfo> chunks = readPinnedRegion(pinner, () -> {
                final List<FoliaChunkInfo> read = new ArrayList<>();
                final RegionizedWorldData worldData = level.getCurrentWorldData();

                for (final LevelChunk chunk : worldData.getChunks()) {
                    read.add(new FoliaChunkInfo(new CraftChunk(chunk), level.getWorld()));
                }

                return read;
            });
            data.put(level.getWorld().getName(), chunks);
            return data;
        }
        // the global tick doesn't own any chunks, so we can return an empty result
        else return new ChunksResult<>();
    }

    private static CountsResult getCountsFromState(final RegionProfiler.ProfilingState profilingState) {
        // we need tile entities, chunks, entities, and players
        // if this isn't a region pinner, this is the global tick
        if (profilingState.handlePinner() instanceof RegionScheduleHandlePinner.RegionPinner pinner) {
            final ServerLevel level = pinner.level();
            return readPinnedRegion(pinner, () -> {
                final RegionizedWorldData localWorldData = level.getCurrentWorldData();

                final int players = localWorldData.getPlayerCount();
                final int entities = localWorldData.getEntityCount();
                final int chunks = localWorldData.getChunkCount();

                // we can actually get all tile entities with this
                int tileEntities = 0;

                // we only use block ticking chunks, matches with what spark does for global polling
                for (final LevelChunk tickingChunk : localWorldData.getTickingChunks()) {
                    tileEntities += tickingChunk.getBlockEntitiesCount();
                }

                return new CountsResult(players, entities, tileEntities, chunks);
            });
        }
        else {
            // the global tick owns no world data. TECHNICALLY it owns players
            // during specific states, like for example:
            // - end credits
            // - login phase
            // - config phase
            return new CountsResult(0, 0, 0, 0);
        }
    }

    /**
     * Runs {@code read} on the profiled region and waits up to {@link #PINNED_TIMEOUT_SECONDS} for it.
     * While a read queued for the same profile has not run yet (the region is stalled), no second one is
     * queued behind it: the call fails at once, as a timeout does (audit PM-245).
     */
    private static <T> T readPinnedRegion(final RegionScheduleHandlePinner.RegionPinner pinner, final Supplier<T> read) {
        final CompletableFuture<T> result = new CompletableFuture<>();
        synchronized (PINNED_READ_LOCK) {
            final PinnedRead previous = lastPinnedRead;
            if (previous != null && previous.pinner() == pinner && !previous.done().isDone()) {
                throw new RuntimeException("Couldn't fetch localized world data: the profiled region has not run the previous request yet");
            }
            lastPinnedRead = new PinnedRead(pinner, result);
        }
        // Once it has run, forget it: the pinner holds its world, which must not outlive an unload here.
        result.whenComplete((value, failure) -> {
            synchronized (PINNED_READ_LOCK) {
                if (lastPinnedRead != null && lastPinnedRead.done() == result) {
                    lastPinnedRead = null;
                }
            }
        });
        final ChunkPos target = pinner.getCenter();
        RegionizedServer.getInstance().taskQueue.queueOrExecuteTickTask(
            // the target chunk should always be loaded due to the profiler tickets
            pinner.level(), target.x(), target.z(), () -> {
                try {
                    result.complete(read.get());
                } catch (final Throwable failure) {
                    // delivered to the waiting caller below as the cause, instead of failing the region's task
                    result.completeExceptionally(failure);
                }
            }, Priority.BLOCKING // highest possible priority, we need this information now please
        );

        try {
            return result.get(PINNED_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Couldn't fetch localized world data", ie);
        } catch (final ExecutionException | TimeoutException failure) {
            throw new RuntimeException("Couldn't fetch localized world data", failure);
        }
    }

    private static String buildRegionKey(final String worldCapitalized, final ChunkRegionCenter center) {
        if (center.id() == -1L) {
            return worldCapitalized + " [region=unknown]";
        }

        return worldCapitalized + " region at " + center.centerBlockX() + ", " + center.centerBlockZ();
    }

    public static final class FoliaChunkInfo extends AbstractFoliaChunkInfo<EntityType> {
        private final CompletableFuture<CountMap<EntityType>> entityCounts;
        private final long deadlineNanos;

        /** A chunk counted now, on the thread that owns it (the profiled region's own task). */
        FoliaChunkInfo(final Chunk chunk, final World world) {
            this(chunk.getX(), chunk.getZ(), resolveRegionCenter(
                ((CraftWorld) world).getHandle(),
                chunk.getX(),
                chunk.getZ(),
                new HashMap<>()
            ), CompletableFuture.completedFuture(calculate(chunk)), System.nanoTime());
        }

        /** A chunk whose counts a region task of its poll completes; {@link #getEntityCounts} waits until {@code deadlineNanos}. */
        FoliaChunkInfo(final int x, final int z, final ChunkRegionCenter center,
                       final CompletableFuture<CountMap<EntityType>> entityCounts, final long deadlineNanos) {
            super(x, z, center);
            this.entityCounts = entityCounts;
            this.deadlineNanos = deadlineNanos;
        }

        private static CountMap<EntityType> calculate(final Chunk chunk) {
            final CountMap<EntityType> entityCounts = new CountMap.EnumKeyed<>(EntityType.class);
            for (final Entity entity : chunk.getEntities()) {
                entityCounts.increment(entity.getType());
            }
            return entityCounts;
        }

        /** Counts {@code chunk} into {@code result}; must run on the chunk's owning region. */
        private static void complete(final CompletableFuture<CountMap<EntityType>> result, final Chunk chunk) {
            try {
                result.complete(calculate(chunk));
            } catch (final Throwable failure) {
                // as CompletableFuture.supplyAsync did: the reader gets it from getEntityCounts as the cause
                result.completeExceptionally(failure);
            }
        }

        /** The counts, waiting at most until the poll's single deadline (one deadline per poll, not 5 s per chunk). */
        @Override
        public CountMap<EntityType> getEntityCounts() {
            try {
                return this.entityCounts.get(Math.max(0L, this.deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (final InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted when trying to get entity counts", ie);
            } catch (final ExecutionException ee) {
                throw new RuntimeException("Exception reading statistics for chunk " + getX() + ", " + getZ(), ee);
            } catch (final TimeoutException te) {
                throw new RuntimeException("Timed out waiting for statistics for chunk " + getX() + ", " + getZ(), te);
            }
        }

        @Nullable
        @Contract(pure = true)
        @SuppressWarnings("deprecation")
        @Override
        public String entityTypeName(final EntityType type) {
            return type.getName();
        }

        private static ChunkRegionCenter resolveRegionCenter(
            final ServerLevel level, final int chunkX, final int chunkZ, final Map<Long, ChunkRegionCenter> cache
        ) {
            final var region = level.regioniser.getRegionAtUnsynchronised(chunkX, chunkZ);
            if (region == null) {
                return ChunkRegionCenter.UNKNOWN;
            }

            final long id = region.id;
            final ChunkRegionCenter cached = cache.get(id);
            if (cached != null) {
                return cached;
            }

            final ChunkPos center = region.getCenterChunk();
            if (center == null) {
                return ChunkRegionCenter.UNKNOWN;
            }

            final ChunkRegionCenter regionCenter = new ChunkRegionCenter(id, center.getMiddleBlockX(), center.getMiddleBlockZ());
            cache.put(id, regionCenter);

            return regionCenter;
        }

    }

}
