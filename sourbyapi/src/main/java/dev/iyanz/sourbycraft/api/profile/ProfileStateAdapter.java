package dev.iyanz.sourbycraft.api.profile;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * A plugin extension that captures and restores its own per-profile data.
 *
 * <p>The AWF core captures inventory, armor, off-hand, ender chest, experience, health, food,
 * saturation, potion effects and location. It cannot guess a plugin's storage, so a plugin registers
 * an adapter and gets a versioned, bounded blob in the profile snapshot. The adapter runs on the
 * player's owning entity thread, inside the same task as the core capture/apply.</p>
 *
 * <p>{@link #capture} returning {@code null} (or an empty array) means "nothing to store";
 * {@link #apply} is then not called for that data. Bytes are the plugin's responsibility: the core
 * stores them verbatim.</p>
 *
 * <p>Limits: one adapter stores at most 2 MiB and all adapters of one snapshot 6 MiB together; an
 * adapter past either limit is skipped for that capture with a warning. At most 31 adapters are
 * registered (the snapshot's 32nd adapter entry is reserved for the server's own record of which stored
 * entries were REQUIRED), and an id whose {@code id:version} key is longer than 64 characters is refused.</p>
 *
 * <p>Data stored by an adapter that is not registered when the profile is saved again (or that is
 * registered at another version and stores nothing) is kept in the snapshot, within the same
 * limits. Data of an adapter registered at the version it was stored with is not kept for that
 * snapshot when the adapter returns {@code null}, throws or exceeds a limit.</p>
 *
 * <p><b>Build 53.</b> {@link #descriptor()} adds a criticality ({@link ProfileDataCriticality#REQUIRED} or
 * {@link ProfileDataCriticality#BEST_EFFORT}), a scope and a size bound. An adapter that does not override it is
 * BEST_EFFORT and behaves as in Build 52, except where the next sentence applies. For a BEST_EFFORT adapter whose
 * capture fails, throws or exceeds a limit, the bytes the profile already stored for that adapter at that version
 * are preserved rather than dropped (a {@code null} return still means "nothing to store" and removes them). For
 * a REQUIRED adapter none of that is skipped: see {@link #descriptor()}.</p>
 *
 * <p><b>Threading contract.</b> {@link #capture} and {@link #apply} run synchronously on the player's owning
 * entity thread and at server shutdown on the region shutdown thread, with no timeout the server could enforce
 * without killing the thread. They must return promptly from their own data: an adapter that waits on a
 * scheduler, a database round trip or another thread hangs the switch, the autosave or the shutdown it runs in.
 * {@link #migrate} runs on a server I/O thread, never on a tick thread, and gets no {@link Player}.</p>
 *
 * <p><b>Stored criticality (Build 53).</b> The snapshot records, beside each adapter entry, whether the adapter was
 * REQUIRED when the entry was stored. A later apply of that entry treats it as REQUIRED when either the stored
 * record or the current registration (or the operator list) says so, so a REQUIRED plugin that is absent or
 * disabled refuses the apply ({@link ProfileErrorCode#ADAPTER_MISSING}) instead of being skipped.</p>
 */
public interface ProfileStateAdapter {

    /**
     * Stable id, {@code [a-z0-9_.:-]{1,64}}; the id plus {@code ':'} plus the {@link #version()} must
     * fit in 64 characters. Never reuse an id for different data.
     */
    String id();

    /**
     * Data version, at least 1, read once when the adapter is registered; bump it when the format
     * changes. Data stored under another version is not passed to {@link #apply}; it stays in the
     * profile, within the snapshot limits, until this adapter stores data at its current version.
     */
    default int version() {
        return 1;
    }

    /** Captures the player's plugin state, or {@code null}. */
    @Nullable byte[] capture(Player player) throws Exception;

    /** Restores bytes previously produced by {@link #capture}. */
    void apply(Player player, byte[] data) throws Exception;

    /**
     * Converts bytes this adapter stored under an older {@link #version()} into bytes of the current version
     * (Build 53). Called when a profile holds this adapter's data at {@code fromVersion} (below the registered
     * version) and the state is about to be applied; the returned bytes are what {@link #apply} then receives, and
     * the next capture stores the current version. Returning {@code null} or an empty array means the migration is
     * not supported: a REQUIRED adapter then refuses the apply ({@link ProfileErrorCode#ADAPTER_REQUIRED_FAILED},
     * naming the adapter and both versions) and a BEST_EFFORT adapter is skipped with a diagnostic, its stored bytes
     * kept. Data stored under a <em>newer</em> version than the registered one is never migrated: for a REQUIRED
     * adapter it refuses the apply ({@link ProfileErrorCode#ADAPTER_MISSING}). Pure: no player, no Bukkit calls;
     * runs on a server I/O thread. The default supports nothing.
     */
    default @Nullable byte[] migrate(final int fromVersion, final byte[] data) throws Exception {
        return null;
    }

    /** The plugin that owns this adapter; used for the services-manager registration. */
    Plugin plugin();

    /**
     * What this adapter declares about itself (Build 53): scope, criticality and the largest blob it stores.
     * Read once when the adapter is registered. The default is {@link ProfileDataCriticality#BEST_EFFORT},
     * {@link ProfileDataScope#PROFILE_SCOPED}, this adapter's {@link #id()} and {@link #version()} and the
     * server's per-adapter cap, so an adapter written before Build 53 keeps its Build 52 behaviour without a
     * change. The descriptor's id and version must equal {@link #id()} and {@link #version()}; an adapter whose
     * descriptor disagrees, or whose {@code descriptor()} throws, is refused at registration with an
     * {@link IllegalArgumentException}.
     *
     * <p>{@link ProfileDataCriticality#REQUIRED} means exactly this (the semantics are enforced by the server,
     * not by the plugin): the capture must be durable before the target profile becomes active; the apply must be
     * verified before the switch is reported successful; a failure refuses the switch
     * ({@link ProfileErrorCode#ADAPTER_REQUIRED_FAILED}) or is journaled for recovery; an adapter absent from this
     * node while the profile holds its data refuses the switch ({@link ProfileErrorCode#ADAPTER_MISSING}); a skip
     * is never success. Returning {@code null} from {@link #capture} is not a failure: it means the player has no
     * data of this adapter and the stored blob is removed.</p>
     *
     * <p>{@link ProfileDataCriticality#BEST_EFFORT} means: a capture or apply failure, an over-limit blob or a
     * missing adapter is skipped with a diagnostic and the switch continues; the opaque bytes the profile already
     * holds for the adapter are kept.</p>
     *
     * <p>A server operator can raise an adapter to REQUIRED with {@code aurora.awf.multiprofile.adapters.required}
     * (a list of adapter ids); an operator cannot lower a REQUIRED adapter to BEST_EFFORT.</p>
     */
    default ProfileAdapterDescriptor descriptor() {
        return new ProfileAdapterDescriptor(id(), version(), ProfileDataScope.PROFILE_SCOPED,
            ProfileDataCriticality.BEST_EFFORT, ProfileAdapterDescriptor.MAX_BYTES_LIMIT);
    }
}
