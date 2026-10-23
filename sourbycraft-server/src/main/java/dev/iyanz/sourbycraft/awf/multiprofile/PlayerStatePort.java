package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Captures and applies player state on the owning entity thread.
 *
 * <p>Implementations must run every read and write of a Bukkit {@code Player} inside
 * {@code EntityScheduler}, never on the caller's thread. The futures complete after that task ran;
 * they must not be joined on a region/entity/global thread. {@link #capture} is the "freeze" step:
 * the whole read happens in one entity task, so gameplay cannot interleave with it.</p>
 *
 * <p>The core never touches Bukkit, which is what makes the switch protocol testable with a fake
 * port.</p>
 */
public interface PlayerStatePort {

    boolean online(UUID playerId);

    /** The player's current state for the requested categories, read on the entity thread. */
    CompletableFuture<ProfileState> capture(UUID playerId, ProfileIsolation isolation);

    /** Applies the categories the isolation setting enables, on the entity thread. */
    CompletableFuture<Void> apply(UUID playerId, ProfileState state, ProfileIsolation isolation);

    /** Teleports the player; completes when the destination is loaded and the move was accepted. */
    CompletableFuture<Void> teleport(UUID playerId, ProfileState.Location location);

    /** A short reason why {@link #capture} or {@link #apply} cannot run, or {@code null}. */
    default String unavailable(final UUID playerId) {
        return online(playerId) ? null : "the player is not online";
    }
}
