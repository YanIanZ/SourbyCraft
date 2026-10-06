package dev.iyanz.sourbycraft.api.world;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.jspecify.annotations.NullMarked;

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
 * <p>Every operation completes on the global region thread or later; never block a region thread
 * on the returned futures. Names are lower-case {@code [a-z0-9_-]}, 1 to 48 characters.</p>
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
     * Creates and loads a new AWF world. Fails if a world with that name already exists.
     *
     * @param creator name, environment, generator and seed of the new world
     * @param autoload whether the world is loaded again when the server starts
     */
    CompletableFuture<World> create(WorldCreator creator, boolean autoload);

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
}
