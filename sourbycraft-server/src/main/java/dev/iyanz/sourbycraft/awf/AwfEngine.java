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
    private final Map<String, AwfRegionStorage> storages = new ConcurrentHashMap<>();

    AwfEngine(final AwfSettings settings, final Executor storageLane, final LongSupplier nanoClock) {
        this.settings = settings;
        this.storageLane = storageLane;
        this.nanoClock = nanoClock;
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
     * Called by {@code RegionFileStorage}'s constructor.
     *
     * @return the AWF storage for this region folder, or {@code null} to use region files as usual
     * @throws IOException when the folder belongs to AWF but its store cannot be opened; the
     *     world must not fall back to region files, which the store shadows
     */
    public static AwfRegionStorage attach(final Path regionFolder) throws IOException {
        // Most servers list nothing and have no store: answer without creating anything.
        final AwfEngine current = global;
        if (current == null && !AwfRegionStorage.hasStore(regionFolder)) {
            final AwfSettings settings = global().settings;
            if (!AwfRegionStorage.listed(regionFolder, settings.worlds())) return null;
        }
        return global().open(regionFolder);
    }

    AwfRegionStorage open(final Path regionFolder) throws IOException {
        final boolean listed = AwfRegionStorage.listed(regionFolder, this.settings.worlds());
        final boolean existing = AwfRegionStorage.hasStore(regionFolder);
        if (!listed && !existing) return null;
        if (!listed) {
            SourbyLogger.warn("AWF store " + AwfRegionStorage.storeFor(regionFolder) + " exists but its world is not in "
                + AwfSettings.WORLDS_KEY + "; opening it anyway, because the region files beneath it are older"
                + " than the chunks it holds");
        }
        final String name = regionFolder.toAbsolutePath().normalize().toString();
        final AwfWorldStore store = AwfWorldStore.open(AwfRegionStorage.storeFor(regionFolder), WorldRole.VANILLA,
            this.settings.retainedGenerations());
        final AwfWorld world = new AwfWorld(name, WorldRole.VANILLA, null, store, this.settings.residentChunks());
        final AwfRegionStorage storage = new AwfRegionStorage(name, world, this.settings, this.storageLane,
            this.nanoClock.getAsLong(), () -> this.storages.remove(name));
        if (this.storages.putIfAbsent(name, storage) != null) {
            throw new IOException("AWF storage " + name + " is already open");
        }
        return storage;
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
