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
    /** Worlds managed through the AuroraWorlds API: AWF worlds without being listed in the config. */
    private final java.util.Set<String> managed = ConcurrentHashMap.newKeySet();
    /**
     * Stores opened ahead of a runtime world's creation, by storage id. The engine creates worlds
     * on the global tick, a region thread where store I/O is refused, so the stores are opened
     * beforehand off it and {@link #open} takes them from here.
     */
    private final Map<String, AwfStore> prepared = new ConcurrentHashMap<>();
    /** Managed worlds that are copy-on-write instances of a template: world name to template name. */
    private final Map<String, String> templateOf = new ConcurrentHashMap<>();
    /** Managed worlds whose empty new chunks are pruned (void worlds). */
    private final java.util.Set<String> pruning = ConcurrentHashMap.newKeySet();
    /** Managed worlds' save bounds. */
    private final Map<String, dev.iyanz.sourbycraft.api.world.WorldProperties.Bounds> bounds = new ConcurrentHashMap<>();
    /**
     * Opened template stores by storage id. A template never changes after it is saved, so every
     * instance of it shares one read-only store and its chunk index.
     */
    private final Map<String, AwfStore> templateStores = new ConcurrentHashMap<>();
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
                    loadManaged(current);
                    if ("redis".equals(settings.backend()) && AwfBackend.named("redis") == null) {
                        registerRedis();
                    }
                    global = current;
                }
            }
        }
        return current;
    }

    /**
     * Registers the built-in Redis backend from {@code [aurora.awf.redis]}. A Redis that does not
     * answer is reported, not hidden: the backend is still registered, so a world stored there
     * fails to load instead of quietly opening from region files.
     */
    private static void registerRedis() {
        final dev.iyanz.sourbycraft.awf.redis.RedisBackend redis;
        try {
            redis = new dev.iyanz.sourbycraft.awf.redis.RedisBackend(
                dev.iyanz.sourbycraft.awf.redis.RedisSettings.readEarly(AURORA_FILE, UNIFIED_FILE));
        } catch (final IllegalArgumentException invalid) {
            SourbyLogger.error("Aurora World Fabric: the Redis backend is misconfigured: " + invalid.getMessage());
            return;
        }
        AwfBackend.register(redis);
        try {
            redis.checkServer();
            SourbyLogger.info("Aurora World Fabric: stores on " + redis.describe());
        } catch (final IOException unreachable) {
            SourbyLogger.error("Aurora World Fabric: Redis is not reachable (" + unreachable.getMessage()
                + "); worlds stored there will fail to load until it is");
        }
    }

    /** The registry file of worlds managed at runtime; read once when the engine starts. */
    public static final Path MANAGED_FILE = Path.of("sourbycraft_config", "aurora-worlds.json");

    private static void loadManaged(final AwfEngine engine) {
        try {
            for (final var entry : new dev.iyanz.sourbycraft.awf.world.AuroraWorldRegistry(MANAGED_FILE).all()) {
                engine.manageWorld(entry.name(), entry.template(), entry.prunesEmptyChunks());
                if (entry.propertiesOrNone().saveBounds() != null) {
                    engine.bounds.put(entry.name(), entry.propertiesOrNone().saveBounds());
                }
            }
        } catch (final IOException failed) {
            // Their stores still open (an existing store always does); only a world created at
            // runtime and never committed would lose its AWF attachment, and the API refuses to
            // work with a registry it cannot read.
            SourbyLogger.warn("Aurora World Fabric could not read " + MANAGED_FILE + ": " + failed.getMessage());
        }
    }

    /** Makes a world an AWF world before its storages open. Called before the world is created. */
    public static void manage(final String worldName) {
        manage(worldName, null);
    }

    /**
     * As {@link #manage(String)}, for a world whose chunks fall through to a template's.
     *
     * @param template the template the world is an instance of, or {@code null}
     */
    public static void manage(final String worldName, final String template) {
        global().manageWorld(worldName, template, false);
    }

    /**
     * @param pruneEmpty whether empty chunks nothing has stored are pruned; only for worlds that
     *     generate empty chunks (the void generator)
     */
    public static void manage(final String worldName, final String template, final boolean pruneEmpty) {
        global().manageWorld(worldName, template, pruneEmpty);
    }

    void manageWorld(final String worldName, final String template) {
        manageWorld(worldName, template, false);
    }

    void manageWorld(final String worldName, final String template, final boolean pruneEmpty) {
        if (template != null) this.templateOf.put(worldName, template);
        else this.templateOf.remove(worldName);
        if (pruneEmpty) this.pruning.add(worldName);
        else this.pruning.remove(worldName);
        this.managed.add(worldName);
    }

    /**
     * Sets a managed world's pruning and save bounds, for storages opened later and for those open
     * now. Called when the world's properties change.
     */
    public static void configure(final String worldName, final boolean pruneEmpty,
                                 final dev.iyanz.sourbycraft.api.world.WorldProperties.Bounds saveBounds) {
        global().configureWorld(worldName, pruneEmpty, saveBounds);
    }

    void configureWorld(final String worldName, final boolean pruneEmpty,
                        final dev.iyanz.sourbycraft.api.world.WorldProperties.Bounds saveBounds) {
        final AwfEngine engine = this;
        if (pruneEmpty) engine.pruning.add(worldName);
        else engine.pruning.remove(worldName);
        if (saveBounds != null) engine.bounds.put(worldName, saveBounds);
        else engine.bounds.remove(worldName);
        for (final AwfRegionStorage storage : engine.storages.values()) {
            final Path folder = Path.of(storage.name());
            boolean mine = false;
            for (final Path element : folder) {
                if (element.toString().equals(worldName)) mine = true;
            }
            if (!mine) continue;
            storage.pruneKind(pruneEmpty ? folder.getFileName().toString() : null);
            storage.saveBounds(saveBounds);
        }
    }

    /** Stops treating a world as managed. Called after the world is deleted. */
    public static void release(final String worldName) {
        global().managed.remove(worldName);
        global().templateOf.remove(worldName);
        global().pruning.remove(worldName);
        global().bounds.remove(worldName);
    }

    /** Where templates are kept: one folder per template, holding one store per storage folder. */
    public static final Path TEMPLATES = Path.of("awf-templates");

    /** The storage id of one of a template's stores, for example {@code awf-templates/lobby/region}. */
    static String templateStorageId(final String template, final String storageFolder) {
        return storageId(TEMPLATES.resolve(template).resolve(storageFolder));
    }

    /** Forgets a template's opened stores. Called after the template is deleted. */
    public static void forgetTemplate(final String template) {
        final AwfEngine engine = global();
        for (final String folder : STORAGE_FOLDERS) {
            engine.templateStores.remove(templateStorageId(template, folder));
        }
    }

    /** The name of the configured backend; templates are kept only on the FILE backend. */
    public static String backendName() {
        return global().settings.backend();
    }

    /** The configured number of committed generations each store keeps. */
    public static int retainedGenerations() {
        return global().settings.retainedGenerations();
    }

    /** The storage folders the engine opens in every dimension folder. */
    public static final List<String> STORAGE_FOLDERS = List.of("region", "entities", "poi");

    /**
     * Opens the AWF stores of a managed world's dimension folder before the world is created or
     * loaded. Must run off the region threads; the storages that open on the global tick then use
     * these stores instead of opening them there.
     */
    public static void prepare(final Path dimensionFolder, final String template) throws IOException {
        global().prepareStores(dimensionFolder, template);
    }

    void prepareStores(final Path dimensionFolder) throws IOException {
        prepareStores(dimensionFolder, null);
    }

    void prepareStores(final Path dimensionFolder, final String template) throws IOException {
        final AwfBackend backend = this.backends.apply(this.settings.backend());
        if (backend == null) {
            throw new IOException("AWF backend '" + this.settings.backend() + "' (" + AwfSettings.BACKEND_KEY
                + ") is not registered");
        }
        for (final String folder : STORAGE_FOLDERS) {
            if (template != null) templateBase(template, folder);
            final Path storageFolder = dimensionFolder.resolve(folder);
            final String id = storageId(storageFolder);
            if (this.storages.containsKey(storageFolder.toAbsolutePath().normalize().toString())
                || this.prepared.containsKey(id)) {
                continue;
            }
            this.prepared.put(id, backend.open(id, WorldRole.VANILLA, this.settings.retainedGenerations()));
        }
    }

    /** Drops stores {@link #prepare} opened for a world that was then not created. */
    public static void unprepare(final Path dimensionFolder) {
        final AwfEngine engine = global();
        for (final String folder : STORAGE_FOLDERS) {
            engine.prepared.remove(storageId(dimensionFolder.resolve(folder)));
        }
    }

    /** Discards every open storage under {@code folder}; see {@link AwfRegionStorage#discard()}. */
    public static int discardUnder(final Path folder) {
        final java.util.List<AwfRegionStorage> found = storagesUnder(folder);
        found.forEach(AwfRegionStorage::discard);
        return found.size();
    }

    /** Every open storage whose folder lies under {@code folder}. */
    public static java.util.List<AwfRegionStorage> storagesUnder(final Path folder) {
        final AwfEngine current = global;
        if (current == null) return java.util.List.of();
        final Path root = folder.toAbsolutePath().normalize();
        final java.util.List<AwfRegionStorage> found = new java.util.ArrayList<>();
        for (final AwfRegionStorage storage : current.storages.values()) {
            if (Path.of(storage.name()).startsWith(root)) found.add(storage);
        }
        return found;
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
        final boolean managedWorld = AwfRegionStorage.listed(regionFolder, this.managed);
        final boolean listed = managedWorld || AwfRegionStorage.listed(regionFolder, this.settings.worlds());
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
        final String template = managedWorld ? templateFor(regionFolder) : null;
        final ChunkSource base = template == null ? null
            : templateBase(template, regionFolder.getFileName().toString());
        final WorldRole role = template == null ? WorldRole.VANILLA : WorldRole.INSTANCE;
        final AwfStore prepared = this.prepared.remove(id);
        final AwfStore store = prepared != null ? prepared
            : backend.open(id, role, this.settings.retainedGenerations());
        final AwfWorld world = new AwfWorld(name, role, base, store, this.settings.residentChunks());
        final AwfRegionStorage storage = new AwfRegionStorage(name, world, this.settings, this.storageLane,
            this.nanoClock.getAsLong(), () -> this.storages.remove(name));
        if (managedWorld && prunes(regionFolder)) storage.pruneEmpty(regionFolder.getFileName().toString());
        if (managedWorld) storage.saveBounds(boundsFor(regionFolder));
        if (this.storages.putIfAbsent(name, storage) != null) {
            throw new IOException("AWF storage " + name + " is already open");
        }
        return storage;
    }

    private dev.iyanz.sourbycraft.api.world.WorldProperties.Bounds boundsFor(final Path regionFolder) {
        for (final Path element : regionFolder.toAbsolutePath().normalize()) {
            final var found = this.bounds.get(element.toString());
            if (found != null && this.managed.contains(element.toString())) return found;
        }
        return null;
    }

    private boolean prunes(final Path regionFolder) {
        for (final Path element : regionFolder.toAbsolutePath().normalize()) {
            if (this.pruning.contains(element.toString()) && this.managed.contains(element.toString())) return true;
        }
        return false;
    }

    /** The template of the managed instance world this folder belongs to, or {@code null}. */
    private String templateFor(final Path regionFolder) {
        for (final Path element : regionFolder.toAbsolutePath().normalize()) {
            final String template = this.templateOf.get(element.toString());
            if (template != null && this.managed.contains(element.toString())) return template;
        }
        return null;
    }

    /**
     * A template's store for one storage folder, opened read-only once and shared, or {@code null}
     * when the template has nothing for that folder (a template without POI, say).
     */
    private AwfStore templateBase(final String template, final String storageFolder) throws IOException {
        // Templates are files under awf-templates/ whatever backend the worlds use.
        final AwfBackend backend = AwfBackend.FILE;
        final String id = templateStorageId(template, storageFolder);
        final AwfStore cached = this.templateStores.get(id);
        if (cached != null) return cached;
        if (!backend.exists(id)) return null;
        final AwfStore opened = backend.open(id, WorldRole.TEMPLATE, this.settings.retainedGenerations());
        final AwfStore raced = this.templateStores.putIfAbsent(id, opened);
        return raced != null ? raced : opened;
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
        if (current == null) return 0;
        final int failed = current.closeEvery();
        if (AwfBackend.named("redis") instanceof dev.iyanz.sourbycraft.awf.redis.RedisBackend redis) redis.shutdown();
        return failed;
    }

    /** The configured backend. */
    public static AwfBackend backend() throws IOException {
        final AwfEngine engine = global();
        final AwfBackend backend = engine.backends.apply(engine.settings.backend());
        if (backend == null) {
            throw new IOException("AWF backend '" + engine.settings.backend() + "' (" + AwfSettings.BACKEND_KEY
                + ") is not registered");
        }
        return backend;
    }

    /** The storage id of one storage folder of a dimension folder. */
    public static String storeId(final Path dimensionFolder, final String storageFolder) {
        return storageId(dimensionFolder.resolve(storageFolder));
    }

    /** Whether any store of a dimension folder exists on the configured backend. */
    public static boolean storesExist(final Path dimensionFolder) throws IOException {
        final AwfBackend backend = backend();
        for (final String folder : STORAGE_FOLDERS) {
            if (backend.exists(storeId(dimensionFolder, folder))) return true;
        }
        return false;
    }

    /** Deletes every store of a dimension folder on the configured backend. */
    public static void deleteStores(final Path dimensionFolder) throws IOException {
        final AwfBackend backend = backend();
        for (final String folder : STORAGE_FOLDERS) backend.delete(storeId(dimensionFolder, folder));
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
