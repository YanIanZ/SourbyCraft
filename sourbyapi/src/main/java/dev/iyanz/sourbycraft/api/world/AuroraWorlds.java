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
 * <p>Every operation completes on the global region thread or later; never block a region thread
 * on the returned futures. World and template names are lower-case {@code [a-z0-9_-]}, 1 to 48
 * characters.</p>
 */
@NullMarked
public interface AuroraWorlds {

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

    /** The template a world is an instance of, if any. */
    Optional<String> template(String name);

    /** Loads a world created through this API. Completes with the world if it is already loaded. */
    CompletableFuture<World> load(String name);

    /**
     * Saves every region of a loaded world and commits its AWF stores. Completes once the commit
     * is durable.
     */
    CompletableFuture<Void> save(String name);

    /**
     * Unloads a loaded world, optionally saving it first. Fails with the engine's reason when the
     * world cannot be unloaded, for example while players are in it.
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
