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
 * <p>{@link #capture} returning {@code null} means "nothing to store"; {@link #apply} is then not
 * called for that data. Bytes are the plugin's responsibility: the core stores them verbatim. The
 * core enforces a 2 MiB limit per adapter and 32 adapters per profile.</p>
 */
public interface ProfileStateAdapter {

    /** Stable id, {@code [a-z0-9_.:-]{1,64}}; never reuse an id for different data. */
    String id();

    /** Data version; bump when the format changes so a consumer can migrate its own bytes. */
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
