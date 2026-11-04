package dev.iyanz.sourbycraft.world;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import com.mojang.serialization.Dynamic;
import io.papermc.paper.world.saveddata.PaperLevelOverrides;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.WanderingTraderData;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.WorldData;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Level data that the global tick owns and may therefore save from the global tick during autosave and
 * {@code save-all}. Two seams, two feature patches:
 *
 * <p><b>0027</b> — {@link #addGlobalOwnedTypes}: {@link PaperLevelOverrides} ({@code paper:level_overrides}) joins
 * the Canvas whitelist of {@code RegionizedServer#autosaveSafeWorldData}. It is the load path for game time, spawn,
 * game type, difficulty, the initialized flag and the per-world distance override; {@code level.dat} is only the
 * fallback when that file does not exist yet. The global tick advances game time and marks the data dirty every
 * tick, and {@code SavedDataStorage#saveSpecific} encodes it on the calling (global) thread and writes the file on
 * the dimension data I/O pool, the same lane the existing map/weather/gamerule autosave uses.</p>
 *
 * <p><b>0056</b> — {@link #saveWorldOwnedData} / {@link #saveServerOwnedData}: the types Folia and Canvas left to
 * the clean-shutdown save ({@code RegionShutdownThread}: {@code ServerLevel#saveLevelData(true)} and
 * {@code MinecraftServer#saveGlobalData(true)}), so a crash lost every change since boot. Each type is saved by
 * its own {@code saveSpecific} call ({@link #saveContained}) so a failing encode re-marks only that type dirty and
 * cannot drop another type's dirty flag (one shared call clears flags before it encodes). Per world:
 * {@link WorldBorder} (ticked on the global tick, mutated by commands and plugins from any thread; its codec reads
 * plain fields through {@code WorldBorder.Settings}, so a concurrent change gives a slightly stale snapshot, not
 * an exception). Server-wide (the overworld {@code data/}): {@link RandomSequences} (concurrent map; a sequence
 * advanced on a region thread at the same instant may be encoded torn — its two seed longs are read outside the
 * mutators' lock, as vanilla's single thread never had to care) and the {@code CommandStorage.Container}s
 * ({@code /data storage}; a plain map, so a concurrent {@code put} can make the encode throw and the container
 * stays dirty for the next autosave). {@code level.dat}: its gameplay fields are shadowed by
 * {@code level_overrides.dat}, what it alone carries is the data-pack configuration, {@code allowCommands}, the
 * server brands and {@code LastPlayed}. The tag is built on the global tick; the periodic autosave writes the file
 * on one ordered daemon thread ({@link #scheduleLevelDat}), so a stalled disk cannot hold the global tick (seen
 * 2026-10-22: 57-60 s in {@code write0} during a disk stall). {@code save-all} still writes on the calling thread
 * ({@link #saveLevelDat}), after any scheduled write, and the clean-shutdown save waits for a scheduled write first
 * ({@link #awaitLevelDatWrites}, feature patch 0077), so an older tag can never replace a newer file.</p>
 *
 * <p><b>0068</b> — wandering trader countdown, delay and chance are global-owned and saved
 * with the per-world contained types. No region callback mutates this saved data.</p>
 *
 * <p>Region-owned {@link SavedData} (raids, the dragon fight, forceload tickets, the plugin world
 * PDC) must never be listed here; see {@code docs/architecture/persistence-save-paths.md} for who
 * saves what and when (forceload tickets are saved periodically from their own detached snapshot,
 * feature patch 0067). The {@code /schedule} queue ({@code ServerLevel.scheduledEvents}) has been
 * global-owned since feature patch 0065 (ticked and mutated only on the global tick), but is still
 * saved only at a clean stop: it is not listed here until its class is confirmed to be a
 * {@link SavedData} held in the world's data storage.</p>
 */
public final class WorldMetadataAutosave {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    /** Added to the shared Canvas whitelist of {@code autosaveSafeWorldData} (patch 0027). */
    public static final List<Class<? extends SavedData>> GLOBAL_OWNED_TYPES = List.of(PaperLevelOverrides.class);

    /** Per-world types saved one {@code saveSpecific} call each from the global tick (patch 0056). */
    public static final List<Class<? extends SavedData>> WORLD_CONTAINED_TYPES = List.of(WorldBorder.class, WanderingTraderData.class);

    /** Server-wide types (overworld {@code data/}) saved one call each from the global tick (patch 0056). */
    public static final List<Class<? extends SavedData>> SERVER_CONTAINED_TYPES = serverContainedTypes();

    /**
     * One daemon thread for scheduled {@code level.dat} writes, created on the first periodic autosave. Its queue
     * holds one task: {@link #scheduleLevelDat} never submits while a write is outstanding, and a refusal is a skip.
     */
    private static final ExecutorService LEVEL_DAT_WRITER = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1), task -> {
            final Thread thread = new Thread(task, "SourbyCraft level.dat writer");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());

    /** Held for the duration of one {@code level.dat} file write; package-private so tests can hold the disk "busy". */
    static final Object LEVEL_DAT_WRITE_LOCK = new Object();

    private static final AtomicBoolean LEVEL_DAT_SKIP_LOGGED = new AtomicBoolean();

    /** The last scheduled write; guarded by the class monitor. */
    private static CompletableFuture<Boolean> pendingLevelDat = CompletableFuture.completedFuture(true);

    private WorldMetadataAutosave() {
    }

    public static void addGlobalOwnedTypes(final List<Class<? extends SavedData>> toSave) {
        toSave.addAll(GLOBAL_OWNED_TYPES);
    }

    /** Patch 0056 call site in {@code autosaveSafeWorldData}: one world's global-tick-owned data. */
    public static CompletableFuture<?> saveWorldOwnedData(final SavedDataStorage storage, final boolean sync) {
        return saveContained(storage, WORLD_CONTAINED_TYPES, sync);
    }

    /**
     * Patch 0056 call site in {@code autosaveSafeGlobalData}: the server data storage plus {@code level.dat}. With
     * {@code sync} ({@code save-all}) {@code level.dat} is written before this returns; otherwise (the periodic
     * autosave) only its tag is built here and the write is scheduled.
     */
    public static void saveServerOwnedData(final MinecraftServer server, final boolean sync) {
        saveServerOwnedData(server.getDataStorage(), sync);
        final GameProfile profile = server.getSingleplayerProfile();
        final UUID singleplayerId = profile == null ? null : profile.id();
        if (sync) {
            saveLevelDat(server.storageSource, server.getWorldData(), singleplayerId);
        } else {
            scheduleLevelDat(server.storageSource, server.getWorldData(), singleplayerId);
        }
    }

    public static CompletableFuture<?> saveServerOwnedData(final SavedDataStorage storage, final boolean sync) {
        return saveContained(storage, SERVER_CONTAINED_TYPES, sync);
    }

    /**
     * Writes {@code level.dat} on the calling thread ({@code save-all}): the tag is built from live level data,
     * any scheduled write is waited for first, then the file goes through the temp-file + replace of
     * {@code LevelStorageAccess#saveLevelData} (I/O failures are logged there), as {@code saveDataTag} does.
     * Returns false, and logs, when the tag could not be built; the previous file is then left in place.
     */
    public static boolean saveLevelDat(final LevelStorageSource.LevelStorageAccess access, final WorldData data,
                                       final @Nullable UUID singleplayerId) {
        final CompoundTag tag = encodeLevelDat(data, singleplayerId);
        if (tag == null) {
            return false;
        }
        awaitLevelDatWrites();
        writeLevelDat(access, tag);
        return true;
    }

    /**
     * The periodic autosave's {@code level.dat}: builds the tag on the calling (global) thread, which owns the
     * level data, and writes the file on {@link #LEVEL_DAT_WRITER}, so the caller never waits on the disk. While
     * the previous scheduled write has not finished (a slow or stalled disk) this one is skipped, not queued: the
     * next autosave writes the then-current data, and at most one write is ever outstanding. The future completes
     * with false when nothing was written (encode failure or skip).
     */
    public static CompletableFuture<Boolean> scheduleLevelDat(final LevelStorageSource.LevelStorageAccess access,
                                                              final WorldData data, final @Nullable UUID singleplayerId) {
        final CompoundTag tag = encodeLevelDat(data, singleplayerId);
        if (tag == null) {
            return CompletableFuture.completedFuture(false);
        }
        synchronized (WorldMetadataAutosave.class) {
            if (!pendingLevelDat.isDone()) {
                if (LEVEL_DAT_SKIP_LOGGED.compareAndSet(false, true)) {
                    LOGGER.warn("The previous level.dat write has not finished (slow or stalled disk?); skipping the periodic "
                        + "level.dat write until it does. The global tick does not wait for it.");
                }
                return CompletableFuture.completedFuture(false);
            }
            final CompletableFuture<Boolean> write;
            try {
                write = CompletableFuture.supplyAsync(() -> {
                    writeLevelDat(access, tag);
                    LEVEL_DAT_SKIP_LOGGED.set(false);
                    return true;
                }, LEVEL_DAT_WRITER);
            } catch (final RejectedExecutionException refused) {
                return CompletableFuture.completedFuture(false);
            }
            pendingLevelDat = write;
            return write;
        }
    }

    /**
     * Waits for the scheduled {@code level.dat} write, if any. Feature patch 0077 calls it from
     * {@code MinecraftServer#saveGlobalData} before the shutdown save writes {@code level.dat}, so an older
     * scheduled tag cannot replace the file after it.
     */
    public static void awaitLevelDatWrites() {
        final CompletableFuture<?> pending;
        synchronized (WorldMetadataAutosave.class) {
            pending = pendingLevelDat;
        }
        try {
            pending.join();
        } catch (final CompletionException | CancellationException failed) {
            // writeLevelDat logs its own failures; nothing more to wait for
        }
    }

    private static @Nullable CompoundTag encodeLevelDat(final WorldData data, final @Nullable UUID singleplayerId) {
        try {
            return data.createTag(singleplayerId);
        } catch (final Throwable thrown) {
            LOGGER.warn("Could not write level.dat for the periodic save; the previous file is kept", thrown);
            return null;
        }
    }

    /** Same file operation as {@code saveDataTag(data, id)}: the tag is wrapped in {@code Data} and replaced atomically. */
    private static void writeLevelDat(final LevelStorageSource.LevelStorageAccess access, final CompoundTag tag) {
        synchronized (LEVEL_DAT_WRITE_LOCK) {
            access.saveLevelData(new Dynamic<>(NbtOps.INSTANCE, tag));
        }
    }

    /**
     * One {@code saveSpecific} per type. {@code saveSpecific} clears a dirty flag before it encodes, so an encode
     * that throws inside one shared call would leave every type cleared earlier in that call unsaved; here the
     * blast radius is the failing type, whose instances are marked dirty again. Encoding runs on the calling
     * thread, the file writes on {@code Util.DIMENSION_DATA_IO_POOL}; with {@code sync} the writes are joined
     * ({@code save-all}), otherwise the future is returned and the autosave does not wait.
     */
    static CompletableFuture<?> saveContained(final SavedDataStorage storage, final List<Class<? extends SavedData>> types,
                                              final boolean sync) {
        final List<CompletableFuture<?>> writes = new ArrayList<>(types.size());
        for (final Class<? extends SavedData> type : types) {
            final List<SavedData> dirty = dirtyInstances(storage, type);
            if (dirty.isEmpty()) {
                continue;
            }
            try {
                writes.add(storage.saveSpecific(() -> List.of(type)));
            } catch (final Throwable thrown) {
                for (final SavedData instance : dirty) {
                    instance.setDirty();
                }
                LOGGER.warn("Could not encode {} for the periodic save; it stays dirty for the next one",
                    type.getSimpleName(), thrown);
            }
        }
        final CompletableFuture<?> all = CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new));
        if (sync) {
            all.join();
        }
        return all;
    }

    private static List<SavedData> dirtyInstances(final SavedDataStorage storage, final Class<? extends SavedData> type) {
        final List<SavedData> dirty = new ArrayList<>(1);
        for (final Optional<SavedData> entry : storage.cache.values()) {
            final SavedData instance = entry.orElse(null);
            if (instance != null && instance.isDirty() && type.isInstance(instance)) {
                dirty.add(instance);
            }
        }
        return dirty;
    }

    private static List<Class<? extends SavedData>> serverContainedTypes() {
        final List<Class<? extends SavedData>> types = new ArrayList<>(2);
        types.add(RandomSequences.class);
        try {
            // Private nested class (one instance per namespace); the saveSpecific filter only needs isInstance.
            types.add(Class.forName("net.minecraft.world.level.storage.CommandStorage$Container").asSubclass(SavedData.class));
        } catch (final ReflectiveOperationException | ClassCastException e) {
            LOGGER.warn("CommandStorage.Container not found; /data storage is saved only at a clean stop", e);
        }
        return List.copyOf(types);
    }
}
