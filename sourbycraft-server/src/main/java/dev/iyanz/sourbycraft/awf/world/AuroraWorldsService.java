package dev.iyanz.sourbycraft.awf.world;

import dev.iyanz.sourbycraft.api.world.AuroraWorlds;
import dev.iyanz.sourbycraft.api.world.UnloadResult;
import dev.iyanz.sourbycraft.api.world.WorldProperties;
import dev.iyanz.sourbycraft.awf.AwfBackend;
import dev.iyanz.sourbycraft.awf.AwfEngine;
import dev.iyanz.sourbycraft.awf.AwfRegionStorage;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import dev.iyanz.sourbycraft.api.world.WorldUnloadResult;
import io.papermc.paper.threadedregions.RegionizedServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;

/**
 * Server side of {@link AuroraWorlds}.
 *
 * <p>World creation and unloading run on the global region thread, as the engine requires. A
 * save fans out one save ticket per region of the world, waits for every region to have written
 * its chunks and for the world's chunk I/O to drain (bounded by {@code aurora.awf.shutdown-deadline-ms}),
 * then commits the world's AWF stores off the region threads, so a completed save is durable and a
 * save that cannot confirm its commit fails. An unload with saving saves that way first and is
 * refused, the world staying loaded, when the save fails. Deletion removes the world's dimension
 * folder, which holds its AWF stores.</p>
 *
 * <p>Templates are kept by {@link AuroraTemplates}, on the FILE backend only.</p>
 */
public final class AuroraWorldsService implements AuroraWorlds, org.bukkit.event.Listener {

    private final AuroraWorldRegistry registry;
    private final AuroraTemplates templates;
    /** Worlds whose pending unload must not save; see {@link #discardOnUnload}. */
    private final Set<String> unloadWithoutSaving = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** The chunk I/O drain each world's save is running or waiting behind, by world; see {@link #boundedDrain}. */
    private final java.util.Map<String, CompletableFuture<Void>> drains = new java.util.concurrent.ConcurrentHashMap<>();
    private final WorldOperationGate operations = new WorldOperationGate(AuroraWorldsService::onGlobalTick);

    /** A world's dimension folder; the server's storage source in production. */
    private final java.util.function.Function<String, Path> folders;

    public AuroraWorldsService(final AuroraWorldRegistry registry, final AuroraTemplates templates) {
        this(registry, templates, AuroraWorldsService::folder);
    }

    /** As above with the folder lookup injected, for tests that run without a server. */
    AuroraWorldsService(final AuroraWorldRegistry registry, final AuroraTemplates templates,
                        final java.util.function.Function<String, Path> folders) {
        this.registry = registry;
        this.templates = templates;
        this.folders = folders;
    }

    /**
     * Logs world operations that have held their world (or template) for over
     * {@link WorldOperationGate#OVERDUE_NANOS}, once each. Called once a second.
     */
    public void watchOperations() {
        for (final String stuck : this.operations.overdue(WorldOperationGate.OVERDUE_NANOS)) {
            SourbyLogger.warn("Aurora World Fabric: operation " + stuck + " has not finished; its world stays"
                + " locked against other operations until it does (or until restart)");
        }
    }

    private List<WorldOperationGate.Resource> worldResources(final String name) {
        final java.util.ArrayList<WorldOperationGate.Resource> resources = new java.util.ArrayList<>();
        resources.add(new WorldOperationGate.Resource("world:" + name, false));
        this.registry.get(name).map(AuroraWorldRegistry.Entry::template).ifPresent(template ->
            resources.add(new WorldOperationGate.Resource("template:" + template, true)));
        return resources;
    }

    private static WorldOperationGate.Resource output(final Path file) {
        return new WorldOperationGate.Resource("file:" + file.toAbsolutePath().normalize(), false);
    }

    @Override
    public CompletableFuture<World> create(final WorldCreator creator, final String generator, final boolean autoload) {
        return this.operations.run("create", worldResources(creator.name()), () -> createInternal(creator, generator, autoload));
    }

    @Override
    public CompletableFuture<World> importSlime(final Path file, final WorldCreator creator, final String generator,
                                                final boolean autoload) {
        return this.operations.run("importSlime", worldResources(creator.name()),
            () -> importSlimeInternal(file, creator, generator, autoload));
    }

    @Override
    public CompletableFuture<World> createFromTemplate(final String template, final String name, final boolean autoload) {
        final var resources = new java.util.ArrayList<>(worldResources(name));
        resources.add(new WorldOperationGate.Resource("template:" + template, true));
        return this.operations.run("createFromTemplate", resources, () -> createFromTemplateInternal(template, name, autoload));
    }

    @Override
    public CompletableFuture<World> load(final String name) {
        return this.operations.run("load", worldResources(name), () -> loadInternal(name));
    }

    @Override
    public CompletableFuture<World> importWorld(final Path file, final String name, final boolean autoload) {
        return this.operations.run("importWorld", worldResources(name), () -> importWorldInternal(file, name, autoload));
    }

    @Override
    public CompletableFuture<Void> exportWorld(final String world, final Path file) {
        final var resources = new java.util.ArrayList<>(worldResources(world));
        resources.add(output(file));
        return this.operations.run("exportWorld", resources, () -> exportWorldInternal(world, file));
    }

    @Override
    public CompletableFuture<Void> convertSlime(final Path slime, final Path awf, final World.Environment environment) {
        return this.operations.run("convertSlime", List.of(output(awf)), () -> convertSlimeInternal(slime, awf, environment));
    }

    @Override
    public CompletableFuture<Void> saveTemplate(final String world, final String template) {
        final var resources = new java.util.ArrayList<>(worldResources(world));
        resources.add(new WorldOperationGate.Resource("template:" + template, false));
        return this.operations.run("saveTemplate", resources, () -> saveTemplateInternal(world, template));
    }

    @Override
    public CompletableFuture<Void> deleteTemplate(final String template) {
        return this.operations.run("deleteTemplate", List.of(new WorldOperationGate.Resource("template:" + template, false)),
            () -> deleteTemplateInternal(template));
    }

    @Override
    public CompletableFuture<Void> save(final String name) {
        return this.operations.run("save", worldResources(name), () -> saveInternal(name));
    }

    @Override
    public CompletableFuture<UnloadResult> unload(final String name, final boolean save) {
        return this.operations.run("unload", worldResources(name), () -> unloadInternal(name, save));
    }

    @Override
    public CompletableFuture<Void> delete(final String name) {
        return this.operations.run("delete", worldResources(name), () -> deleteInternal(name));
    }

    @Override
    public void setAutoload(final String name, final boolean autoload) {
        try (var lease = this.operations.acquire("setAutoload", worldResources(name))) {
            setAutoloadInternal(name, autoload);
        }
    }

    @Override
    public Set<String> list() {
        final Set<String> names = new LinkedHashSet<>();
        for (final AuroraWorldRegistry.Entry entry : this.registry.all()) names.add(entry.name());
        return names;
    }

    @Override
    public boolean exists(final String name) {
        return this.registry.contains(name);
    }

    @Override
    public boolean isLoaded(final String name) {
        return Bukkit.getWorld(name) != null;
    }

    @Override
    public CompletableFuture<World> cloneWorld(final String source, final String target, final boolean autoload) {
        final AuroraWorldRegistry.Entry entry = this.registry.get(source).orElse(null);
        if (entry == null) return CompletableFuture.failedFuture(new IllegalArgumentException("no AWF world named " + source));
        final java.util.ArrayList<WorldOperationGate.Resource> resources = new java.util.ArrayList<>(worldResources(target));
        // The source is only read: shared, so several clones of one world can run at once.
        resources.add(new WorldOperationGate.Resource("world:" + source, true));
        return this.operations.run("clone", resources, () -> {
            if (Bukkit.getWorld(source) != null) {
                return CompletableFuture.failedFuture(new IllegalStateException(source + " is loaded; unload it first,"
                    + " so the copy is one consistent moment"));
            }
            final AuroraWorldRegistry.Entry copy = entry.named(target, entry.template(), autoload);
            return start(copy, () -> {
                final AwfBackend backend = AwfEngine.backend();
                final java.util.Map<String, Integer> copied = new java.util.LinkedHashMap<>();
                for (final String storage : AwfEngine.STORAGE_FOLDERS) {
                    final dev.iyanz.sourbycraft.awf.AwfStore from = AuroraWorldIo.openRead(backend, folder(source), storage);
                    if (from == null) continue;
                    try {
                        // Own chunks only: an instance's template stays its base, shared, not copied.
                        final dev.iyanz.sourbycraft.awf.AwfStore to = backend.open(
                            AwfEngine.storeId(folder(target), storage), dev.iyanz.sourbycraft.awf.WorldRole.VANILLA, 1);
                        try {
                            final AuroraWorldIo.BatchSink sink = AuroraWorldIo.into(to);
                            AuroraWorldIo.flatten(from, null, sink);
                            copied.put(storage, sink.finish());
                            if (!from.deleted().isEmpty()) {
                                // Deletions shadow the template in the source; they must in the copy too.
                                to.commit(java.util.Map.of(), java.util.Set.of(), from.deleted(),
                                    dev.iyanz.sourbycraft.awf.PersistenceMode.INCREMENTAL);
                            }
                        } finally {
                            to.close();
                        }
                    } finally {
                        from.close();
                    }
                }
                SourbyLogger.info("Aurora World Fabric: cloned " + source + " to " + target + " " + copied
                    + (entry.template() != null ? " over template " + entry.template() : ""));
            });
        });
    }

    @Override
    public WorldProperties properties(final String name) {
        return this.registry.get(name).map(AuroraWorldRegistry.Entry::propertiesOrNone)
            .orElseThrow(() -> new IllegalArgumentException("no AWF world named " + name));
    }

    @Override
    public CompletableFuture<Void> setProperties(final String name, final WorldProperties properties) {
        java.util.Objects.requireNonNull(properties, "properties");
        return this.operations.run("setProperties", worldResources(name), () -> {
            final AuroraWorldRegistry.Entry entry = this.registry.get(name).orElse(null);
            if (entry == null) return CompletableFuture.failedFuture(new IllegalArgumentException("no AWF world named " + name));
            final AuroraWorldRegistry.Entry updated = entry.withProperties(properties);
            // The registry's forced write blocks: off the calling thread, which may be a tick thread.
            return dev.iyanz.sourbycraft.util.VirtualExecutor.<Void>supply(() -> {
                this.registry.put(updated);
                return null;
            }).thenCompose(written -> {
                AwfEngine.configure(name, updated.prunesEmptyChunks(), properties.saveBounds());
                if (Bukkit.getWorld(name) == null) return CompletableFuture.completedFuture(null);
                return onGlobal(() -> {
                    final World world = Bukkit.getWorld(name);
                    if (world != null) applyProperties(world, properties);
                    return null;
                });
            });
        });
    }

    /** Applies the properties the world itself holds. Runs on the global region thread. */
    static void applyProperties(final World world, final WorldProperties properties) {
        if (properties.spawn() != null) {
            final WorldProperties.Spawn spawn = properties.spawn();
            world.setSpawnLocation(new org.bukkit.Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), 0f));
        }
        if (properties.difficulty() != null) world.setDifficulty(properties.difficulty());
        if (properties.pvp() != null) world.setPVP(properties.pvp());
        if (properties.allowMonsters() != null || properties.allowAnimals() != null) {
            world.setSpawnFlags(properties.allowMonsters() != null ? properties.allowMonsters() : world.getAllowMonsters(),
                properties.allowAnimals() != null ? properties.allowAnimals() : world.getAllowAnimals());
        }
    }

    @Override
    public boolean autoload(final String name) {
        return this.registry.get(name).map(AuroraWorldRegistry.Entry::autoload).orElse(false);
    }

    private void setAutoloadInternal(final String name, final boolean autoload) {
        final AuroraWorldRegistry.Entry entry = this.registry.get(name)
            .orElseThrow(() -> new IllegalArgumentException("no AWF world named " + name));
        try {
            this.registry.put(entry.withAutoload(autoload));
        } catch (final IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        }
    }

    private CompletableFuture<World> createInternal(final WorldCreator creator, final String generator, final boolean autoload) {
        final AuroraWorldRegistry.Entry entry;
        try {
            entry = describe(creator, generator, autoload);
        } catch (final IllegalArgumentException refused) {
            return CompletableFuture.failedFuture(refused);
        }
        return start(entry, null);
    }

    private CompletableFuture<World> importSlimeInternal(final Path file, final WorldCreator creator, final String generator,
                                                final boolean autoload) {
        final AuroraWorldRegistry.Entry entry;
        try {
            entry = describe(creator, generator, autoload);
        } catch (final IllegalArgumentException refused) {
            return CompletableFuture.failedFuture(refused);
        }
        if (!Files.isRegularFile(file)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("no such file: " + file));
        }
        // The file's own properties become the world's (only its header and extra data are read here).
        final SlimeImporter.WorldData fileWorld;
        try {
            fileWorld = SlimeImporter.worldData(file);
        } catch (final IOException failed) {
            return CompletableFuture.failedFuture(failed);
        }
        final AuroraWorldRegistry.Entry withFile = fileWorld.properties().equals(WorldProperties.NONE) ? entry
            : entry.withProperties(fileWorld.properties());
        final int minSection = creator.environment() == World.Environment.NORMAL ? -4 : 0;
        final CompletableFuture<World> created = start(withFile, () -> {
            final SlimeImporter.Result result = SlimeImporter.importInto(SlimeImporter.readFile(file), folder(entry.name()), minSection);
            SourbyLogger.info("Aurora World Fabric: imported " + file.getFileName() + " as " + entry.name() + " ("
                + result.chunks() + " chunks, " + result.entityChunks() + " with entities, " + result.poiChunks()
                + " with POI, data version " + result.dataVersion() + ")");
            if (!fileWorld.dropped().isEmpty()) {
                SourbyLogger.warn("Aurora World Fabric: importing " + file.getFileName() + " did not keep "
                    + fileWorld.dropped() + " (not representable in AWF yet, or a value that does not fit, such as a"
                    + " spawn outside the world)");
            }
        });
        if (fileWorld.pluginData() == null) return created;
        // The world's BukkitValues go into its persistent data container. start() completes on the
        // global tick, where the world's container may be written; WorldLoadEvent has already fired, so
        // a listener on it does not see the data yet. The container reaches paper/persistent_data_container.dat
        // at clean shutdown; world PDC has no consistent concurrent snapshot for periodic autosave.
        return created.thenApply(world -> {
            try {
                SlimeImporter.restorePluginData(world.getPersistentDataContainer(), fileWorld.pluginData());
            } catch (final IOException failed) {
                SourbyLogger.warn("Aurora World Fabric: importing " + file.getFileName() + " did not restore the world's"
                    + " plugin data (BukkitValues): " + failed.getMessage());
            }
            return world;
        });
    }

    /** A new world's entry, from a creator without a generator object. */
    private static AuroraWorldRegistry.Entry describe(final WorldCreator creator, final String generator,
                                                      final boolean autoload) {
        if (creator.generator() != null) {
            throw new IllegalArgumentException("name the generator instead of setting one on the creator"
                + " (void, or Plugin[:id]), so the world can be loaded again with it");
        }
        if (!creator.key().equals(NamespacedKey.minecraft(creator.name()))) {
            throw new IllegalArgumentException("AWF worlds use the key minecraft:" + creator.name() + ", not " + creator.key());
        }
        final String environment = creator.environment().name().toLowerCase(Locale.ROOT);
        final String type = creator.type() == WorldType.NORMAL ? null : creator.type().name().toLowerCase(Locale.ROOT);
        // "void:<biome>" fixes the void world's single biome from the first chunk on, so the world's
        // empty chunks are regenerable and prunable; set later, the chunks already made keep the
        // dimension's biomes.
        String named = generator;
        String biome = null;
        if (generator != null && generator.regionMatches(true, 0, VoidGenerator.NAME + ":", 0, VoidGenerator.NAME.length() + 1)) {
            named = VoidGenerator.NAME;
            biome = generator.substring(VoidGenerator.NAME.length() + 1).toLowerCase(Locale.ROOT);
            if (biome.isBlank() || NamespacedKey.fromString(biome) == null) {
                throw new IllegalArgumentException("not a biome key: " + generator);
            }
        }
        final AuroraWorldRegistry.Entry entry = new AuroraWorldRegistry.Entry(creator.name(), environment, creator.seed(),
            named, type, null, autoload);
        return biome == null ? entry : entry.withProperties(WorldProperties.NONE.withDefaultBiome(biome));
    }

    /** A refusal for a template name that is not a valid name (and could leave the templates folder). */
    private static Optional<CompletableFuture<Void>> invalidTemplate(final String template) {
        return AuroraWorldRegistry.invalidTemplateName(template)
            .map(reason -> CompletableFuture.failedFuture(new IllegalArgumentException("not a template name: " + reason)));
    }

    private CompletableFuture<World> createFromTemplateInternal(final String template, final String name, final boolean autoload) {
        if (AuroraWorldRegistry.invalidTemplateName(template).isPresent()) {
            return invalidTemplate(template).orElseThrow().thenApply(ignored -> null);
        }
        final AuroraWorldRegistry.Entry description;
        try {
            description = this.templates.read(template).orElse(null);
        } catch (final IOException failed) {
            return CompletableFuture.failedFuture(failed);
        }
        if (description == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("no template named " + template));
        }
        return start(description.named(name, template, autoload), null);
    }

    @Override
    public Optional<String> template(final String name) {
        return this.registry.get(name).map(AuroraWorldRegistry.Entry::template);
    }

    /** Blocking work run off the region threads before a new world's stores open. */
    private interface IoStep {
        void run() throws IOException;
    }

    /**
     * Registers a new world and creates it. The entry, and anything {@code before} wrote into the
     * world's folder, are removed again if creation fails.
     *
     * @param before run off the region threads before the stores open, or {@code null}
     */
    private CompletableFuture<World> start(final AuroraWorldRegistry.Entry entry, final IoStep before) {
        final String name = entry.name();
        final var invalid = AuroraWorldRegistry.invalidName(name);
        if (invalid.isPresent()) return CompletableFuture.failedFuture(new IllegalArgumentException(invalid.get()));
        if (this.registry.contains(name) || Bukkit.getWorld(name) != null || Files.exists(folder(name))) {
            return CompletableFuture.failedFuture(new IllegalStateException("a world named " + name + " already exists"));
        }
        if (entry.template() != null && !this.templates.exists(entry.template())) {
            return CompletableFuture.failedFuture(new IllegalStateException("AWF world " + name
                + " is an instance of template '" + entry.template() + "', which is missing"));
        }
        final WorldCreator creator;
        try {
            creator = creator(entry);
        } catch (final RuntimeException failed) {
            return CompletableFuture.failedFuture(failed);
        }
        // Registered before the world exists, so its region storages attach to AWF as they open. Off
        // the calling thread, which may be the global tick or a region thread: the backend lookup and
        // the registry's forced write block.
        final CompletableFuture<Void> registered = dev.iyanz.sourbycraft.util.VirtualExecutor.supply(() -> {
            if (AwfEngine.storesExist(folder(name))) {
                // A network backend can hold a world this server has no folder for.
                throw new IllegalStateException("AWF stores for " + name + " already exist on " + storage()
                    + "; delete them or choose another name");
            }
            this.registry.put(entry);
            AwfEngine.manage(name, entry.template(), entry.prunesEmptyChunks());
            AwfEngine.configure(name, entry.prunesEmptyChunks(), entry.propertiesOrNone().saveBounds());
            return null;
        });
        // Only a failure after the registration is undone: stores or a folder found before it are not
        // this start's to delete.
        return registered.thenCompose(registeredNow -> {
            final CompletableFuture<Void> ready = before == null ? prepared(entry)
                : dev.iyanz.sourbycraft.util.VirtualExecutor.<Void>supply(() -> {
                    before.run();
                    return null;
                }).thenCompose(ignored -> prepared(entry));
            return createAfter(ready, () -> createStaged(creator, entry, "create"))
                .handle((world, failed) -> failed == null ? CompletableFuture.completedFuture(world) : forgetFailedStart(name, failed))
                .thenCompose(java.util.function.Function.identity());
        });
    }

    /**
     * Undoes a {@link #start} that failed: the entry, the world's AWF stores and folder, and the
     * engine's management of the name. The stores and folder are removed only once the world is
     * gone (a failed creation unloads its half-built world first), and never existed before
     * {@code start}, which refuses an existing folder or stores; storages still open under the
     * folder are discarded and closed first, and when one cannot be closed nothing is deleted and
     * the entry stays for {@code delete} to retry. The I/O runs off the region threads; the
     * operation stays reserved until it is done, so the name cannot be reused midway.
     *
     * <p>Not when the server is stopping and the world still exists: the shutdown saves it, spawn
     * still unset, and the entry is kept so a later load finishes it (its spawn search runs then).</p>
     */
    private CompletableFuture<World> forgetFailedStart(final String name, final Throwable failed) {
        if (serverStopping() && Bukkit.getWorld(name) != null) {
            SourbyLogger.warn("Aurora World Fabric: the server stopped while creating " + name + "; it stays registered"
                + " and finishes at its next load");
            return CompletableFuture.failedFuture(failed);
        }
        return dev.iyanz.sourbycraft.util.VirtualExecutor.<Void>supply(() -> {
            AwfEngine.unprepare(folder(name));
            if (Bukkit.getWorld(name) == null) {
                try {
                    closeOpenStorages(folder(name));
                } catch (final IOException notClosed) {
                    // Nothing was deleted under a storage that could not be closed. The entry stays, so
                    // AuroraWorlds.delete can retry the cleanup; the caller sees the original failure.
                    failed.addSuppressed(notClosed);
                    SourbyLogger.warn("Aurora World Fabric: " + name + " stays registered after its failed creation: a"
                        + " storage it left open could not be closed, so its stores were not deleted (delete the world"
                        + " to retry): " + notClosed.getMessage());
                    return null;
                }
                try {
                    AwfEngine.deleteStores(folder(name));
                    deleteRecursively(folder(name));
                } catch (final IOException cleanup) {
                    // The folder or stores block the name until removed; the caller sees the original failure.
                    failed.addSuppressed(cleanup);
                }
            }
            try {
                this.registry.remove(name);
            } catch (final IOException ignored) {
                // The original failure is what the caller needs; the entry is retried on delete.
            }
            AwfEngine.release(name);
            return null;
        }).handle((ignored, cleanupFailed) -> {
            if (cleanupFailed != null) failed.addSuppressed(cleanupFailed);
            throw failed instanceof CompletionException completion ? completion : new CompletionException(failed);
        });
    }

    /** Whether the server has begun stopping, so the global tick may never run again. */
    private static boolean serverStopping() {
        final MinecraftServer server = MinecraftServer.getServer();
        return server == null || server.hasStartedShutdownThread.get() || !server.isRunning();
    }

    /**
     * Creates or loads the world in stages ({@code CraftServer#sourbycraft$createWorldStaged}): on
     * the global tick, the global tick never waiting for the initial spawn's terrain. Completes
     * on the global tick with the world, its properties applied.
     */
    private static CompletableFuture<World> createStaged(final WorldCreator creator, final AuroraWorldRegistry.Entry entry,
                                                         final String verb) {
        return ((org.bukkit.craftbukkit.CraftServer) Bukkit.getServer()).sourbycraft$createWorldStaged(creator).thenApply(world -> {
            if (world == null) throw new IllegalStateException("the server refused to " + verb + " " + creator.name());
            applyProperties(world, entry.propertiesOrNone());
            return world;
        });
    }

    private CompletableFuture<World> loadInternal(final String name) {
        final AuroraWorldRegistry.Entry entry = this.registry.get(name).orElse(null);
        if (entry == null) return CompletableFuture.failedFuture(new IllegalArgumentException("no AWF world named " + name));
        final World loaded = Bukkit.getWorld(name);
        if (loaded != null) return CompletableFuture.completedFuture(loaded);
        if (entry.template() != null && !this.templates.exists(entry.template())) {
            // Without its template an instance would open empty stores and later commit a void/terrain
            // world over a real one. Refuse instead of silently replacing its content.
            return CompletableFuture.failedFuture(new IllegalStateException("AWF world " + name
                + " is an instance of template '" + entry.template() + "', which is missing; refusing to load"
                + " it as a fresh world (restore the template or delete the world)"));
        }
        final WorldCreator creator;
        try {
            creator = creator(entry);
        } catch (final RuntimeException failed) {
            return CompletableFuture.failedFuture(failed);
        }
        AwfEngine.manage(name, entry.template(), entry.prunesEmptyChunks());
            AwfEngine.configure(name, entry.prunesEmptyChunks(), entry.propertiesOrNone().saveBounds());
        return createAfter(preparedForLoad(entry), () -> {
            final World existing = Bukkit.getWorld(name);
            if (existing != null) return CompletableFuture.completedFuture(existing);
            return createStaged(creator, entry, "load");
        }).whenComplete((world, failed) -> {
            if (failed != null) AwfEngine.unprepare(folder(name));
        });
    }

    /** The creator an entry describes, with its named generator resolved. */
    static WorldCreator creator(final AuroraWorldRegistry.Entry entry) {
        final WorldCreator creator = new WorldCreator(entry.name())
            .environment(World.Environment.valueOf(entry.environment().toUpperCase(Locale.ROOT)));
        if (entry.seed() != null) creator.seed(entry.seed());
        if (entry.worldType() != null) creator.type(WorldType.valueOf(entry.worldType().toUpperCase(Locale.ROOT)));
        if (VoidGenerator.NAME.equals(entry.generator())) {
            creator.generator(new VoidGenerator(entry.propertiesOrNone().defaultBiome()));
        } else if (entry.generator() != null) {
            creator.generator(entry.generator());
            if (creator.generator() == null) {
                throw new IllegalStateException("generator " + entry.generator() + " is not available;"
                    + " is its plugin installed and enabled?");
            }
        }
        return creator;
    }

    /**
     * Opens the world's AWF stores, and its template's, off the region threads. The world itself
     * is created on the global tick, which is a region thread, where the stores refuse blocking I/O.
     */
    private static CompletableFuture<Void> prepared(final AuroraWorldRegistry.Entry entry) {
        return dev.iyanz.sourbycraft.util.VirtualExecutor.supply(() -> {
            AwfEngine.prepare(folder(entry.name()), entry.template());
            return null;
        });
    }

    /**
     * {@link #prepared} for loading a registered world, refused while a store recorded for it
     * ({@link AuroraWorldRegistry.Entry#knownStores}) is missing: preparing would open a new, empty
     * store in its place, the world would load fresh (void or regenerated), and its next commits
     * would replace what it was. The stores that exist are recorded before they are opened.
     */
    private CompletableFuture<Void> preparedForLoad(final AuroraWorldRegistry.Entry entry) {
        return dev.iyanz.sourbycraft.util.VirtualExecutor.supply(() -> {
            final Path folder = folder(entry.name());
            if (entry.knownStores() != null) {
                final AwfBackend backend = AwfEngine.backend();
                for (final String storage : entry.knownStores()) {
                    if (storage == null || !AwfEngine.STORAGE_FOLDERS.contains(storage)) continue;
                    final String id = AwfEngine.storeId(folder, storage);
                    if (!backend.exists(id)) {
                        throw new IllegalStateException("AWF world " + entry.name() + " had a store " + id + " on "
                            + backend.name() + " when it was last loaded or saved (recorded in " + AwfEngine.MANAGED_FILE
                            + "), and that store is missing; refusing to load the world fresh in its place (restore the"
                            + " store, or delete the world)");
                    }
                }
            }
            recordKnownStores(entry.name());
            AwfEngine.prepare(folder, entry.template());
            return null;
        });
    }

    private CompletableFuture<World> importWorldInternal(final Path file, final String name, final boolean autoload) {
        final AuroraWorldRegistry.Entry entry;
        final net.minecraft.nbt.CompoundTag pluginData;
        try (dev.iyanz.sourbycraft.awf.AwfWorldFile awf = dev.iyanz.sourbycraft.awf.AwfWorldFile.open(file)) {
            entry = AuroraWorldFiles.describe(awf, name, autoload);
            // Decoded before anything is created, so a malformed value fails the import like malformed properties.
            pluginData = AuroraWorldFiles.pluginData(awf.metadata());
        } catch (final IOException failed) {
            return CompletableFuture.failedFuture(failed);
        }
        final CompletableFuture<World> created = start(entry, () -> {
            final long started = System.nanoTime();
            try (dev.iyanz.sourbycraft.awf.AwfWorldFile awf = dev.iyanz.sourbycraft.awf.AwfWorldFile.open(file)) {
                final var written = AuroraWorldFiles.importInto(awf, folder(name));
                SourbyLogger.info("Aurora World Fabric: imported " + file.getFileName() + " as " + name + " " + written
                    + " in " + (System.nanoTime() - started) / 1_000_000 + " ms");
            }
        });
        if (pluginData == null) return created;
        // As for a Slime import: start() completes on the global tick, where the world's container may be
        // written; WorldLoadEvent has already fired, so a listener on it does not see the data yet.
        return created.thenApply(world -> {
            try {
                SlimeImporter.restorePluginData(world.getPersistentDataContainer(), pluginData);
            } catch (final IOException failed) {
                SourbyLogger.warn("Aurora World Fabric: importing " + file.getFileName() + " did not restore the world's"
                    + " plugin data (plugin-data): " + failed.getMessage());
            }
            return world;
        });
    }

    private CompletableFuture<Void> exportWorldInternal(final String world, final Path file) {
        final AuroraWorldRegistry.Entry entry = this.registry.get(world).orElse(null);
        if (entry == null) return CompletableFuture.failedFuture(new IllegalArgumentException("no AWF world named " + world));
        if (Bukkit.getWorld(world) != null) {
            return CompletableFuture.failedFuture(new IllegalStateException(world + " is loaded; unload it first,"
                + " so the file is one consistent moment"));
        }
        if (Files.exists(file)) return CompletableFuture.failedFuture(new IllegalStateException(file + " already exists"));
        return CompletableFuture.runAsync(() -> {
            try {
                final long started = System.nanoTime();
                final AuroraWorldFiles.Written written = AuroraWorldFiles.export(AwfEngine.backend(), folder(world), entry,
                    AwfEngine.TEMPLATES, file);
                SourbyLogger.info("Aurora World Fabric: exported " + world + " to " + file + " " + written.chunks()
                    + " (" + written.codec() + ", " + Files.size(file) + " bytes, "
                    + (System.nanoTime() - started) / 1_000_000 + " ms)");
            } catch (final IOException failed) {
                throw new CompletionException(failed);
            }
        }, dev.iyanz.sourbycraft.util.VirtualExecutor.executor());
    }

    private CompletableFuture<Void> convertSlimeInternal(final Path slime, final Path awf, final World.Environment environment) {
        if (!Files.isRegularFile(slime)) return CompletableFuture.failedFuture(new IllegalArgumentException("no such file: " + slime));
        if (Files.exists(awf)) return CompletableFuture.failedFuture(new IllegalStateException(awf + " already exists"));
        final String env = environment.name().toLowerCase(Locale.ROOT);
        return CompletableFuture.runAsync(() -> {
            try {
                final AuroraWorldFiles.Written written = AuroraWorldFiles.convertSlime(SlimeImporter.readFile(slime), env,
                    slime.getFileName().toString(), awf);
                SourbyLogger.info("Aurora World Fabric: converted " + slime + " to " + awf + " " + written.chunks()
                    + " (" + written.codec() + ", " + Files.size(slime) + " -> " + Files.size(awf) + " bytes)");
            } catch (final IOException failed) {
                throw new CompletionException(failed);
            }
        }, dev.iyanz.sourbycraft.util.VirtualExecutor.executor());
    }

    @Override
    public String storage() {
        try {
            return AwfEngine.backend().describe();
        } catch (final IOException unregistered) {
            return unregistered.getMessage();
        }
    }

    @Override
    public Set<String> templates() {
        try {
            return this.templates.list();
        } catch (final IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        }
    }

    private CompletableFuture<Void> saveTemplateInternal(final String world, final String template) {
        final AuroraWorldRegistry.Entry entry = this.registry.get(world).orElse(null);
        if (entry == null) return CompletableFuture.failedFuture(new IllegalArgumentException("no AWF world named " + world));
        if (Bukkit.getWorld(world) != null) {
            return CompletableFuture.failedFuture(new IllegalStateException(world + " is loaded; unload it first,"
                + " so the template is one consistent moment"));
        }
        final var invalid = AuroraWorldRegistry.invalidTemplateName(template);
        if (invalid.isPresent()) return CompletableFuture.failedFuture(new IllegalArgumentException(invalid.get()));
        return CompletableFuture.runAsync(() -> {
            try {
                final int chunks = this.templates.save(AwfEngine.backend(), folder(world), entry, template);
                SourbyLogger.info("Aurora World Fabric: saved " + world + " as template " + template
                    + " (" + chunks + " chunks)");
            } catch (final IOException failed) {
                throw new CompletionException(failed);
            }
        }, dev.iyanz.sourbycraft.util.VirtualExecutor.executor());
    }

    private CompletableFuture<Void> deleteTemplateInternal(final String template) {
        final var invalid = invalidTemplate(template);
        if (invalid.isPresent()) return invalid.get();
        if (!this.templates.exists(template)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("no template named " + template));
        }
        final List<String> instances = this.registry.all().stream()
            .filter(entry -> template.equals(entry.template())).map(AuroraWorldRegistry.Entry::name).toList();
        if (!instances.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException("template " + template
                + " still has instances: " + String.join(", ", instances)));
        }
        return CompletableFuture.runAsync(() -> {
            try {
                AwfEngine.forgetTemplate(template);
                this.templates.delete(template);
                SourbyLogger.info("Aurora World Fabric: deleted template " + template);
            } catch (final IOException failed) {
                throw new CompletionException(failed);
            }
        }, dev.iyanz.sourbycraft.util.VirtualExecutor.executor());
    }

    private CompletableFuture<Void> saveInternal(final String name) {
        final World world = Bukkit.getWorld(name);
        if (world == null || !this.registry.contains(name)) {
            return CompletableFuture.failedFuture(new IllegalStateException(name + " is not a loaded AWF world"));
        }
        final ServerLevel level = ((CraftWorld) world).getHandle();
        // Obtain the lane before posting region work. A stopped executor must not throw after
        // the fan-out started and release the lifecycle reservation while regions still save.
        final var ioExecutor = dev.iyanz.sourbycraft.util.VirtualExecutor.executor();
        final long drainMillis = AwfEngine.saveDrainTimeoutMillis();
        final WorldSaveBarrier regions = new WorldSaveBarrier();
        // The fan-out runs on the global tick, like the engine's own save-all: the regioniser is
        // read under its lock there, and each region consumes its ticket at its next tick.
        RegionizedServer.getInstance().addTask(() -> {
            try {
                level.regioniser.computeForAllRegions(region -> {
                    if (region.hasNoAliveSections() || region.isDead()) return;
                    regions.admitted();
                    try {
                        // The engine reports failure, then always invokes the completion callback
                        // in finally. Do not release world admission from the failure callback.
                        region.getData().canvas$saveAllTicket.propagate(new dev.iyanz.aurora.engine.util.ticket.SaveAllTicket(
                            regions::finished, regions::failed, true));
                    } catch (final Throwable failed) {
                        regions.failed(failed);
                        regions.finished(); // this ticket was never admitted by its region
                    }
                });
            } catch (final Throwable failed) {
                regions.failed(failed);
            } finally {
                regions.finished();
            }
        });
        // Every region has handed its chunks to the chunk system's I/O. Region tickets are not a
        // cross-region I/O barrier: drain the writes admitted by all tickets before the final AWF
        // snapshot (bounded, see boundedDrain), then commit off the region threads, where
        // flushConfirmed returns only once the commit is durable and throws when it is not.
        return regions.completion()
            .thenCompose(ignored -> boundedDrain(name, level, ioExecutor, drainMillis))
            .thenRunAsync(() -> {
                for (final AwfRegionStorage storage : AwfEngine.storagesUnder(folder(name))) {
                    try {
                        storage.flushConfirmed();
                    } catch (final IOException failed) {
                        throw new CompletionException(failed);
                    }
                }
                recordKnownStores(name);
            }, ioExecutor);
    }

    /**
     * The chunk system's I/O queue of {@code level} drained ({@code MoonriseRegionFileIO.flush}),
     * failing as not durable when that takes longer than {@code millis}: the drain waits until no
     * chunk, POI or entity task of the world is pending, reads included, so steady chunk traffic can
     * keep it from settling. The drain cannot be interrupted and keeps waiting after a timeout; a
     * later save of the same world starts its own drain only after it ends, so a world never holds
     * more than one I/O thread for this.
     */
    private CompletableFuture<Void> boundedDrain(final String name, final ServerLevel level,
                                                 final java.util.concurrent.Executor io, final long millis) {
        final Runnable flush = () -> ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.flush(level);
        // A new drain, never the running one: that one may already have seen the queue empty before
        // this save's writes were queued.
        final CompletableFuture<Void> drain = this.drains.compute(name, (key, running) ->
            running == null || running.isDone() ? CompletableFuture.runAsync(flush, io)
                : running.handle((ok, failed) -> null).thenRunAsync(flush, io));
        drain.whenComplete((ok, failed) -> this.drains.remove(name, drain));
        return drain.copy().orTimeout(millis, java.util.concurrent.TimeUnit.MILLISECONDS).exceptionally(failed -> {
            if (cause(failed) instanceof java.util.concurrent.TimeoutException) {
                throw new CompletionException(new IOException("the chunk I/O of " + name + " did not drain within "
                    + millis + " ms (" + dev.iyanz.sourbycraft.awf.AwfSettings.SHUTDOWN_DEADLINE_KEY + "); nothing was"
                    + " committed and this save is not durable"));
            }
            throw failed instanceof CompletionException completion ? completion : new CompletionException(failed);
        });
    }

    /**
     * Records which of the world's AWF stores exist ({@link AuroraWorldRegistry.Entry#knownStores}),
     * adding to what was recorded, so a later load refuses when one of them has gone missing.
     * Blocking backend I/O and a registry write: off the region threads only. A failure is logged,
     * not thrown: the save or load it follows is not affected, and the next one records it.
     */
    private void recordKnownStores(final String name) {
        try {
            final AuroraWorldRegistry.Entry entry = this.registry.get(name).orElse(null);
            if (entry == null) return;
            final List<String> previous = entry.knownStores() == null ? List.of() : entry.knownStores();
            final AwfBackend backend = AwfEngine.backend();
            final List<String> known = new java.util.ArrayList<>();
            for (final String storage : AwfEngine.STORAGE_FOLDERS) {
                if (previous.contains(storage) || backend.exists(AwfEngine.storeId(folder(name), storage))) known.add(storage);
            }
            if (!known.equals(entry.knownStores())) this.registry.put(entry.withKnownStores(List.copyOf(known)));
        } catch (final IOException | RuntimeException failed) {
            SourbyLogger.warn("Aurora World Fabric could not record the stores of " + name + " in " + AwfEngine.MANAGED_FILE
                + " (" + failed.getMessage() + "); until it is, a load cannot tell a missing store from a new one");
        }
    }

    /** The cause a {@link CompletionException} wraps, or the throwable itself. */
    private static Throwable cause(final Throwable failed) {
        return failed instanceof CompletionException && failed.getCause() != null ? failed.getCause() : failed;
    }

    private CompletableFuture<UnloadResult> unloadInternal(final String name, final boolean save) {
        final World world = Bukkit.getWorld(name);
        if (world == null) return CompletableFuture.failedFuture(new IllegalStateException(name + " is not loaded"));
        // A stopping server refuses the unload itself once its shutdown thread started (SERVER_STOPPING)
        // and saves the world in its own shutdown; a save first would wait on region tickets that may
        // never run again. The unload is asked for as before.
        if (!save || !this.registry.contains(name) || serverStopping()) return unloadNow(name, world, save);
        // The engine closes the world's storages at the very end of its unload, after the world is
        // removed, so a failed final commit there can no longer keep the world loaded. Save durably
        // first: when that fails the unload is refused and the world stays loaded for a retry.
        return saveInternal(name).handle((saved, failed) -> failed).thenCompose(failed -> {
            if (failed == null) return unloadNow(name, world, true);
            final Throwable reason = cause(failed);
            return CompletableFuture.failedFuture(new IOException("AWF world " + name + " was not unloaded: its save"
                + " before the unload did not complete durably (" + (reason.getMessage() == null
                ? reason.getClass().getSimpleName() : reason.getMessage()) + "); it stays loaded and the unload can be"
                + " retried", reason));
        });
    }

    /** Asks the engine to unload a loaded world, on the global tick. */
    private CompletableFuture<UnloadResult> unloadNow(final String name, final World world, final boolean save) {
        final CompletableFuture<UnloadResult> result = new CompletableFuture<>();
        RegionizedServer.getInstance().addTask(() -> {
            // The engine always saves on unload (its save flag is ignored); without saving, the
            // world's AWF storages discard what the unload writes instead. Marked here, applied in
            // discardOnUnload only once the engine has accepted the unload.
            if (!save) markUnloadWithoutSaving(name);
            try {
                Bukkit.getServer().unloadWorldAsync(world, save, outcome -> {
                    this.unloadWithoutSaving.remove(name);
                    result.complete(map(outcome));
                });
            } catch (final Throwable failed) {
                this.unloadWithoutSaving.remove(name);
                result.completeExceptionally(failed);
            }
        });
        return result;
    }

    /** Remembers that the unload of {@code name} must discard what it writes; see {@link #discardOnUnload}. */
    void markUnloadWithoutSaving(final String name) {
        this.unloadWithoutSaving.add(name);
    }

    /** Whether an unload of {@code name} is still waiting to discard its writes. */
    boolean unloadWithoutSavingPending(final String name) {
        return this.unloadWithoutSaving.contains(name);
    }

    private CompletableFuture<Void> deleteInternal(final String name) {
        if (!this.registry.contains(name)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("no AWF world named " + name));
        }
        if (Bukkit.getWorld(name) != null) {
            return CompletableFuture.failedFuture(new IllegalStateException(name + " is loaded; unload it first"));
        }
        return CompletableFuture.runAsync(() -> {
            try {
                closeOpenStorages(folder(name));
                AwfEngine.deleteStores(folder(name));
                deleteRecursively(folder(name));
                this.registry.remove(name);
                AwfEngine.release(name);
                SourbyLogger.info("Aurora World Fabric: deleted world " + name);
            } catch (final IOException failed) {
                throw new CompletionException(failed);
            }
        }, dev.iyanz.sourbycraft.util.VirtualExecutor.executor());
    }

    /**
     * Discards and closes the storages still open under a world's folder that is about to be
     * deleted, such as those of a creation that failed after they attached, whose abandoning unload
     * may still be closing them. Deleting the stores under an open storage kept it registered in the
     * engine: a later commit recreated part of a store, and every new world of the name was refused
     * as "already open" until restart. A close another thread already started (the unload's) returns
     * here at once, without waiting for it: such a storage (or one whose close the shutdown deadline
     * refused) is still open afterwards, and the cleanup is refused until it is closed. Blocking:
     * off the region threads only.
     *
     * @throws IOException naming each storage whose close failed or that is still being closed;
     *     nothing may be deleted then
     */
    private static void closeOpenStorages(final Path folder) throws IOException {
        IOException failed = null;
        for (final AwfRegionStorage storage : AwfEngine.storagesUnder(folder)) {
            storage.discard();
            try {
                storage.close();
            } catch (final IOException | RuntimeException closeFailed) {
                if (failed == null) failed = new IOException("could not close the AWF storages still open under " + folder);
                failed.addSuppressed(closeFailed);
            }
        }
        if (failed != null) throw failed;
        final List<AwfRegionStorage> stillOpen = AwfEngine.storagesUnder(folder);
        if (!stillOpen.isEmpty()) {
            throw new IOException("AWF storages under " + folder + " are still open after their close (another thread,"
                + " such as a world unload, is still closing them, or the shutdown deadline refused the close): "
                + stillOpen.stream().map(AwfRegionStorage::name).toList() + "; nothing was deleted, retry");
        }
    }

    /**
     * Fired by the engine after every unload precondition has passed, just before the world's
     * regions start shutting down. A world unloaded without saving has its AWF storages discard
     * from here on, so it keeps its last commit. Never earlier: an unload refused for players
     * present would otherwise leave a loaded world dropping its writes.
     */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void discardOnUnload(final dev.iyanz.sourbycraft.api.event.world.WorldUnloadAsyncEvent event) {
        final String name = event.getWorld().getName();
        if (!this.unloadWithoutSaving.remove(name)) return;
        final int storages = AwfEngine.discardUnder(this.folders.apply(name));
        SourbyLogger.info("Aurora World Fabric: unloading " + name + " without saving (" + storages
            + " storages discard their writes)");
    }

    /** The engine's unload outcome, in the API's own terms. */
    static UnloadResult map(final WorldUnloadResult outcome) {
        return switch (outcome) {
            case SUCCESS -> UnloadResult.SUCCESS;
            case FAIL_ALREADY_UNLOADING -> UnloadResult.ALREADY_UNLOADING;
            case FAIL_IS_OVERWORLD -> UnloadResult.IS_OVERWORLD;
            case FAIL_IS_SHUTDOWN -> UnloadResult.SERVER_STOPPING;
            case FAIL_PLAYERS_JOINING -> UnloadResult.PLAYERS_JOINING;
            case FAIL_PLAYERS_PRESENT -> UnloadResult.PLAYERS_PRESENT;
            case FAIL_UNLOAD_EVENT -> UnloadResult.CANCELLED_BY_EVENT;
            default -> UnloadResult.FAILED;
        };
    }

    /** Loads every autoload world on the global tick, after the default worlds exist. */
    public void scheduleAutoload() {
        RegionizedServer.getInstance().addTask(this::loadAutoloadWorlds);
    }

    /** Loads every autoload world. Runs on the global region thread. */
    void loadAutoloadWorlds() {
        for (final AuroraWorldRegistry.Entry entry : this.registry.all()) {
            if (!entry.autoload()) continue;
            load(entry.name()).whenComplete((world, failed) -> {
                if (failed != null) {
                    SourbyLogger.warn("Aurora World Fabric could not load " + entry.name() + " at startup", failed);
                } else {
                    SourbyLogger.info("Aurora World Fabric: loaded " + entry.name());
                }
            });
        }
    }

    /** The open storages (region, entities, poi) of a loaded world; empty when it is unloaded. */
    public java.util.List<dev.iyanz.sourbycraft.awf.AwfRegionStorage> storages(final String name) {
        return AwfEngine.storagesUnder(folder(name));
    }

    /** The world's dimension folder, where its region, entity and POI storages and AWF stores live. */
    static Path folder(final String name) {
        final MinecraftServer server = MinecraftServer.getServer();
        final ResourceKey<net.minecraft.world.level.Level> key = ResourceKey.create(Registries.DIMENSION,
            CraftNamespacedKey.toMinecraft(NamespacedKey.minecraft(name)));
        return server.storageSource.getDimensionPath(key).toAbsolutePath().normalize();
    }

    private static void deleteRecursively(final Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            final List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (final Path path : paths) Files.delete(path);
        }
    }

    private interface Action<T> {
        T run() throws Exception;
    }

    /**
     * Runs {@code action} on the global region thread once {@code ready} completes, and follows
     * the future it returns. The global tick never waits for {@code ready} (blocking I/O on the
     * I/O lane): completed already and called there, the action runs at once; otherwise it runs
     * on a later global tick. The result therefore may complete after this returns even for a
     * caller on the global tick, which must not join it there (see {@link WorldOperationGate}).
     */
    private static <T> CompletableFuture<T> createAfter(final CompletableFuture<Void> ready,
                                                        final Action<CompletableFuture<T>> action) {
        if (ready.isDone() && !ready.isCompletedExceptionally() && Bukkit.isGlobalTickThread()) {
            return started(action);
        }
        return ready.thenCompose(ignored -> onGlobal(action).thenCompose(java.util.function.Function.identity()));
    }

    private static <T> CompletableFuture<T> started(final Action<CompletableFuture<T>> action) {
        try {
            return action.run();
        } catch (final Throwable failed) {
            return CompletableFuture.failedFuture(failed);
        }
    }

    /** True on the global region thread; false without a server (unit tests). */
    private static boolean onGlobalTick() {
        return Bukkit.getServer() != null && Bukkit.isGlobalTickThread();
    }

    private static <T> CompletableFuture<T> onGlobal(final Action<T> action) {
        final CompletableFuture<T> result = new CompletableFuture<>();
        RegionizedServer.getInstance().addTask(() -> {
            try {
                result.complete(action.run());
            } catch (final Throwable failed) {
                result.completeExceptionally(failed);
            }
        });
        return result;
    }
}
