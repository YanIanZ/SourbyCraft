package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.ProfileAdapterDescriptor;
import dev.iyanz.sourbycraft.api.profile.ProfileDataCriticality;
import dev.iyanz.sourbycraft.api.profile.ProfileDataScope;
import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.api.profile.ProfileStateAdapter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
 * 2 MiB (or the smaller bound its descriptor declares) and all adapters of a snapshot 6 MiB together.
 * Within those bounds, data of an adapter that is not registered on this boot is kept in the profile
 * ({@link #carryForward}) instead of being dropped by the next save.</p>
 *
 * <p><b>Contract (Build 53, RFC E03).</b> {@link ProfileStateAdapter#descriptor()} is read once at
 * registration. An adapter is <em>required</em> when its descriptor says {@link ProfileDataCriticality#REQUIRED}
 * or the operator lists its id in {@code adapters.required} (the list only raises strictness). A required
 * adapter whose capture throws or exceeds its bound fails the whole capture ({@link ProfileErrorCode#ADAPTER_REQUIRED_FAILED});
 * one whose apply throws fails the apply; stored data of a required id whose exact version is not registered
 * refuses the apply before it starts ({@link ProfileErrorCode#ADAPTER_MISSING}, {@link #validateApply}). A
 * best-effort adapter that fails or exceeds its bound is skipped with a diagnostic and the bytes the profile
 * already stores for it at that version are kept ({@link Capture#preserved()}); a {@code null} or empty capture
 * still means "nothing to store" and removes them. {@link ProfileDataScope#UNSUPPORTED} adapters are registered
 * but never captured or applied (their data is not per profile; the registration documents that);
 * {@link ProfileDataScope#ACCOUNT_WIDE} adapters are captured and applied like profile-scoped ones (the server
 * cannot verify the declaration; it is reported, not enforced). The aggregate budget is never resolved by
 * dropping: a snapshot whose captured and carried entries exceed it is refused ({@link ProfileErrorCode#STATE_TOO_LARGE}).</p>
 *
 * <p><b>Stored criticality and migration (Build 53, review P2-7).</b> The merge writes one reserved adapter entry,
 * {@link #REQUIRED_META_KEY}, listing the ids of the stored entries that were REQUIRED when stored (by descriptor or by
 * the operator). Build 52 carries it forward as an unknown adapter blob and never applies it. {@link #validateApply}
 * and {@link #apply} treat an entry as required when its stored record, its registration or the operator list says so.
 * Stored data of a required id at an <em>older</em> version than the registration is offered to
 * {@link ProfileStateAdapter#migrate}; a migration the adapter does not support refuses the apply with
 * {@link ProfileErrorCode#ADAPTER_REQUIRED_FAILED} naming the adapter and both versions (a best-effort one is skipped,
 * its bytes kept). Data at a <em>newer</em> version than the registration, or of an unregistered required id, refuses
 * with {@link ProfileErrorCode#ADAPTER_MISSING} naming the versions. Plugins register at most
 * {@value #MAX_PLUGIN_ADAPTERS} adapters; the 32nd snapshot entry is the reserved record.</p>
 */
public final class ProfileStateAdapters {

    /** The reserved snapshot entry that records which stored adapter ids were REQUIRED; never a plugin's id. */
    public static final String REQUIRED_META_KEY = "sourbycraft.required-adapters:1";
    /** Plugin adapters registered at most; one snapshot entry stays reserved for {@link #REQUIRED_META_KEY}. */
    public static final int MAX_PLUGIN_ADAPTERS = ProfileState.MAX_ADAPTERS - 1;

    /** A registration; the descriptor is read once, here, so capture, apply and the merge agree on the key and rules. */
    private record Registered(Plugin plugin, ProfileStateAdapter adapter, int version, ProfileAdapterDescriptor descriptor,
                              boolean required) {

        int maxBytes() {
            return (int) Math.min(ProfileState.MAX_ADAPTER_BYTES, this.descriptor.maxBytes());
        }

        boolean captured() {
            return this.descriptor.scope() != ProfileDataScope.UNSUPPORTED;
        }
    }

    /**
     * One capture: the entries to store; the keys ({@code id:version}) of best-effort adapters whose capture failed
     * or exceeded a bound, whose previously stored bytes at that exact key the merge keeps ({@code preserved}); and
     * the keys of adapters that were asked and returned nothing, whose stored bytes the merge removes ({@code cleared};
     * it lets a capture staged before its adapters were unregistered, at shutdown, merge exactly like a live one).
     */
    public record Capture(Map<String, byte[]> data, Set<String> preserved, Set<String> cleared) {

        public static final Capture EMPTY = new Capture(Map.of(), Set.of(), Set.of());

        public Capture {
            data = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(data, "data")));
            preserved = Set.copyOf(Objects.requireNonNull(preserved, "preserved"));
            cleared = Set.copyOf(Objects.requireNonNull(cleared, "cleared"));
        }
    }

    private final ConcurrentHashMap<String, Registered> adapters = new ConcurrentHashMap<>();
    /** Adapter ids the operator forces to REQUIRED ({@code adapters.required}). */
    private final Set<String> forcedRequired;

    public ProfileStateAdapters() {
        this(Set.of());
    }

    /** @param forcedRequired adapter ids treated as REQUIRED whatever their descriptor declares */
    public ProfileStateAdapters(final Set<String> forcedRequired) {
        this.forcedRequired = Set.copyOf(Objects.requireNonNull(forcedRequired, "forcedRequired"));
    }

    /**
     * Registers an adapter, or replaces the one the same plugin registered under that id. Its
     * {@code version()} and {@code descriptor()} are read once, here; a plugin that changes them registers again.
     * Refused, with an {@link IllegalArgumentException}, for a malformed id, a version below 1, an {@code id:version}
     * key longer than 64 characters, a descriptor that throws or whose id or version differ from {@code id()} and
     * {@code version()}, an id another enabled plugin registered, and a new id once
     * {@value ProfileState#MAX_ADAPTERS} adapters are registered.
     */
    public synchronized void register(final Plugin plugin, final ProfileStateAdapter adapter) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(adapter, "adapter");
        final String id = adapter.id();
        if (id == null || !id.matches("[a-z0-9_.:-]{1,64}")) {
            throw new IllegalArgumentException("adapter id must match [a-z0-9_.:-]{1,64}: " + id);
        }
        if (REQUIRED_META_KEY.equals(key(id, adapter.version())) || idOf(REQUIRED_META_KEY).equals(id)) {
            throw new IllegalArgumentException("adapter id '" + id + "' is reserved for the server's own snapshot record");
        }
        final int version = adapter.version();
        if (version < 1) throw new IllegalArgumentException("adapter version " + version);
        if (key(id, version).length() > ProfileState.MAX_ADAPTER_KEY) {
            throw new IllegalArgumentException("adapter id '" + id + "' with version " + version + " makes the snapshot key '"
                + key(id, version) + "' longer than " + ProfileState.MAX_ADAPTER_KEY + " characters; use a shorter id");
        }
        final ProfileAdapterDescriptor descriptor;
        try {
            descriptor = adapter.descriptor();
        } catch (final RuntimeException failed) {
            throw new IllegalArgumentException("adapter '" + id + "': descriptor() failed: " + failed.getMessage(), failed);
        }
        if (descriptor == null) throw new IllegalArgumentException("adapter '" + id + "': descriptor() returned null");
        if (!descriptor.id().equals(id) || descriptor.version() != version) {
            throw new IllegalArgumentException("adapter '" + id + "' version " + version + ": its descriptor names '"
                + descriptor.id() + "' version " + descriptor.version() + "; they must be equal");
        }
        final Registered existing = this.adapters.get(id);
        if (existing != null && existing.plugin() != plugin && existing.plugin().isEnabled()) {
            throw new IllegalArgumentException("adapter id '" + id + "' is registered by " + existing.plugin().getName());
        }
        if (existing == null && this.adapters.size() >= MAX_PLUGIN_ADAPTERS) {
            throw new IllegalArgumentException(MAX_PLUGIN_ADAPTERS + " profile state adapters are registered"
                + " already (one snapshot entry is reserved for the server); '" + id + "' was refused");
        }
        final boolean required = descriptor.criticality() == ProfileDataCriticality.REQUIRED || this.forcedRequired.contains(id);
        this.adapters.put(id, new Registered(plugin, adapter, version, descriptor, required));
        if (required && descriptor.criticality() != ProfileDataCriticality.REQUIRED) {
            dev.iyanz.sourbycraft.util.SourbyLogger.info("AWF MultiProfile: adapter '" + id + "' declares BEST_EFFORT but "
                + ProfileSettings.ADAPTERS_REQUIRED_KEY + " lists it; it is treated as REQUIRED");
        }
        if (descriptor.scope() == ProfileDataScope.UNSUPPORTED) {
            dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF MultiProfile: adapter '" + id + "' of " + plugin.getName()
                + " declares scope UNSUPPORTED: its plugin state is not isolated per profile and is neither captured nor"
                + " applied by a switch");
        }
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

    /** The descriptor of a registered adapter id, or {@code null}; for diagnostics. */
    public ProfileAdapterDescriptor descriptor(final String id) {
        final Registered registered = this.adapters.get(id);
        return registered == null ? null : registered.descriptor();
    }

    /** Whether {@code id} is required on this node: registered as REQUIRED or forced by {@code adapters.required}. */
    public boolean required(final String id) {
        final Registered registered = this.adapters.get(id);
        return registered != null ? registered.required() : this.forcedRequired.contains(id);
    }

    /**
     * Captures every registered adapter for one player. A best-effort adapter that throws, returns more than its
     * bound, or would push the adapters past 6 MiB together is logged, stores nothing in this capture and is named in
     * {@link Capture#preserved()} so the merge keeps its previously stored bytes. A required adapter in any of those
     * cases fails the capture with {@link ProfileErrorCode#ADAPTER_REQUIRED_FAILED}: nothing of it is skipped. An
     * UNSUPPORTED-scope adapter is not asked. Runs on the player's owning thread.
     */
    Capture capture(final Player player) {
        final Map<String, byte[]> captured = new LinkedHashMap<>();
        final Set<String> preserved = new LinkedHashSet<>();
        final Set<String> cleared = new LinkedHashSet<>();
        long total = 0L;
        for (final Map.Entry<String, Registered> entry : this.adapters.entrySet()) {
            final String id = entry.getKey();
            final Registered registered = entry.getValue();
            if (!registered.captured()) continue;
            final String key = key(id, registered.version());
            final byte[] data;
            try {
                data = registered.adapter().capture(player);
            } catch (final Exception failed) {
                skipped(registered, id, key, preserved, "failed to capture: " + failed.getMessage());
                continue;
            }
            if (data == null || data.length == 0) {
                cleared.add(key);
                continue;
            }
            if (data.length > registered.maxBytes()) {
                skipped(registered, id, key, preserved, "returned " + data.length + " bytes; its limit is " + registered.maxBytes());
                continue;
            }
            if (total + data.length > ProfileState.MAX_ADAPTER_TOTAL) {
                skipped(registered, id, key, preserved, "returned " + data.length + " bytes, which would put the adapters of one"
                    + " profile past " + ProfileState.MAX_ADAPTER_TOTAL + " bytes");
                continue;
            }
            total += data.length;
            captured.put(key, data);
        }
        return new Capture(captured, preserved, cleared);
    }

    private static void skipped(final Registered registered, final String id, final String key, final Set<String> preserved,
                                final String why) {
        if (registered.required()) {
            throw new ProfileOperationException(ProfileErrorCode.ADAPTER_REQUIRED_FAILED, "required adapter '" + id + "' " + why
                + "; the capture was refused and nothing was skipped");
        }
        preserved.add(key);
        dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF MultiProfile: adapter '" + id + "' " + why + "; the data was skipped"
            + " and the bytes the profile already stores for it are kept");
    }

    /** {@link #carryForward(Map, Map, Capture)} with nothing preserved or cleared beyond the registry's own rules. */
    Map<String, byte[]> carryForward(final Map<String, byte[]> captured, final Map<String, byte[]> previous) {
        return carryForward(captured, previous, new Capture(captured, Set.of(), Set.of()));
    }

    /**
     * The adapter entries a new snapshot of a profile stores: {@code captured}, followed by each entry
     * of {@code previous} (the profile's last stored state) that this registry does not account for,
     * so the data of a plugin that is not installed or not enabled on this boot, or that is registered
     * at another version and stored nothing, stays in the profile, and each entry of {@code previous} whose key
     * {@code capture} preserved (a best-effort adapter that failed this capture). An entry is accounted for when the
     * capture holds an entry with the same id, when {@code capture} cleared its key (asked, returned nothing), or when
     * a capturing adapter is registered under that id and version and the key is not preserved: it was asked and
     * stored nothing (an UNSUPPORTED-scope registration is never asked, so its stored bytes are carried). The captured
     * entries come first; the reserved {@link #REQUIRED_META_KEY} entry, naming every kept id that is required here or
     * was recorded required before, comes last and is written only when it names something.
     * More than {@value #MAX_PLUGIN_ADAPTERS} entries or more than 6 MiB together refuses the snapshot
     * ({@link ProfileErrorCode#STATE_TOO_LARGE}) naming the entries that do not fit: the budget is never met by
     * dropping required or preserved bytes. Reads no player and calls no plugin; safe on any thread.
     */
    Map<String, byte[]> carryForward(final Map<String, byte[]> captured, final Map<String, byte[]> previous,
                                     final Capture capture) {
        final Set<String> preserved = capture.preserved();
        final Set<String> previouslyRequired = requiredIds(previous);
        final Map<String, byte[]> kept = new LinkedHashMap<>();
        final List<String> over = new ArrayList<>();
        long total = 0L;
        for (final Map.Entry<String, byte[]> entry : captured.entrySet()) {
            if (REQUIRED_META_KEY.equals(entry.getKey())) continue;
            if (kept.size() >= MAX_PLUGIN_ADAPTERS || total + entry.getValue().length > ProfileState.MAX_ADAPTER_TOTAL) {
                over.add(entry.getKey());
                continue;
            }
            total += entry.getValue().length;
            kept.put(entry.getKey(), entry.getValue());
        }
        for (final Map.Entry<String, byte[]> entry : previous.entrySet()) {
            final String key = entry.getKey();
            if (REQUIRED_META_KEY.equals(key)) continue;
            final String id = idOf(key);
            if (id == null || capturedId(captured, id) || capture.cleared().contains(key)) continue;
            final Registered registered = this.adapters.get(id);
            if (registered != null && registered.captured() && key.equals(key(id, registered.version())) && !preserved.contains(key)) continue;
            if (kept.size() >= MAX_PLUGIN_ADAPTERS || total + entry.getValue().length > ProfileState.MAX_ADAPTER_TOTAL) {
                over.add(key);
                continue;
            }
            total += entry.getValue().length;
            kept.put(key, entry.getValue());
        }
        if (!over.isEmpty()) {
            throw new ProfileOperationException(ProfileErrorCode.STATE_TOO_LARGE, "a profile snapshot holds at most "
                + MAX_PLUGIN_ADAPTERS + " adapter entries and " + ProfileState.MAX_ADAPTER_TOTAL + " bytes of adapter data;"
                + " these do not fit beside the others: " + over + ". The snapshot was refused rather than written without them");
        }
        final java.util.TreeSet<String> required = new java.util.TreeSet<>();
        for (final String key : kept.keySet()) {
            final String id = idOf(key);
            if (id != null && (required(id) || previouslyRequired.contains(id))) required.add(id);
        }
        if (!required.isEmpty()) kept.put(REQUIRED_META_KEY, encodeRequired(required));
        return kept;
    }

    /** The ids the stored {@link #REQUIRED_META_KEY} entry of {@code state} marks required; empty without one or when unreadable. */
    static Set<String> requiredIds(final Map<String, byte[]> state) {
        final byte[] meta = state.get(REQUIRED_META_KEY);
        if (meta == null) return Set.of();
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(meta))) {
            final int count = in.readInt();
            if (count < 0 || count > ProfileState.MAX_ADAPTERS) throw new java.io.IOException("count " + count);
            final Set<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < count; i++) ids.add(in.readUTF());
            return Set.copyOf(ids);
        } catch (final java.io.IOException malformed) {
            dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF MultiProfile: the stored required-adapter record is unreadable ("
                + malformed.getMessage() + "); stored criticality is ignored for this state");
            return Set.of();
        }
    }

    private static byte[] encodeRequired(final Set<String> ids) {
        final java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(buffer)) {
            out.writeInt(ids.size());
            for (final String id : ids) out.writeUTF(id);
        } catch (final java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        return buffer.toByteArray();
    }

    /** Whether stored data of {@code id} is required here: registered or forced REQUIRED, or recorded REQUIRED in {@code state}. */
    private boolean requiredStored(final String id, final Set<String> recorded) {
        return required(id) || recorded.contains(id);
    }

    /**
     * Refuses, before any apply starts, stored data of a required adapter id (required by registration, by the
     * operator list or by the snapshot's own record) that cannot be applied here: no registration, an UNSUPPORTED
     * registration, a registration at an <em>older</em> version than the data ({@link ProfileErrorCode#ADAPTER_MISSING},
     * naming the stored and registered versions), or a registration at a newer version whose
     * {@link ProfileStateAdapter#migrate} does not support the stored version
     * ({@link ProfileErrorCode#ADAPTER_REQUIRED_FAILED}, naming adapter and versions). Data of a best-effort or unknown
     * id is not checked (it stays in the profile, see {@link #apply}). Calls {@code migrate} of required adapters only;
     * reads no player; safe on any thread.
     */
    void validateApply(final Map<String, byte[]> state) {
        final Set<String> recorded = requiredIds(state);
        final List<String> missing = new ArrayList<>();
        final List<String> unmigratable = new ArrayList<>();
        for (final Map.Entry<String, byte[]> entry : state.entrySet()) {
            final String key = entry.getKey();
            if (REQUIRED_META_KEY.equals(key)) continue;
            final String id = idOf(key);
            if (id == null || !requiredStored(id, recorded)) continue;
            final int stored = versionOf(key);
            final Registered registered = this.adapters.get(id);
            if (registered == null || !registered.captured()) {
                missing.add(key + " (" + (registered == null ? "not registered" : "registered with scope UNSUPPORTED") + ")");
                continue;
            }
            if (registered.version() == stored) continue;
            if (registered.version() < stored) {
                missing.add(key + " (registered at the older version " + registered.version() + ")");
                continue;
            }
            final String failure = migrationFailure(registered, id, stored, entry.getValue());
            if (failure != null) unmigratable.add(failure);
        }
        if (!missing.isEmpty()) {
            throw new ProfileOperationException(ProfileErrorCode.ADAPTER_MISSING, "the profile stores data of required adapter(s) "
                + missing + " that cannot be applied on this node; the state cannot be applied safely. Load the plugin at a"
                + " version that reads that data and retry");
        }
        if (!unmigratable.isEmpty()) {
            throw new ProfileOperationException(ProfileErrorCode.ADAPTER_REQUIRED_FAILED, "required adapter(s) cannot migrate"
                + " their stored data to the registered version: " + unmigratable + "; the state cannot be applied safely");
        }
    }

    /** Runs {@code migrate} for a version gap; {@code null} when it succeeded, else the reason naming adapter and versions. */
    private static String migrationFailure(final Registered registered, final String id, final int stored, final byte[] data) {
        try {
            final byte[] migrated = registered.adapter().migrate(stored, data);
            if (migrated == null || migrated.length == 0) {
                return "'" + id + "' stored version " + stored + " -> registered version " + registered.version() + " (migrate() not supported)";
            }
            if (migrated.length > registered.maxBytes()) {
                return "'" + id + "' stored version " + stored + " -> registered version " + registered.version() + " (migrated to "
                    + migrated.length + " bytes, over its " + registered.maxBytes() + "-byte bound)";
            }
            return null;
        } catch (final Exception failed) {
            return "'" + id + "' stored version " + stored + " -> registered version " + registered.version() + " (migrate() failed: "
                + failed.getMessage() + ")";
        }
    }

    /**
     * Applies the adapters whose id has a registration at the stored version, or at a newer version after a
     * successful {@link ProfileStateAdapter#migrate}. A best-effort adapter that fails, or cannot be migrated, is
     * logged and skipped (its data stays in the profile); a required one (by registration, operator list or the
     * snapshot's record) that fails fails the apply with {@link ProfileErrorCode#ADAPTER_REQUIRED_FAILED} after the
     * remaining adapters ran. Runs on the player's owning thread; callers {@link #validateApply} first.
     */
    void apply(final Player player, final Map<String, byte[]> state) {
        apply(player, state, Set.of());
    }

    /**
     * The adapter ids of a capture's {@link Capture#preserved() preserved} keys: best-effort adapters whose capture
     * failed, whose live state on the player is therefore the only copy. An apply of a state merged from that capture (a
     * salvage destination) must leave them alone (review P2-4): see {@link #apply(Player, Map, Set)}. A switch rollback
     * does not use it (review N1): it runs after the target apply already replaced those adapters' live state, so it
     * restores them from the carried stored entry or resets them.
     */
    public static Set<String> preservedIds(final Capture capture) {
        final Set<String> ids = new LinkedHashSet<>();
        for (final String key : capture.preserved()) {
            final String id = idOf(key);
            if (id != null) ids.add(id);
        }
        return Set.copyOf(ids);
    }

    /**
     * {@link #apply(Player, Map)} where the adapters named in {@code untouched} (ids) are never told
     * {@link ProfileStateAdapter#applyAbsent}: their data is missing from {@code state} because their capture failed,
     * not because the profile holds none, so a reset would overwrite the player's only copy with "nothing" and the
     * next capture would store it. An entry of {@code state} under such an id is still applied.
     */
    void apply(final Player player, final Map<String, byte[]> state, final Set<String> untouched) {
        final Set<String> recorded = requiredIds(state);
        final List<String> requiredFailures = new ArrayList<>();
        state.forEach((key, stored) -> {
            if (REQUIRED_META_KEY.equals(key)) return;
            final String id = idOf(key);
            if (id == null) return;
            final int version = versionOf(key);
            if (version < 0) return;
            final boolean required = requiredStored(id, recorded);
            final Registered registered = this.adapters.get(id);
            if (registered == null || !registered.captured() || registered.version() < version) {
                if (required) requiredFailures.add(key + " (not registered at a version that reads it)");
                dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF MultiProfile: adapter '" + id + "' version " + version
                    + " is not registered (current version is "
                    + (registered == null ? "absent" : registered.version()) + "); its data was not applied"
                    + " and stays in the profile");
                return;
            }
            byte[] data = stored;
            if (registered.version() != version) {
                byte[] migrated = null;
                String failure = null;
                try {
                    migrated = registered.adapter().migrate(version, stored);
                    if (migrated == null || migrated.length == 0) failure = "migrate() not supported";
                    else if (migrated.length > registered.maxBytes()) failure = "migrated to " + migrated.length + " bytes, over its bound";
                } catch (final Exception failed) {
                    failure = "migrate() failed: " + failed.getMessage();
                }
                if (failure != null) {
                    if (required) {
                        requiredFailures.add(key + " -> version " + registered.version() + " (" + failure + ")");
                    } else {
                        dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF MultiProfile: adapter '" + id + "' stored version " + version
                            + " was not migrated to version " + registered.version() + " (" + failure + "); its data was not applied"
                            + " and stays in the profile");
                    }
                    return;
                }
                data = migrated;
            }
            try {
                registered.adapter().apply(player, data);
            } catch (final Exception failed) {
                if (required) {
                    requiredFailures.add(key + " (" + failed.getMessage() + ")");
                } else {
                    dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF MultiProfile: adapter '" + id
                        + "' failed to apply: " + failed.getMessage());
                }
            }
        });
        // Build 55: a registered adapter with no entry of its id in the state (any version) is told so, on the same thread
        // and after the others applied, so per-profile plugin state never follows the player into a profile that holds none.
        final Set<String> present = new java.util.HashSet<>();
        for (final String key : state.keySet()) {
            final String id = idOf(key);
            if (id != null) present.add(id);
        }
        for (final Map.Entry<String, Registered> entry : this.adapters.entrySet()) {
            final String id = entry.getKey();
            final Registered registered = entry.getValue();
            if (!registered.captured() || present.contains(id) || untouched.contains(id)) continue;
            try {
                registered.adapter().applyAbsent(player);
            } catch (final Exception failed) {
                if (registered.required() || this.forcedRequired.contains(id)) {
                    requiredFailures.add(id + " (applyAbsent: " + failed.getMessage() + ")");
                } else {
                    dev.iyanz.sourbycraft.util.SourbyLogger.warn("AWF MultiProfile: adapter '" + id
                        + "' failed to reset for a profile without its data (applyAbsent): " + failed.getMessage());
                }
            }
        }
        if (!requiredFailures.isEmpty()) {
            throw new ProfileOperationException(ProfileErrorCode.ADAPTER_REQUIRED_FAILED, "required adapter(s) failed to apply: "
                + requiredFailures + "; the apply is reported as failed, not skipped");
        }
    }

    /** The version of a key {@code id:version}, or {@code -1} when malformed. */
    private static int versionOf(final String key) {
        final int colon = key.lastIndexOf(':');
        if (colon <= 0) return -1;
        try {
            return Integer.parseInt(key.substring(colon + 1));
        } catch (final NumberFormatException malformed) {
            return -1;
        }
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
