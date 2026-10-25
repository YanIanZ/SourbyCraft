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

    private static final Path AURORA_FILE = Path.of("sourbycraft_config", "aurora.yml");
    private static final Path UNIFIED_FILE = Path.of("sourbycraft_config", "sourbycraft_global_config.yml");

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
    /** Set once by {@link #beginShutdown()}; every AWF wait from then on shares it. */
    private volatile AwfShutdownBudget shutdownBudget;
    /** What each storage's close at shutdown confirmed; see {@link AwfShutdownReport}. */
    private final AwfShutdownReport shutdownReport = new AwfShutdownReport(SourbyLogger::error);

    AwfEngine(final AwfSettings settings, final Executor storageLane, final LongSupplier nanoClock) {
        this(settings, storageLane, nanoClock, defaultBackends(settings));
    }

    /** The registered backends, with {@code file} honouring {@code aurora.awf.delta-generations}. */
    private static java.util.function.Function<String, AwfBackend> defaultBackends(final AwfSettings settings) {
        final AwfBackend file = settings.deltaGenerations() ? new AwfBackend.FileBackend(Path.of(""), true) : AwfBackend.FILE;
        return name -> "file".equals(name) ? file : AwfBackend.named(name);
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
            final dev.iyanz.sourbycraft.awf.redis.RedisSettings settings =
                dev.iyanz.sourbycraft.awf.redis.RedisSettings.readEarly(AURORA_FILE, UNIFIED_FILE);
            final dev.iyanz.sourbycraft.awf.redis.fabric.RedisFabric fabric =
                dev.iyanz.sourbycraft.awf.redis.fabric.RedisFabric.running();
            if (fabric != null && fabric.settings().enabled()) {
                // One bounded pool (aurora.awf.redis.fabric.pools.storage) shared with the fabric,
                // so an event consumer can never exhaust the world store's connections.
                redis = dev.iyanz.sourbycraft.awf.redis.RedisBackend.sharing(settings, fabric.storageClient());
            } else {
                redis = new dev.iyanz.sourbycraft.awf.redis.RedisBackend(settings);
            }
        } catch (final RuntimeException invalid) {
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
            if (!AwfRegionStorage.worldNames(folder).contains(worldName)) continue;
            storage.pruneKind(pruneEmpty ? folder.getFileName().toString() : null);
            storage.saveBounds(saveBounds);
        }
    }

    /** Stops treating a world as managed. Called after the world is deleted. */
    public static void release(final String worldName) {
        global().releaseWorld(worldName);
    }

    void releaseWorld(final String worldName) {
        this.managed.remove(worldName);
        this.templateOf.remove(worldName);
        this.pruning.remove(worldName);
        this.bounds.remove(worldName);
    }

    /** Where templates are kept: one folder per template, holding one store per storage folder. */
    public static final Path TEMPLATES = Path.of("awf-templates");

    /** The storage id of one of a template's stores, for example {@code awf-templates/lobby/region}. */
    static String templateStorageId(final String template, final String storageFolder) {
        // A template name read from the registry never resolves outside awf-templates/.
        return storageId(dev.iyanz.sourbycraft.awf.world.AuroraTemplates.resolveInside(TEMPLATES, template)
            .resolve(storageFolder));
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

    /**
     * Closes and drops stores {@link #prepare} opened for a world that was then not created.
     * Closing releases what the store holds on its backend (Redis: the world's lease), which a
     * dropped store would otherwise keep until the server stops, refusing the world's deletion and
     * every other server's open of it in the meantime.
     */
    public static void unprepare(final Path dimensionFolder) {
        global().unprepareStores(dimensionFolder);
    }

    void unprepareStores(final Path dimensionFolder) {
        for (final String folder : STORAGE_FOLDERS) {
            final String id = storageId(dimensionFolder.resolve(folder));
            closeQuietly(this.prepared.remove(id), id);
        }
    }

    /** Closes a store this engine opened but will not use, logging rather than throwing. */
    private static void closeQuietly(final AwfStore store, final String id) {
        if (store == null) return;
        try {
            store.close();
        } catch (final IOException | RuntimeException failed) {
            SourbyLogger.warn("Aurora World Fabric could not release unused store " + id + ": " + failed.getMessage());
        }
    }

    /** Discards every open storage under {@code folder}; see {@link AwfRegionStorage#discard()}. */
    public static int discardUnder(final Path folder) {
        final java.util.List<AwfRegionStorage> found = storagesUnder(folder);
        found.forEach(AwfRegionStorage::discard);
        return found.size();
    }

    // Scoped to an unload; keeps closed handles long enough to verify commits even when the
    // inherited chunk engine catches/logs their IOException. Removed in try-with-resources.
    private static final java.util.concurrent.CopyOnWriteArrayList<CloseWatch> CLOSE_WATCHES =
        new java.util.concurrent.CopyOnWriteArrayList<>();

    public static final class CloseWatch implements AutoCloseable {
        private final Path root;
        private final java.util.Set<AwfRegionStorage> observed = java.util.concurrent.ConcurrentHashMap.newKeySet();

        private CloseWatch(final Path root) {
            this.root = root.toAbsolutePath().normalize();
        }

        private void observe(final AwfRegionStorage storage) {
            if (Path.of(storage.name()).startsWith(this.root)) this.observed.add(storage);
        }

        public void requireConfirmed() {
            for (final AwfRegionStorage storage : this.observed) {
                if (!storage.closeConfirmed()) {
                    throw new IllegalStateException("AWF unload did not confirm final storage commit: " + storage.name());
                }
            }
        }

        @Override
        public void close() {
            CLOSE_WATCHES.remove(this);
            this.observed.clear();
        }
    }

    public static CloseWatch watchClose(final Path folder) {
        final CloseWatch watch = new CloseWatch(folder);
        CLOSE_WATCHES.add(watch);
        storagesUnder(folder).forEach(watch::observe);
        return watch;
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
        // Checked before a store is opened: a second open of a store this server already holds
        // would take its Redis lease over from the open storage, then hand it back by closing.
        if (this.storages.containsKey(name)) {
            throw new IOException("AWF storage " + name + " is already open");
        }
        final String template = managedWorld ? templateFor(regionFolder) : null;
        final ChunkSource base = template == null ? null
            : templateBase(template, regionFolder.getFileName().toString());
        final WorldRole role = template == null ? WorldRole.VANILLA : WorldRole.INSTANCE;
        final AwfStore prepared = this.prepared.remove(id);
        final AwfStore store = prepared != null ? prepared
            : backend.open(id, role, this.settings.retainedGenerations());
        try {
            final AwfWorld world = new AwfWorld(name, role, base, store, this.settings.residentChunks());
            final AwfRegionStorage storage = new AwfRegionStorage(name, world, this.settings, this.storageLane,
                this.nanoClock.getAsLong(), () -> this.storages.remove(name), () -> this.shutdownBudget,
                this.shutdownReport::record);
            if (managedWorld && prunes(regionFolder)) storage.pruneEmpty(regionFolder.getFileName().toString());
            if (managedWorld) storage.saveBounds(boundsFor(regionFolder));
            if (this.storages.putIfAbsent(name, storage) != null) {
                throw new IOException("AWF storage " + name + " is already open");
            }
            CLOSE_WATCHES.forEach(watch -> watch.observe(storage));
            return storage;
        } catch (final IOException | RuntimeException failed) {
            // Nothing will ever close a store whose storage was not registered.
            closeQuietly(store, id);
            throw failed;
        }
    }

    private dev.iyanz.sourbycraft.api.world.WorldProperties.Bounds boundsFor(final Path regionFolder) {
        for (final String name : AwfRegionStorage.worldNames(regionFolder)) {
            final var found = this.bounds.get(name);
            if (found != null && this.managed.contains(name)) return found;
        }
        return null;
    }

    private boolean prunes(final Path regionFolder) {
        for (final String name : AwfRegionStorage.worldNames(regionFolder)) {
            if (this.pruning.contains(name) && this.managed.contains(name)) return true;
        }
        return false;
    }

    /** The template of the managed instance world this folder belongs to, or {@code null}. */
    private String templateFor(final Path regionFolder) {
        for (final String name : AwfRegionStorage.worldNames(regionFolder)) {
            final String template = this.templateOf.get(name);
            if (template != null && this.managed.contains(name)) return template;
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
        int chunks = 0;
        int deletions = 0;
        final AwfStore store = backend.open(id, WorldRole.READ_ONLY, this.settings.retainedGenerations());
        try {
            for (final ChunkKey key : store.keys()) {
                final byte[] bytes = store.read(key).orElseThrow(() -> new IOException("index lists " + key + " but it has no bytes"));
                sink.write(key.x(), key.z(), bytes);
                chunks++;
            }
            for (final ChunkKey key : store.deleted()) {
                sink.write(key.x(), key.z(), null);
                deletions++;
            }
        } finally {
            store.close();
        }
        sink.flush();
        return new ExportResult(chunks, deletions, backend.retire(id));
    }

    /**
     * Starts the AWF shutdown deadline ({@code aurora.awf.shutdown-deadline-ms}). Called once at the
     * start of shutdown, before the first AWF wait (SourbyCraft's shutdown hook runs before the region
     * saves, the chunk I/O drain and {@link #closeAll()}); later calls keep the first budget. Does
     * nothing when no storage was ever attached.
     *
     * <p>From then on every flush, close, commit and Redis call measures against this one deadline;
     * when it passes, every world is fenced (queued commits commit nothing), lease renewal stops and
     * every Redis connection in use is aborted.</p>
     */
    public static void beginShutdown() {
        final AwfEngine current = global;
        if (current != null) current.beginShutdownBudget();
    }

    AwfShutdownBudget beginShutdownBudget() {
        synchronized (this) {
            if (this.shutdownBudget != null) return this.shutdownBudget;
            final AwfShutdownBudget budget = new AwfShutdownBudget(this.settings.shutdownDeadlineMillis(), this.nanoClock);
            budget.onExpiry(() -> {
                if (this.storages.isEmpty()) return;                  // nothing of AWF's is still waiting
                for (final AwfRegionStorage storage : this.storages.values()) storage.fenceAtDeadline();
                SourbyLogger.warn("Aurora World Fabric: the shutdown deadline (" + budget.totalMillis() + " ms, "
                    + AwfSettings.SHUTDOWN_DEADLINE_KEY + ") passed; no further commit is attempted and open backend"
                    + " connections are cut. What was not confirmed is listed below");
            });
            for (final AwfBackend backend : this.shutdownBackends()) backend.beginShutdown(budget);
            this.shutdownBudget = budget;
            budget.startTimer();
            if (!this.storages.isEmpty()) {
                SourbyLogger.info("Aurora World Fabric: shutdown began; every AWF commit and release must finish within "
                    + budget.totalMillis() + " ms (" + AwfSettings.SHUTDOWN_DEADLINE_KEY + ")");
            }
            return budget;
        }
    }

    /** Backends that take part in the shutdown deadline: the configured one, when registered. */
    private List<AwfBackend> shutdownBackends() {
        final AwfBackend backend = this.backends.apply(this.settings.backend());
        return backend == null ? List.of() : List.of(backend);
    }

    /** The shutdown budget, or {@code null} before {@link #beginShutdown()}. */
    AwfShutdownBudget shutdownBudget() {
        return this.shutdownBudget;
    }

    AwfShutdownReport shutdownReport() {
        return this.shutdownReport;
    }

    /**
     * Commits and closes every open storage, on the calling thread. Called once at shutdown, after
     * the chunk system has written its last save: Folia's shutdown never closes region storages,
     * so without this the writes since the last periodic commit would be lost on every stop.
     * Storages already closed by their world's region save reported to the same
     * {@link AwfShutdownReport}; this logs the one aggregate result for all of them.
     *
     * @return storages whose data is not confirmed (failed, refused or uncertain final commit),
     *     counting those closed earlier in this shutdown; non-zero fails the shutdown
     */
    public static int closeAll() {
        final AwfEngine current = global;
        int unconfirmed = 0;
        try {
            if (current != null) unconfirmed = current.shutdownAll();
        } finally {
            // The Aurora Redis Fabric owns the STORAGE pool an AWF Redis backend commits through.
            // Bootstrap#close runs before the region saves and this method, so the fabric is only
            // stopped here, after the last commit and lease release, never earlier.
            try {
                dev.iyanz.sourbycraft.awf.redis.fabric.FabricRuntime.stop(5_000L);
            } catch (final RuntimeException failed) {
                SourbyLogger.warn("Aurora Redis Fabric shutdown failed: " + failed.getMessage());
            }
        }
        return unconfirmed;
    }

    /** {@link #closeAll()} for this engine: close the rest, release the backend, report. */
    int shutdownAll() {
        final AwfShutdownBudget budget = beginShutdownBudget();
        closeEvery();
        for (final AwfBackend backend : shutdownBackends()) backend.shutdown();
        final List<AwfShutdownReport.StorageResult> results = this.shutdownReport.results();
        final int unconfirmed = this.shutdownReport.unconfirmed().size();
        budget.finish();
        final var alert = this.shutdownReport.alert(budget.totalMillis(), budget.elapsedMillis());
        if (alert.isPresent()) {
            SourbyLogger.error(alert.get());
        } else if (!results.isEmpty()) {
            SourbyLogger.info(AwfShutdownReport.OK_MARKER + ": Aurora World Fabric confirmed " + results.size()
                + " storage(s) at shutdown in " + budget.elapsedMillis() + " ms (deadline " + budget.totalMillis() + " ms)");
        }
        return unconfirmed;
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
        if (this.shutdownBudget != null) {
            // Each close reports to shutdownReport; none throws for an unconfirmed commit.
            int failed = 0;
            for (final AwfRegionStorage storage : this.storages.values()) {
                try {
                    storage.close();
                } catch (final IOException | RuntimeException failure) {
                    failed++;
                    SourbyLogger.error("Aurora World Fabric could not close " + storage.name() + " at shutdown", failure);
                }
            }
            return failed + this.shutdownReport.unconfirmed().size();
        }
        int failed = 0;
        int closed = 0;
        int unreleased = 0;
        for (final AwfRegionStorage storage : this.storages.values()) {
            try {
                storage.close();
                closed++;
                if (storage.releaseFailed()) unreleased++;
            } catch (final AwfRegionStorage.CloseFailure failure) {
                failed++;
                SourbyLogger.error("Aurora World Fabric could not commit " + storage.name() + " at shutdown: "
                    + failure.getMessage(), failure);
            } catch (final IOException | RuntimeException failure) {
                failed++;
                SourbyLogger.error("Aurora World Fabric could not commit " + storage.name()
                    + " at shutdown; its chunks written since the last commit are lost", failure);
            }
        }
        if (closed + failed > 0) {
            SourbyLogger.info("Aurora World Fabric: committed and closed " + closed + " storage(s) at shutdown"
                + (unreleased > 0 ? " (" + unreleased + " not released on the backend)" : "")
                + (failed > 0 ? ", " + failed + " failed to commit" : ""));
        }
        return failed;
    }

    /**
     * Starts due commits. Called once a second by {@link AwfMaintenanceDriver} on its own thread.
     * Does no I/O and does not wait for a commit, but is not lock-free: handing a commit to the
     * storage lane takes the lane queue's lock, and a commit rejected at once logs its warning on
     * the calling thread. Safe to call concurrently (each storage claims its commit with a CAS),
     * but the driver never does.
     */
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

    /** One storage's answer to {@link #compact}: {@code fullAfter} is what a pre-AWF-7 build needs. */
    public record CompactionReport(String storage, boolean fullBefore, long generation, boolean fullAfter, String error) {}

    /**
     * Compacts every open storage of a loaded world, found by its dimension folder
     * ({@link AwfWorldFolders#loaded}; {@code "all"}: every open storage), clean ones
     * included, on the storage lane, and reports per storage whether its current generation is
     * now self-contained — the check to run before downgrading to a build without the chain
     * parser. A world that is not loaded has no open storage and is not touched.
     */
    public static java.util.concurrent.CompletableFuture<List<CompactionReport>> compact(final String world) {
        final AwfEngine current = global;
        if (current == null) return java.util.concurrent.CompletableFuture.completedFuture(List.of());
        return current.compactStorages(world, AwfWorldFolders::loaded);
    }

    /**
     * As {@link #compact}, with the world-name lookup injected: {@code folders} maps a loaded
     * world's name to its dimension folder ({@code null} when it is not loaded). A name is never a
     * path: on 26.2 {@code <server>/<name>} is either nothing (a managed world) or the level folder
     * holding every dimension ({@code world}), which compacted the stores of all of them.
     */
    java.util.concurrent.CompletableFuture<List<CompactionReport>> compactStorages(final String world,
            final java.util.function.Function<String, Path> folders) {
        if ("all".equalsIgnoreCase(world)) return compactStorages((Path) null);
        final Path folder = folders.apply(world);
        if (folder == null) return java.util.concurrent.CompletableFuture.completedFuture(List.of());
        return compactStorages(folder);
    }

    /** Compacts every open storage under {@code folder}; {@code null} = every open storage. */
    java.util.concurrent.CompletableFuture<List<CompactionReport>> compactStorages(final Path folder) {
        final List<AwfRegionStorage> targets = folder == null ? List.copyOf(this.storages.values())
            : storagesUnder(this, folder);
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            final List<CompactionReport> reports = new java.util.ArrayList<>();
            for (final AwfRegionStorage storage : targets) {
                final boolean before = storage.fullIndex();
                String error = null;
                long generation = -1L;
                try {
                    final AwfStore.CommitResult result = storage.compact();
                    generation = result == null ? storage.storeGeneration() : result.generation();
                } catch (final IOException | RuntimeException failed) {
                    error = failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage();
                }
                reports.add(new CompactionReport(storage.name(), before, generation, storage.fullIndex(), error));
            }
            return reports;
        }, this.storageLane);
    }

    private static List<AwfRegionStorage> storagesUnder(final AwfEngine engine, final Path folder) {
        final Path root = folder.toAbsolutePath().normalize();
        final List<AwfRegionStorage> found = new java.util.ArrayList<>();
        for (final AwfRegionStorage storage : engine.storages.values()) {
            if (Path.of(storage.name()).startsWith(root)) found.add(storage);
        }
        return found;
    }

    /** Every open storage of the running engine; empty when none was ever attached. */
    public static Collection<AwfRegionStorage> openStorages() {
        final AwfEngine current = global;
        return current == null ? List.of() : current.storages();
    }

    public Collection<AwfRegionStorage> storages() {
        return List.copyOf(this.storages.values());
    }
}
