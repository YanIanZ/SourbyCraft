package dev.iyanz.sourbycraft.world;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import io.papermc.paper.world.saveddata.PaperLevelOverrides;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.saveddata.SavedData;
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
 * stays dirty for the next autosave). {@code level.dat} is rewritten synchronously by {@link #saveLevelDat}, as
 * vanilla's main-thread autosave does; its gameplay fields are shadowed by {@code level_overrides.dat}, what it
 * alone carries is the data-pack configuration, {@code allowCommands}, the server brands and {@code LastPlayed}.</p>
 *
 * <p>Region-owned {@link SavedData} (raids, the dragon fight, wandering trader timers, forceload tickets, the
 * never-ticked scheduled events, the plugin world PDC) must never be listed here; see
 * {@code docs/architecture/persistence-save-paths.md} for who saves what and when.</p>
 */
public final class WorldMetadataAutosave {

    private static final Logger LOGGER = LogUtils.getClassLogger();

    /** Added to the shared Canvas whitelist of {@code autosaveSafeWorldData} (patch 0027). */
    public static final List<Class<? extends SavedData>> GLOBAL_OWNED_TYPES = List.of(PaperLevelOverrides.class);

    /** Per-world types saved one {@code saveSpecific} call each from the global tick (patch 0056). */
    public static final List<Class<? extends SavedData>> WORLD_CONTAINED_TYPES = List.of(WorldBorder.class);

    /** Server-wide types (overworld {@code data/}) saved one call each from the global tick (patch 0056). */
    public static final List<Class<? extends SavedData>> SERVER_CONTAINED_TYPES = serverContainedTypes();

    private WorldMetadataAutosave() {
    }

    public static void addGlobalOwnedTypes(final List<Class<? extends SavedData>> toSave) {
        toSave.addAll(GLOBAL_OWNED_TYPES);
    }

    /** Patch 0056 call site in {@code autosaveSafeWorldData}: one world's global-tick-owned data. */
    public static CompletableFuture<?> saveWorldOwnedData(final SavedDataStorage storage, final boolean sync) {
        return saveContained(storage, WORLD_CONTAINED_TYPES, sync);
    }

    /** Patch 0056 call site in {@code autosaveSafeGlobalData}: the server data storage plus {@code level.dat}. */
    public static void saveServerOwnedData(final MinecraftServer server, final boolean sync) {
        saveServerOwnedData(server.getDataStorage(), sync);
        final GameProfile profile = server.getSingleplayerProfile();
        saveLevelDat(server.storageSource, server.getWorldData(), profile == null ? null : profile.id());
    }

    public static CompletableFuture<?> saveServerOwnedData(final SavedDataStorage storage, final boolean sync) {
        return saveContained(storage, SERVER_CONTAINED_TYPES, sync);
    }

    /**
     * Writes {@code level.dat} the way the clean shutdown does ({@code MinecraftServer#saveGlobalData}), on the
     * calling thread: the tag is built from live level data, the file goes through the temp-file + replace of
     * {@code LevelStorageAccess#saveDataTag} (I/O failures are logged there). Returns false, and logs, when the
     * tag could not be built; the previous file is then left in place.
     */
    public static boolean saveLevelDat(final LevelStorageSource.LevelStorageAccess access, final WorldData data,
                                       final @Nullable UUID singleplayerId) {
        try {
            access.saveDataTag(data, singleplayerId);
            return true;
        } catch (final Throwable thrown) {
            LOGGER.warn("Could not write level.dat for the periodic save; the previous file is kept", thrown);
            return false;
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
