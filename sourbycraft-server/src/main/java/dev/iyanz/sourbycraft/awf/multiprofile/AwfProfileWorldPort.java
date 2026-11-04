package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.world.AuroraWorlds;
import dev.iyanz.sourbycraft.api.world.WorldRequest;
import dev.iyanz.sourbycraft.api.world.UnloadResult;
import dev.iyanz.sourbycraft.awf.world.AuroraWorldRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Binds profile worlds to Aurora World Fabric through the public {@code AuroraWorlds} API.
 *
 * <p>Creation follows AWF's own rules: a missing world is created from the profile's template as a
 * copy-on-write instance ({@code createFromTemplate}), never by copying folders; loading is the
 * engine's {@code load}; and unloading is {@code unload(name, true)}, which saves the world durably
 * first and leaves it loaded when that save fails, so a world is durable before it leaves memory.
 * Players must already be out of the world — the engine's
 * unload result says {@code FAIL_PLAYERS_PRESENT} and this port surfaces that as a failure instead of
 * forcing the unload.</p>
 *
 * <p>No lifecycle future is joined here. The port is called by the profile engine on its I/O
 * executor, so the entity and region threads never wait for a world.</p>
 */
public final class AwfProfileWorldPort implements ProfileWorldPort {

    private final Supplier<AuroraWorlds> worlds;

    public AwfProfileWorldPort(final Supplier<AuroraWorlds> worlds) {
        this.worlds = Objects.requireNonNull(worlds, "worlds");
    }

    @Override
    public CompletableFuture<Void> ensureReady(final ProfileWorldBinding binding) {
        final AuroraWorlds service = this.worlds.get();
        if (service == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Aurora World Fabric is not running"));
        }
        final List<CompletableFuture<?>> pending = new ArrayList<>();
        for (final String world : binding.worlds().keySet()) {
            if (!service.exists(world)) {
                if (binding.template() == null) {
                    return CompletableFuture.failedFuture(new IllegalStateException("profile world '" + world
                        + "' does not exist and the profile has no template to create it from"));
                }
                pending.add(service.create(new WorldRequest.TemplateClone(binding.template(), world, false)));
            } else if (!service.isLoaded(world)) {
                pending.add(service.load(world));
            }
        }
        if (pending.isEmpty()) return CompletableFuture.completedFuture(null);
        return CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).thenApply(ignored -> null);
    }

    @Override
    public CompletableFuture<Void> saveAndUnload(final String world) {
        final AuroraWorlds service = this.worlds.get();
        if (service == null || !service.isLoaded(world)) return CompletableFuture.completedFuture(null);
        return service.unload(world, true).thenAccept(result -> {
            if (result != UnloadResult.SUCCESS) {
                throw new IllegalStateException("world '" + world + "' could not be unloaded: " + result);
            }
        });
    }

    @Override
    public boolean loaded(final String world) {
        final AuroraWorlds service = this.worlds.get();
        return service != null && service.isLoaded(world);
    }

    /**
     * A world in the AWF registry. AWF refuses the server's level-name (the main world) and the vanilla
     * dimension names as world names; the name check repeats that refusal here, so the main world is
     * never managed even if a registry entry claimed its name.
     */
    @Override
    public boolean managed(final String world) {
        final AuroraWorlds service = this.worlds.get();
        return service != null && service.exists(world) && AuroraWorldRegistry.invalidName(world).isEmpty();
    }

    /** A level's player list is a copy-on-write list in the region-threaded engine, safe to read from any thread. */
    @Override
    public boolean occupied(final String world) {
        final World loaded = Bukkit.getWorld(world);
        return loaded != null && !loaded.getPlayers().isEmpty();
    }

    /** The world's spawn block, centred on it as the server places a player at a world spawn. */
    @Override
    public ProfileState.Location spawnOf(final String world) {
        final World loaded = Bukkit.getWorld(world);
        if (loaded == null) return null;
        final Location spawn = loaded.getSpawnLocation();
        return new ProfileState.Location(world, spawn.getBlockX() + 0.5, spawn.getY(), spawn.getBlockZ() + 0.5,
            spawn.getYaw(), spawn.getPitch());
    }

    /** Visible for diagnostics: the AWF storage description, or a reason it is unavailable. */
    public String describe() {
        final AuroraWorlds service = this.worlds.get();
        return service == null ? "unavailable" : service.storage();
    }
}
