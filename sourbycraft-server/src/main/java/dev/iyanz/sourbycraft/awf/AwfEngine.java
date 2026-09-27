package dev.iyanz.sourbycraft.awf;

import dev.iyanz.sourbycraft.execution.ResourceGovernor;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

/**
 * Where the engine's region storages meet Aurora World Fabric. {@code RegionFileStorage} calls
 * {@link #attach} once, from its constructor; everything after that goes through the returned
 * {@link AwfRegionStorage}.
 */
public final class AwfEngine {

    private static final Path AURORA_FILE = Path.of("sourbycraft_config", "aurora.toml");
    private static final Path UNIFIED_FILE = Path.of("sourbycraft_config", "sourbycraft_global_config.toml");

    /** The process's instance, created on the first attach so the settings are read then. */
    private static volatile AwfEngine global;

    private final AwfSettings settings;
    private final Executor storageLane;
    private final LongSupplier nanoClock;
    private final java.util.function.Function<String, AwfBackend> backends;
    private final Map<String, AwfRegionStorage> storages = new ConcurrentHashMap<>();
    private volatile boolean warnedMissingBackend;

    AwfEngine(final AwfSettings settings, final Executor storageLane, final LongSupplier nanoClock) {
        this(settings, storageLane, nanoClock, AwfBackend::named);
    }

    AwfEngine(final AwfSettings settings, final Executor storageLane, final LongSupplier nanoClock,
              final java.util.function.Function<String, AwfBackend> backends) {
        this.settings = settings;
        this.storageLane = storageLane;
        this.nanoClock = nanoClock;
        this.backends = backends;
    }

    /**
     * A region folder's storage id: relative to the server directory when it is inside it, with
     * {@code /} separators; absolute otherwise.
     */
    static String storageId(final Path regionFolder) {
        final Path cwd = Path.of("").toAbsolutePath().normalize();
        final Path folder = regionFolder.toAbsolutePath().normalize();
        final Path id = folder.startsWith(cwd) && !folder.equals(cwd) ? cwd.relativize(folder) : folder;
        return id.toString().replace('\\', '/');
    }

    static AwfEngine global() {
        AwfEngine current = global;
        if (current == null) {
            synchronized (AwfEngine.class) {
                current = global;
                if (current == null) {
                    final AwfSettings settings = AwfSettings.readEarly(AURORA_FILE, UNIFIED_FILE);
                    current = new AwfEngine(settings,
                        task -> ResourceGovernor.GLOBAL.lane(ResourceGovernor.Lane.STORAGE).execute(task),
                        System::nanoTime);
                    if (!settings.worlds().isEmpty()) {
                        SourbyLogger.info("Aurora World Fabric: storing " + settings.worlds() + " ("
                            + settings.persistence() + ", commit every " + settings.commitIntervalSeconds()
                            + "s, " + settings.residentChunks() + " resident chunks per storage)");
                    }
                    global = current;
                }
            }
        }
        return current;
    }

    /** The instance if a storage was ever attached; {@code null} otherwise. Never creates one. */
    public static AwfEngine ifStarted() {
        return global;
    }

    /**
     * Where an export writes: the region storage itself, before AWF is attached to it.
     * {@code nbt == null} deletes the chunk.
     */
    public interface RegionSink {
        void write(int chunkX, int chunkZ, byte[] nbt) throws IOException;

        void flush() throws IOException;
    }

    /** What an export wrote. */
    public record ExportResult(int chunks, int deletions, String keptAs) {}

    /** As {@link #attach(Path, RegionSink)} with no way to export. */
    public static AwfRegionStorage attach(final Path regionFolder) throws IOException {
        return attach(regionFolder, null);
    }

    /**
     * Called by {@code RegionFileStorage}'s constructor.
     *
     * @param exportSink the storage's own region-file writer, used when the world is listed in
     *     {@code aurora.awf.export}; {@code null} if the caller cannot export
     * @return the AWF storage for this region folder, or {@code null} to use region files as usual
     * @throws IOException when the folder belongs to AWF but its store cannot be opened or
     *     exported; the world must not fall back to region files the store shadows
     */
    public static AwfRegionStorage attach(final Path regionFolder, final RegionSink exportSink) throws IOException {
        return global().open(regionFolder, exportSink);
    }

    AwfRegionStorage open(final Path regionFolder) throws IOException {
        return open(regionFolder, null);
    }

    AwfRegionStorage open(final Path regionFolder, final RegionSink exportSink) throws IOException {
        final boolean listed = AwfRegionStorage.listed(regionFolder, this.settings.worlds());
        final boolean exporting = AwfRegionStorage.listed(regionFolder, this.settings.export());
        final AwfBackend backend = this.backends.apply(this.settings.backend());
        if (backend == null) {
            if (listed || exporting) {
                // Never a fallback: the world's chunks may live only on that backend.
                throw new IOException("AWF backend '" + this.settings.backend() + "' (" + AwfSettings.BACKEND_KEY
                    + ") is not registered; refusing to load " + regionFolder);
            }
            if (!this.warnedMissingBackend) {
                this.warnedMissingBackend = true;
                SourbyLogger.warn("AWF backend '" + this.settings.backend() + "' is not registered; unlisted worlds"
                    + " cannot be checked for an existing store on it");
            }
            return null;
        }
        final String id = storageId(regionFolder);
        final boolean existing = backend.exists(id);
        if (exporting && listed) {
            SourbyLogger.warn(regionFolder + " is in both " + AwfSettings.WORLDS_KEY + " and " + AwfSettings.EXPORT_KEY
                + "; nothing is exported and the world stays in AWF");
        } else if (exporting && existing) {
            if (exportSink == null) {
                throw new IOException("export of " + regionFolder + " was requested but this storage cannot write region files");
            }
            final ExportResult result = export(backend, id, exportSink);
            SourbyLogger.info("Aurora World Fabric exported " + regionFolder + ": " + result.chunks() + " chunks and "
                + result.deletions() + " deletions written to region files; the store was kept as " + result.keptAs());
            return null;
        }
        if (!listed && !existing) return null;
        if (!listed) {
            SourbyLogger.warn("AWF store " + id + " (" + backend.name() + ") exists but its world is not in "
                + AwfSettings.WORLDS_KEY + "; opening it anyway, because the region files beneath it are older"
                + " than the chunks it holds");
        }
        final String name = regionFolder.toAbsolutePath().normalize().toString();
        final AwfStore store = backend.open(id, WorldRole.VANILLA, this.settings.retainedGenerations());
        final AwfWorld world = new AwfWorld(name, WorldRole.VANILLA, null, store, this.settings.residentChunks());
        final AwfRegionStorage storage = new AwfRegionStorage(name, world, this.settings, this.storageLane,
            this.nanoClock.getAsLong(), () -> this.storages.remove(name));
        if (this.storages.putIfAbsent(name, storage) != null) {
            throw new IOException("AWF storage " + name + " is already open");
        }
        return storage;
    }

    /**
     * Writes every chunk and deletion a store holds into region files, then moves the store aside
     * so the region files are authoritative again.
     *
     * <p>Idempotent until the final move: a crash part-way leaves the store in place, and the next
     * load exports it again from the start. The store is renamed, not deleted, so the operator can
     * check the result before removing it.</p>
     */
    ExportResult export(final AwfBackend backend, final String id, final RegionSink sink) throws IOException {
        final AwfStore store = backend.open(id, WorldRole.READ_ONLY, this.settings.retainedGenerations());
        int chunks = 0;
        for (final ChunkKey key : store.keys()) {
            final byte[] bytes = store.read(key).orElseThrow(() -> new IOException("index lists " + key + " but it has no bytes"));
            sink.write(key.x(), key.z(), bytes);
            chunks++;
        }
        int deletions = 0;
        for (final ChunkKey key : store.deleted()) {
            sink.write(key.x(), key.z(), null);
            deletions++;
        }
        sink.flush();
        return new ExportResult(chunks, deletions, backend.retire(id));
    }

    /**
     * Commits and closes every open storage, on the calling thread. Called once at shutdown, after
     * the chunk system has written its last save: Folia's shutdown never closes region storages,
     * so without this the writes since the last periodic commit would be lost on every stop.
     *
     * @return storages whose final commit failed; their uncommitted chunks are lost
     */
    public static int closeAll() {
        final AwfEngine current = global;
        return current == null ? 0 : current.closeEvery();
    }

    int closeEvery() {
        int failed = 0;
        int closed = 0;
        for (final AwfRegionStorage storage : this.storages.values()) {
            try {
                storage.close();
                closed++;
            } catch (final IOException | RuntimeException failure) {
                failed++;
                SourbyLogger.error("Aurora World Fabric could not commit " + storage.name()
                    + " at shutdown; its chunks written since the last commit are lost", failure);
            }
        }
        if (closed + failed > 0) {
            SourbyLogger.info("Aurora World Fabric: committed and closed " + closed + " storage(s) at shutdown"
                + (failed > 0 ? ", " + failed + " failed" : ""));
        }
        return failed;
    }

    /** Starts due commits. Called once a second by the metrics collector; never blocks. */
    public static void maintainAll() {
        final AwfEngine current = global;
        if (current == null) return;
        current.maintain();
    }

    int maintain() {
        final long now = this.nanoClock.getAsLong();
        int started = 0;
        for (final AwfRegionStorage storage : this.storages.values()) {
            try {
                if (storage.maintain(now)) started++;
            } catch (final RuntimeException rejected) {
                // A full storage lane: the next second tries again.
                SourbyLogger.warn("AWF commit for " + storage.name() + " could not start: " + rejected.getMessage());
            }
        }
        return started;
    }

    public AwfSettings settings() {
        return this.settings;
    }

    public Collection<AwfRegionStorage> storages() {
        return List.copyOf(this.storages.values());
    }
}
