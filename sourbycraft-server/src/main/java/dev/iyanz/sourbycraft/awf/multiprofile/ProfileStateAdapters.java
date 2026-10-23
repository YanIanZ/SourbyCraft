package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.ProfileStateAdapter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Registry of plugin-owned profile state adapters.
 *
 * <p>Adapters are keyed by id and version ({@code id:version}) inside the snapshot, so a plugin that
 * bumps its version does not have its old bytes silently applied to a new format: the pair no longer
 * matches and the adapter is skipped with a warning. Registration is per plugin; disabling a plugin
 * removes its adapters.</p>
 */
public final class ProfileStateAdapters {

    private record Registered(Plugin plugin, ProfileStateAdapter adapter) {}

    private final ConcurrentHashMap<String, Registered> adapters = new ConcurrentHashMap<>();

    public void register(final Plugin plugin, final ProfileStateAdapter adapter) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(adapter, "adapter");
        final String id = adapter.id();
        if (id == null || !id.matches("[a-z0-9_.:-]{1,64}")) {
            throw new IllegalArgumentException("adapter id must match [a-z0-9_.:-]{1,64}: " + id);
        }
        if (adapter.version() < 1) throw new IllegalArgumentException("adapter version " + adapter.version());
        this.adapters.put(id, new Registered(plugin, adapter));
    }

    public boolean unregister(final Plugin plugin, final String id) {
        final Registered registered = this.adapters.get(id);
        if (registered == null || registered.plugin() != plugin) return false;
        return this.adapters.remove(id, registered);
    }

    public int unregisterAll(final Plugin plugin) {
        int removed = 0;
        for (final Map.Entry<String, Registered> entry : this.adapters.entrySet()) {
            if (entry.getValue().plugin() == plugin && this.adapters.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        return removed;
    }

    public int size() {
        return this.adapters.size();
    }

    /** Captures every registered adapter for one player; a failure is logged and that adapter skipped. */
    Map<String, byte[]> capture(final Player player) {
        final Map<String, byte[]> captured = new LinkedHashMap<>();
        this.adapters.forEach((id, registered) -> {
            try {
                final byte[] data = registered.adapter().capture(player);
                if (data != null && data.length > 0) {
                    if (data.length > 2 * 1024 * 1024) {
                        dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF MultiProfile: adapter '" + id
                            + "' returned " + data.length + " bytes; the limit is 2097152 and the data was skipped");
                        return;
                    }
                    captured.put(id + ":" + registered.adapter().version(), data);
                }
            } catch (final Exception failed) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF MultiProfile: adapter '" + id
                    + "' failed to capture: " + failed.getMessage());
            }
        });
        return captured;
    }

    /** Applies the adapters whose id and version still have a registration. */
    void apply(final Player player, final Map<String, byte[]> state) {
        state.forEach((key, data) -> {
            final int colon = key.lastIndexOf(':');
            if (colon <= 0) return;
            final String id = key.substring(0, colon);
            final int version;
            try {
                version = Integer.parseInt(key.substring(colon + 1));
            } catch (final NumberFormatException malformed) {
                return;
            }
            final Registered registered = this.adapters.get(id);
            if (registered == null || registered.adapter().version() != version) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF MultiProfile: adapter '" + id + "' version " + version
                    + " is not registered (current version is "
                    + (registered == null ? "absent" : registered.adapter().version()) + "); its data was not applied");
                return;
            }
            try {
                registered.adapter().apply(player, data);
            } catch (final Exception failed) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF MultiProfile: adapter '" + id
                    + "' failed to apply: " + failed.getMessage());
            }
        });
    }
}
