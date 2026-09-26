package dev.iyanz.sourbycraft.awf;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The loaded AWF worlds, for the "loaded worlds" metric and for anything that needs to enumerate
 * them. Registration is explicit; a world that is never registered is never counted.
 */
public final class AwfRegistry {

    private final Map<String, AwfWorld> worlds = new ConcurrentHashMap<>();

    /** Registers a world; a second world with the same name is refused. */
    public void register(final AwfWorld world) {
        if (this.worlds.putIfAbsent(world.name(), world) != null) {
            throw new IllegalStateException("an AWF world named " + world.name() + " is already loaded");
        }
    }

    public boolean unregister(final String name) {
        return this.worlds.remove(name) != null;
    }

    public AwfWorld get(final String name) {
        return this.worlds.get(name);
    }

    public int loaded() {
        return this.worlds.size();
    }

    public Collection<AwfWorld> all() {
        return List.copyOf(this.worlds.values());
    }
}
