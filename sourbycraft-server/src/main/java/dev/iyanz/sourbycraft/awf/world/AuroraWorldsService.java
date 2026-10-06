package dev.iyanz.sourbycraft.awf.world;

import dev.iyanz.sourbycraft.api.world.AuroraWorlds;
import dev.iyanz.sourbycraft.api.world.UnloadResult;
import dev.iyanz.sourbycraft.api.world.WorldProperties;
import dev.iyanz.sourbycraft.awf.AwfBackend;
import dev.iyanz.sourbycraft.awf.AwfEngine;
import dev.iyanz.sourbycraft.awf.AwfRegionStorage;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import io.canvasmc.canvas.WorldUnloadResult;
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
 * its chunks, then commits the world's AWF stores off the region threads, so a completed save is
 * durable. Deletion removes the world's dimension folder, which holds its AWF stores.</p>
 *
 * <p>Templates are kept by {@link AuroraTemplates}, on the FILE backend only.</p>
 */
public final class AuroraWorldsService implements AuroraWorlds, org.bukkit.event.Listener {

    private final AuroraWorldRegistry registry;
    private final AuroraTemplates templates;
    /** Worlds whose pending unload must not save; see {@link #discardOnUnload}. */
    private final Set<String> unloadWithoutSaving = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final WorldOperationGate operations = new WorldOperationGate();

    public AuroraWorldsService(final AuroraWorldRegistry registry, final AuroraTemplates templates) {
        this.registry = registry;
        this.templates = templates;
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
            try {
                this.registry.put(updated);
            } catch (final IOException failed) {
                return CompletableFuture.failedFuture(failed);
            }
            AwfEngine.configure(name, updated.prunesEmptyChunks(), properties.saveBounds());
            if (Bukkit.getWorld(name) == null) return CompletableFuture.completedFuture(null);
            return onGlobal(() -> {
                final World world = Bukkit.getWorld(name);
                if (world != null) applyProperties(world, properties);
                return null;
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
        return start(withFile, () -> {
            final SlimeImporter.Result result = SlimeImporter.importInto(Files.readAllBytes(file), folder(entry.name()), minSection);
            SourbyLogger.info("Aurora World Fabric: imported " + file.getFileName() + " as " + entry.name() + " ("
                + result.chunks() + " chunks, " + result.entityChunks() + " with entities, " + result.poiChunks()
                + " with POI, data version " + result.dataVersion() + ")");
            if (!fileWorld.dropped().isEmpty()) {
                SourbyLogger.warn("Aurora World Fabric: importing " + file.getFileName() + " did not keep "
                    + fileWorld.dropped() + " (not representable in AWF yet)");
            }
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

    private CompletableFuture<World> createFromTemplateInternal(final String template, final String name, final boolean autoload) {
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
        final WorldCreator creator;
        try {
            if (AwfEngine.storesExist(folder(name))) {
                // A network backend can hold a world this server has no folder for.
                throw new IllegalStateException("AWF stores for " + name + " already exist on " + storage()
                    + "; delete them or choose another name");
            }
            creator = creator(entry);
            // Registered before the world exists, so its region storages attach to AWF as they open.
            this.registry.put(entry);
            AwfEngine.manage(name, entry.template(), entry.prunesEmptyChunks());
            AwfEngine.configure(name, entry.prunesEmptyChunks(), entry.propertiesOrNone().saveBounds());
        } catch (final IOException | RuntimeException failed) {
            return CompletableFuture.failedFuture(failed);
        }
        final CompletableFuture<Void> ready = before == null ? prepared(entry)
            : dev.iyanz.sourbycraft.util.VirtualExecutor.<Void>supply(() -> {
                before.run();
                return null;
            }).thenCompose(ignored -> prepared(entry));
        return createAfter(ready, () -> {
            final World world = Bukkit.createWorld(creator);
            if (world == null) throw new IllegalStateException("the server refused to create " + name);
            applyProperties(world, entry.propertiesOrNone());
            return world;
        }).whenComplete((world, failed) -> {
            if (failed != null) {
                AwfEngine.unprepare(folder(name));
                if (before != null && Bukkit.getWorld(name) == null) {
                    try {
                        deleteRecursively(folder(name));
                    } catch (final IOException ignored) {
                        // Reported by the original failure; the folder blocks the name until removed.
                    }
                }
                try {
                    this.registry.remove(name);
                } catch (final IOException ignored) {
                    // The original failure is what the caller needs; the entry is retried on delete.
                }
                AwfEngine.release(name);
            }
        });
    }

    private CompletableFuture<World> loadInternal(final String name) {
        final AuroraWorldRegistry.Entry entry = this.registry.get(name).orElse(null);
        if (entry == null) return CompletableFuture.failedFuture(new IllegalArgumentException("no AWF world named " + name));
        final World loaded = Bukkit.getWorld(name);
        if (loaded != null) return CompletableFuture.completedFuture(loaded);
        final WorldCreator creator;
        try {
            creator = creator(entry);
        } catch (final RuntimeException failed) {
            return CompletableFuture.failedFuture(failed);
        }
        AwfEngine.manage(name, entry.template(), entry.prunesEmptyChunks());
            AwfEngine.configure(name, entry.prunesEmptyChunks(), entry.propertiesOrNone().saveBounds());
        return createAfter(prepared(entry), () -> {
            final World existing = Bukkit.getWorld(name);
            if (existing != null) return existing;
            final World world = Bukkit.createWorld(creator);
            if (world == null) throw new IllegalStateException("the server refused to load " + name);
            applyProperties(world, entry.propertiesOrNone());
            return world;
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

    private CompletableFuture<World> importWorldInternal(final Path file, final String name, final boolean autoload) {
        final AuroraWorldRegistry.Entry entry;
        try (dev.iyanz.sourbycraft.awf.AwfWorldFile awf = dev.iyanz.sourbycraft.awf.AwfWorldFile.open(file)) {
            entry = AuroraWorldFiles.describe(awf, name, autoload);
        } catch (final IOException failed) {
            return CompletableFuture.failedFuture(failed);
        }
        return start(entry, () -> {
            final long started = System.nanoTime();
            try (dev.iyanz.sourbycraft.awf.AwfWorldFile awf = dev.iyanz.sourbycraft.awf.AwfWorldFile.open(file)) {
                final var written = AuroraWorldFiles.importInto(awf, folder(name));
                SourbyLogger.info("Aurora World Fabric: imported " + file.getFileName() + " as " + name + " " + written
                    + " in " + (System.nanoTime() - started) / 1_000_000 + " ms");
            }
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
                final AuroraWorldFiles.Written written = AuroraWorldFiles.convertSlime(Files.readAllBytes(slime), env,
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
        final var invalid = AuroraWorldRegistry.invalidName(template);
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
        // Every region has handed its chunks to the storages; commit them off the region threads,
        // where AwfRegionStorage.flush waits until the commit is durable.
        return regions.completion().thenRunAsync(() -> {
            for (final AwfRegionStorage storage : AwfEngine.storagesUnder(folder(name))) {
                try {
                    storage.flush();
                } catch (final IOException failed) {
                    throw new CompletionException(failed);
                }
            }
        }, ioExecutor);
    }

    private CompletableFuture<UnloadResult> unloadInternal(final String name, final boolean save) {
        final World world = Bukkit.getWorld(name);
        if (world == null) return CompletableFuture.failedFuture(new IllegalStateException(name + " is not loaded"));
        final CompletableFuture<UnloadResult> result = new CompletableFuture<>();
        RegionizedServer.getInstance().addTask(() -> {
            // The engine always saves on unload (its save flag is ignored); without saving, the
            // world's AWF storages discard what the unload writes instead. Marked here, applied in
            // discardOnUnload only once the engine has accepted the unload.
            if (!save) this.unloadWithoutSaving.add(name);
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

    private CompletableFuture<Void> deleteInternal(final String name) {
        if (!this.registry.contains(name)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("no AWF world named " + name));
        }
        if (Bukkit.getWorld(name) != null) {
            return CompletableFuture.failedFuture(new IllegalStateException(name + " is loaded; unload it first"));
        }
        return CompletableFuture.runAsync(() -> {
            try {
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
     * Fired by the engine after every unload precondition has passed, just before the world's
     * regions start shutting down. A world unloaded without saving has its AWF storages discard
     * from here on, so it keeps its last commit. Never earlier: an unload refused for players
     * present would otherwise leave a loaded world dropping its writes.
     */
    @org.bukkit.event.EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void discardOnUnload(final io.canvasmc.canvas.event.world.WorldUnloadAsyncEvent event) {
        final String name = event.getWorld().getName();
        if (!this.unloadWithoutSaving.remove(name)) return;
        final int storages = AwfEngine.discardUnder(folder(name));
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
     * Runs {@code action} on the global region thread once {@code ready} completes. Called on the
     * global region thread itself, it waits for {@code ready} (blocking I/O on the I/O lane) and
     * runs the action at once, so the returned future is already complete and a caller there can
     * join it; scheduling it for a later global tick instead would deadlock such a caller.
     */
    private static <T> CompletableFuture<T> createAfter(final CompletableFuture<Void> ready, final Action<T> action) {
        if (Bukkit.isGlobalTickThread()) {
            try {
                ready.join();
                return CompletableFuture.completedFuture(action.run());
            } catch (final CompletionException failed) {
                return CompletableFuture.failedFuture(failed.getCause() == null ? failed : failed.getCause());
            } catch (final Throwable failed) {
                return CompletableFuture.failedFuture(failed);
            }
        }
        return ready.thenCompose(ignored -> onGlobal(action));
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
