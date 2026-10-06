package dev.iyanz.sourbycraft.api.world;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Worlds stored through Aurora World Fabric, managed at runtime.
 *
 * <p>Obtain it from Bukkit's services manager:</p>
 * <pre>{@code
 * AuroraWorlds worlds = Bukkit.getServicesManager().load(AuroraWorlds.class);
 * }</pre>
 *
 * <p>An AWF world keeps its chunks in memory, commits them atomically in generations, and never
 * writes region files while it is attached. A world created here stays an AWF world across
 * restarts without any configuration change; {@code autoload} worlds are loaded again at startup.</p>
 *
 * <p>Templates are frozen worlds. A world created from a template is a copy-on-write instance:
 * it reads the template's chunks until it changes one, and keeps only its own changes, so a
 * thousand instances of one island share one copy of it on disk. A template never changes after it
 * is saved.</p>
 *
 * <p>Generators are named, so a world can be loaded again with the same one: {@code void} (built
 * in, an empty world spawning at 0.5, 64, 0.5), a plugin's generator as {@code Plugin} or
 * {@code Plugin:id}, or {@code null} for the environment's own terrain.</p>
 *
 * <p>Completion callbacks have no guaranteed thread. {@code create},
 * {@code createFromTemplate}, the imports and {@code load} called <em>on</em> the global region
 * thread do their work there and return a completed future, so a caller that needs the world at
 * once (a world provider asked for an island's world, say) can join it there. Elsewhere, joining
 * blocks that thread until the global tick has created the world; never do so on a region/entity
 * thread. Conflicting lifecycle work fails with {@link WorldOperationBusyException}, and
 * cancelling its future does not stop engine work. World and template names are lower-case {@code [a-z0-9_-]}, 1 to 48
 * characters.</p>
 */
@NullMarked
public interface AuroraWorlds {

    /**
     * Creates a persistent world or template clone from an immutable request. Existing creation
     * methods remain available. Concurrent lifecycle operations on one world fail with
     * {@link WorldOperationBusyException}; separate worlds and clones may proceed concurrently.
     * Cancelling the returned future does not stop engine work or release its reservation early.
     */
    default CompletableFuture<World> create(final WorldRequest request) {
        return switch (request) {
            case WorldRequest.Persistent persistent -> {
                final WorldCreator creator = WorldCreator.ofKey(org.bukkit.NamespacedKey.minecraft(persistent.name()))
                    .environment(persistent.environment()).type(persistent.type());
                if (persistent.seed() != null) creator.seed(persistent.seed());
                yield create(creator, persistent.generator(), persistent.autoload());
            }
            case WorldRequest.TemplateClone clone -> createFromTemplate(clone.template(), clone.name(), clone.autoload());
        };
    }

    /** Every world this server manages through this API, loaded or not. */
    Set<String> list();

    /** Whether a world with this name is managed through this API. */
    boolean exists(String name);

    /** Whether the world is currently loaded. */
    boolean isLoaded(String name);

    /**
     * Creates and loads a new AWF world with the environment's own terrain or a named generator.
     * Fails if a world with that name already exists.
     *
     * @param creator name, environment, world type and seed of the new world; its generator must
     *     be unset, because a generator object cannot be found again at the next load
     * @param generator {@code void}, {@code Plugin[:id]}, or {@code null}
     * @param autoload whether the world is loaded again when the server starts
     */
    CompletableFuture<World> create(WorldCreator creator, @Nullable String generator, boolean autoload);

    /** As {@link #create(WorldCreator, String, boolean)} with the environment's own terrain. */
    default CompletableFuture<World> create(final WorldCreator creator, final boolean autoload) {
        return create(creator, null, autoload);
    }

    /**
     * Creates and loads a copy-on-write instance of a template, with the template's environment,
     * seed and generator.
     */
    CompletableFuture<World> createFromTemplate(String template, String name, boolean autoload);

    /**
     * Imports a Slime world file, as SourbyCraft's former Slime world manager wrote it (format
     * versions 12 and 13), as a new AWF world and loads it. The file is read, not changed. Light is recomputed on first load; the chunks'
     * data version is kept, so older chunks are upgraded by the server's data fixers.
     *
     * @param file the {@code .slime} file
     * @param creator name, environment and seed of the new world; its generator must be unset
     * @param generator what generates chunks the file does not hold: {@code void} (usual for Slime
     *     worlds), {@code Plugin[:id]}, or {@code null} for the environment's own terrain
     */
    CompletableFuture<World> importSlime(java.nio.file.Path file, WorldCreator creator, @Nullable String generator,
                                         boolean autoload);

    /**
     * Imports an {@code .awf} world file as a new world and loads it, with the environment, seed
     * and generator the file records.
     */
    CompletableFuture<World> importWorld(java.nio.file.Path file, String name, boolean autoload);

    /**
     * Exports an unloaded world to one {@code .awf} file: its chunks, entities and POI as the world
     * reads them (an instance's own chunks over its template's) and how to create it again.
     */
    CompletableFuture<Void> exportWorld(String world, java.nio.file.Path file);

    /** Converts a Slime file into an {@code .awf} file without creating a world. */
    CompletableFuture<Void> convertSlime(java.nio.file.Path slime, java.nio.file.Path awf, World.Environment environment);

    /** Where world data is stored, for display: the backend and, for a network backend, its address. */
    String storage();

    /** The template a world is an instance of, if any. */
    Optional<String> template(String name);

    /**
     * Loads a world created through this API. Completes with the world if it is already loaded
     * and no conflicting lifecycle operation is in progress.
     */
    CompletableFuture<World> load(String name);

    /**
     * Saves every region of a loaded world and commits its AWF stores. Completes once the commit
     * is durable.
     */
    CompletableFuture<Void> save(String name);

    /**
     * Unloads a loaded world. Fails with the engine's reason when the world cannot be unloaded,
     * for example while players are in it.
     *
     * @param save {@code true} commits the world's current chunks; {@code false} keeps the state
     *     of its last commit and writes nothing (what changed since is lost) — the region-threaded
     *     engine itself always saves on unload, so this is done by its AWF storages discarding
     */
    CompletableFuture<UnloadResult> unload(String name, boolean save);

    /**
     * Deletes an unloaded world: its folder, its AWF stores and its entry. Fails while the world
     * is loaded.
     */
    CompletableFuture<Void> delete(String name);

    /** Whether the world is loaded again when the server starts. */
    boolean autoload(String name);

    /** Changes whether the world is loaded again when the server starts. */
    void setAutoload(String name, boolean autoload);

    /**
     * Copies an unloaded world into a new world and loads it, with the source's environment, seed,
     * generator and properties. A copy-on-write instance stays an instance of the same template and
     * copies only its own chunks, so cloning an island costs its changes, not the template.
     */
    CompletableFuture<World> cloneWorld(String source, String target, boolean autoload);

    /** The world's properties; {@link WorldProperties#NONE} when it has none. */
    WorldProperties properties(String name);

    /**
     * Replaces the world's properties. They are stored with the world, applied at once when it is
     * loaded (on the global region thread) and again on every later load.
     */
    CompletableFuture<Void> setProperties(String name, WorldProperties properties);

    /** Every saved template. */
    Set<String> templates();

    /**
     * Saves an unloaded world as a new template: its chunks, and for an instance its template's
     * chunks beneath them, flattened into a template that never changes again. The world itself
     * is left as it is.
     */
    CompletableFuture<Void> saveTemplate(String world, String template);

    /** Deletes a template. Fails while any world is an instance of it. */
    CompletableFuture<Void> deleteTemplate(String template);
}
