package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.ProfileStateAdapter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 *
 * <p>Bounds: at most {@value ProfileState#MAX_ADAPTERS} adapters are registered, an id whose key is
 * longer than {@value ProfileState#MAX_ADAPTER_KEY} characters is refused, one adapter stores at most
 * 2 MiB and all adapters of a snapshot 6 MiB together. Within those bounds, data of an adapter that is
 * not registered on this boot is kept in the profile ({@link #carryForward}) instead of being dropped by
 * the next save.</p>
 */
public final class ProfileStateAdapters {

    /** A registration; the version is read once, here, so capture, apply and the merge agree on the key. */
    private record Registered(Plugin plugin, ProfileStateAdapter adapter, int version) {}

    private final ConcurrentHashMap<String, Registered> adapters = new ConcurrentHashMap<>();

    /**
     * Registers an adapter, or replaces the one the same plugin registered under that id. Its
     * {@code version()} is read once, here; a plugin that changes it registers again. Refused, with an
     * {@link IllegalArgumentException}, for a malformed id, a version below 1, an {@code id:version} key
     * longer than 64 characters, an id another enabled plugin registered, and a new id once
     * {@value ProfileState#MAX_ADAPTERS} adapters are registered.
     */
    public synchronized void register(final Plugin plugin, final ProfileStateAdapter adapter) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(adapter, "adapter");
        final String id = adapter.id();
        if (id == null || !id.matches("[a-z0-9_.:-]{1,64}")) {
            throw new IllegalArgumentException("adapter id must match [a-z0-9_.:-]{1,64}: " + id);
        }
        final int version = adapter.version();
        if (version < 1) throw new IllegalArgumentException("adapter version " + version);
        if (key(id, version).length() > ProfileState.MAX_ADAPTER_KEY) {
            throw new IllegalArgumentException("adapter id '" + id + "' with version " + version + " makes the snapshot key '"
                + key(id, version) + "' longer than " + ProfileState.MAX_ADAPTER_KEY + " characters; use a shorter id");
        }
        final Registered existing = this.adapters.get(id);
        if (existing != null && existing.plugin() != plugin && existing.plugin().isEnabled()) {
            throw new IllegalArgumentException("adapter id '" + id + "' is registered by " + existing.plugin().getName());
        }
        if (existing == null && this.adapters.size() >= ProfileState.MAX_ADAPTERS) {
            throw new IllegalArgumentException(ProfileState.MAX_ADAPTERS + " profile state adapters are registered"
                + " already; '" + id + "' was refused");
        }
        this.adapters.put(id, new Registered(plugin, adapter, version));
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

    /**
     * Captures every registered adapter for one player. An adapter that throws, returns more than
     * 2 MiB, or would push the adapters past 6 MiB together is logged and stores nothing in this
     * capture.
     */
    Map<String, byte[]> capture(final Player player) {
        final Map<String, byte[]> captured = new LinkedHashMap<>();
        long total = 0L;
        for (final Map.Entry<String, Registered> entry : this.adapters.entrySet()) {
            final String id = entry.getKey();
            final Registered registered = entry.getValue();
            try {
                final byte[] data = registered.adapter().capture(player);
                if (data == null || data.length == 0) continue;
                if (data.length > ProfileState.MAX_ADAPTER_BYTES) {
                    dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF MultiProfile: adapter '" + id + "' returned "
                        + data.length + " bytes; the limit is " + ProfileState.MAX_ADAPTER_BYTES + " and the data was skipped");
                    continue;
                }
                if (total + data.length > ProfileState.MAX_ADAPTER_TOTAL) {
                    dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF MultiProfile: adapter '" + id + "' returned "
                        + data.length + " bytes, which would put the adapters of one profile past "
                        + ProfileState.MAX_ADAPTER_TOTAL + " bytes; the data was skipped");
                    continue;
                }
                total += data.length;
                captured.put(key(id, registered.version()), data);
            } catch (final Exception failed) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF MultiProfile: adapter '" + id
                    + "' failed to capture: " + failed.getMessage());
            }
        }
        return captured;
    }

    /**
     * The adapter entries a new snapshot of a profile stores: {@code captured}, followed by each entry
     * of {@code previous} (the profile's last stored state) that this registry does not account for,
     * so the data of a plugin that is not installed or not enabled on this boot, or that is registered
     * at another version and stored nothing, stays in the profile. An entry is accounted for when the
     * capture holds an entry with the same id, or when an adapter is registered under that id and
     * version: it was asked and stored nothing (it returned nothing, failed, or was over a limit).
     * The captured entries come first; entries past {@value ProfileState#MAX_ADAPTERS} or past 6 MiB
     * together are not stored, with one warning. Reads no player and calls no plugin; safe on any
     * thread.
     */
    Map<String, byte[]> carryForward(final Map<String, byte[]> captured, final Map<String, byte[]> previous) {
        final Map<String, byte[]> kept = new LinkedHashMap<>();
        final List<String> dropped = new ArrayList<>();
        long total = 0L;
        for (final Map.Entry<String, byte[]> entry : captured.entrySet()) {
            if (kept.size() >= ProfileState.MAX_ADAPTERS || total + entry.getValue().length > ProfileState.MAX_ADAPTER_TOTAL) {
                dropped.add(entry.getKey());
                continue;
            }
            total += entry.getValue().length;
            kept.put(entry.getKey(), entry.getValue());
        }
        for (final Map.Entry<String, byte[]> entry : previous.entrySet()) {
            final String key = entry.getKey();
            final String id = idOf(key);
            if (id == null || capturedId(captured, id)) continue;
            final Registered registered = this.adapters.get(id);
            if (registered != null && key.equals(key(id, registered.version()))) continue;
            if (kept.size() >= ProfileState.MAX_ADAPTERS || total + entry.getValue().length > ProfileState.MAX_ADAPTER_TOTAL) {
                dropped.add(key);
                continue;
            }
            total += entry.getValue().length;
            kept.put(key, entry.getValue());
        }
        if (!dropped.isEmpty()) {
            dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF MultiProfile: a profile snapshot holds at most "
                + ProfileState.MAX_ADAPTERS + " adapter entries and " + ProfileState.MAX_ADAPTER_TOTAL
                + " bytes of adapter data; not stored: " + dropped);
        }
        return kept;
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
            if (registered == null || registered.version() != version) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF MultiProfile: adapter '" + id + "' version " + version
                    + " is not registered (current version is "
                    + (registered == null ? "absent" : registered.version()) + "); its data was not applied"
                    + " and stays in the profile");
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

    private static String key(final String id, final int version) {
        return id + ":" + version;
    }

    /** The id of a key {@code id:version}, or {@code null} when the key has no version part. */
    private static String idOf(final String key) {
        final int colon = key.lastIndexOf(':');
        return colon <= 0 ? null : key.substring(0, colon);
    }

    private static boolean capturedId(final Map<String, byte[]> captured, final String id) {
        for (final String key : captured.keySet()) {
            if (id.equals(idOf(key))) return true;
        }
        return false;
    }
}
