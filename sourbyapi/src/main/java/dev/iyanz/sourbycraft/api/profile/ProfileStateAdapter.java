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
 * adapter past either limit is skipped for that capture with a warning. At most 32 adapters are
 * registered, and an id whose {@code id:version} key is longer than 64 characters is refused.</p>
 *
 * <p>Data stored by an adapter that is not registered when the profile is saved again (or that is
 * registered at another version and stores nothing) is kept in the snapshot, within the same
 * limits. Data of an adapter registered at the version it was stored with is not kept for that
 * snapshot when the adapter returns {@code null}, throws or exceeds a limit.</p>
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

    /** The plugin that owns this adapter; used for the services-manager registration. */
    Plugin plugin();
}
