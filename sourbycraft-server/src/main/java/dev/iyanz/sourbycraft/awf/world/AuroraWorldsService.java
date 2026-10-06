package dev.iyanz.sourbycraft.awf.world;

import dev.iyanz.sourbycraft.api.world.AuroraWorlds;
import dev.iyanz.sourbycraft.api.world.UnloadResult;
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
import java.util.concurrent.atomic.AtomicInteger;
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
public final class AuroraWorldsService implements AuroraWorlds {

    private final AuroraWorldRegistry registry;
    private final AuroraTemplates templates;

    public AuroraWorldsService(final AuroraWorldRegistry registry, final AuroraTemplates templates) {
        this.registry = registry;
        this.templates = templates;
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
    public boolean autoload(final String name) {
        return this.registry.get(name).map(AuroraWorldRegistry.Entry::autoload).orElse(false);
    }

    @Override
    public void setAutoload(final String name, final boolean autoload) {
        final AuroraWorldRegistry.Entry entry = this.registry.get(name)
            .orElseThrow(() -> new IllegalArgumentException("no AWF world named " + name));
        try {
            this.registry.put(entry.withAutoload(autoload));
        } catch (final IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        }
    }

    @Override
    public CompletableFuture<World> create(final WorldCreator creator, final String generator, final boolean autoload) {
        if (creator.generator() != null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("name the generator instead of"
                + " setting one on the creator (void, or Plugin[:id]), so the world can be loaded again with it"));
        }
        if (!creator.key().equals(NamespacedKey.minecraft(creator.name()))) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                "AWF worlds use the key minecraft:" + creator.name() + ", not " + creator.key()));
        }
        final String environment = creator.environment().name().toLowerCase(Locale.ROOT);
        final String type = creator.type() == WorldType.NORMAL ? null : creator.type().name().toLowerCase(Locale.ROOT);
        return start(new AuroraWorldRegistry.Entry(creator.name(), environment, creator.seed(), generator, type,
            null, autoload));
    }

    @Override
    public CompletableFuture<World> createFromTemplate(final String template, final String name, final boolean autoload) {
        final AuroraWorldRegistry.Entry description;
        try {
            description = this.templates.read(template).orElse(null);
        } catch (final IOException failed) {
            return CompletableFuture.failedFuture(failed);
        }
        if (description == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("no template named " + template));
        }
        return start(description.named(name, template, autoload));
    }

    @Override
    public Optional<String> template(final String name) {
        return this.registry.get(name).map(AuroraWorldRegistry.Entry::template);
    }

    /** Registers a new world and creates it. The entry is removed again if creation fails. */
    private CompletableFuture<World> start(final AuroraWorldRegistry.Entry entry) {
        final String name = entry.name();
        final var invalid = AuroraWorldRegistry.invalidName(name);
        if (invalid.isPresent()) return CompletableFuture.failedFuture(new IllegalArgumentException(invalid.get()));
        if (this.registry.contains(name) || Bukkit.getWorld(name) != null || Files.exists(folder(name))) {
            return CompletableFuture.failedFuture(new IllegalStateException("a world named " + name + " already exists"));
        }
        final WorldCreator creator;
        try {
            creator = creator(entry);
            // Registered before the world exists, so its region storages attach to AWF as they open.
            this.registry.put(entry);
            AwfEngine.manage(name, entry.template());
        } catch (final IOException | RuntimeException failed) {
            return CompletableFuture.failedFuture(failed);
        }
        return prepared(entry).thenCompose(ignored -> onGlobal(() -> {
            final World world = Bukkit.createWorld(creator);
            if (world == null) throw new IllegalStateException("the server refused to create " + name);
            return world;
        })).whenComplete((world, failed) -> {
            if (failed != null) {
                AwfEngine.unprepare(folder(name));
                try {
                    this.registry.remove(name);
                } catch (final IOException ignored) {
                    // The original failure is what the caller needs; the entry is retried on delete.
                }
                AwfEngine.release(name);
            }
        });
    }

    @Override
    public CompletableFuture<World> load(final String name) {
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
        AwfEngine.manage(name, entry.template());
        return prepared(entry).thenCompose(ignored -> onGlobal(() -> {
            final World existing = Bukkit.getWorld(name);
            if (existing != null) return existing;
            final World world = Bukkit.createWorld(creator);
            if (world == null) throw new IllegalStateException("the server refused to load " + name);
            return world;
        })).whenComplete((world, failed) -> {
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
            creator.generator(new VoidGenerator());
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
        return CompletableFuture.runAsync(() -> {
            try {
                AwfEngine.prepare(folder(entry.name()), entry.template());
            } catch (final IOException failed) {
                throw new CompletionException(failed);
            }
        }, dev.iyanz.sourbycraft.util.VirtualExecutor.executor());
    }

    @Override
    public Set<String> templates() {
        try {
            return this.templates.list();
        } catch (final IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        }
    }

    @Override
    public CompletableFuture<Void> saveTemplate(final String world, final String template) {
        final AuroraWorldRegistry.Entry entry = this.registry.get(world).orElse(null);
        if (entry == null) return CompletableFuture.failedFuture(new IllegalArgumentException("no AWF world named " + world));
        if (Bukkit.getWorld(world) != null) {
            return CompletableFuture.failedFuture(new IllegalStateException(world + " is loaded; unload it first,"
                + " so the template is one consistent moment"));
        }
        final var invalid = AuroraWorldRegistry.invalidName(template);
        if (invalid.isPresent()) return CompletableFuture.failedFuture(new IllegalArgumentException(invalid.get()));
        if (!"file".equals(AwfEngine.backendName())) {
            return CompletableFuture.failedFuture(new IllegalStateException("templates need the file backend"));
        }
        return CompletableFuture.runAsync(() -> {
            try {
                final int chunks = this.templates.save(folder(world), entry, template);
                SourbyLogger.info("Aurora World Fabric: saved " + world + " as template " + template
                    + " (" + chunks + " chunks)");
            } catch (final IOException failed) {
                throw new CompletionException(failed);
            }
        }, dev.iyanz.sourbycraft.util.VirtualExecutor.executor());
    }

    @Override
    public CompletableFuture<Void> deleteTemplate(final String template) {
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

    @Override
    public CompletableFuture<Void> save(final String name) {
        final World world = Bukkit.getWorld(name);
        if (world == null || !this.registry.contains(name)) {
            return CompletableFuture.failedFuture(new IllegalStateException(name + " is not a loaded AWF world"));
        }
        final ServerLevel level = ((CraftWorld) world).getHandle();
        final CompletableFuture<Void> regionsSaved = new CompletableFuture<>();
        final AtomicInteger outstanding = new AtomicInteger(1);    // released after the fan-out
        final Runnable oneDone = () -> {
            if (outstanding.decrementAndGet() == 0) regionsSaved.complete(null);
        };
        // The fan-out runs on the global tick, like the engine's own save-all: the regioniser is
        // read under its lock there, and each region consumes its ticket at its next tick.
        RegionizedServer.getInstance().addTask(() -> {
            try {
                level.regioniser.computeForAllRegions(region -> {
                    if (region.hasNoAliveSections() || region.isDead()) return;
                    outstanding.incrementAndGet();
                    region.getData().canvas$saveAllTicket.propagate(new dev.iyanz.aurora.engine.util.ticket.SaveAllTicket(
                        oneDone, regionsSaved::completeExceptionally, true));
                });
            } catch (final Throwable failed) {
                regionsSaved.completeExceptionally(failed);
            } finally {
                oneDone.run();
            }
        });
        // Every region has handed its chunks to the storages; commit them off the region threads,
        // where AwfRegionStorage.flush waits until the commit is durable.
        return regionsSaved.thenRunAsync(() -> {
            for (final AwfRegionStorage storage : AwfEngine.storagesUnder(folder(name))) {
                try {
                    storage.flush();
                } catch (final IOException failed) {
                    throw new CompletionException(failed);
                }
            }
        }, dev.iyanz.sourbycraft.util.VirtualExecutor.executor());
    }

    @Override
    public CompletableFuture<UnloadResult> unload(final String name, final boolean save) {
        final World world = Bukkit.getWorld(name);
        if (world == null) return CompletableFuture.failedFuture(new IllegalStateException(name + " is not loaded"));
        final CompletableFuture<UnloadResult> result = new CompletableFuture<>();
        RegionizedServer.getInstance().addTask(() -> {
            try {
                Bukkit.getServer().unloadWorldAsync(world, save, outcome -> result.complete(map(outcome)));
            } catch (final Throwable failed) {
                result.completeExceptionally(failed);
            }
        });
        return result;
    }

    @Override
    public CompletableFuture<Void> delete(final String name) {
        if (!this.registry.contains(name)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("no AWF world named " + name));
        }
        if (Bukkit.getWorld(name) != null) {
            return CompletableFuture.failedFuture(new IllegalStateException(name + " is loaded; unload it first"));
        }
        return CompletableFuture.runAsync(() -> {
            try {
                deleteRecursively(folder(name));
                this.registry.remove(name);
                AwfEngine.release(name);
                SourbyLogger.info("Aurora World Fabric: deleted world " + name);
            } catch (final IOException failed) {
                throw new CompletionException(failed);
            }
        }, dev.iyanz.sourbycraft.util.VirtualExecutor.executor());
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
