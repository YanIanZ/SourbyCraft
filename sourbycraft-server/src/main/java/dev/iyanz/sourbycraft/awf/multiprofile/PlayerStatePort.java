package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

/**
 * Captures and applies player state on the owning entity thread.
 *
 * <p>Implementations must run every read and write of a Bukkit {@code Player} inside
 * {@code EntityScheduler}, never on the caller's thread. The futures complete after that task ran;
 * they must not be joined on a region/entity/global thread. A capture or an apply is one entity task,
 * so a tick cannot interleave with half of it; what the player does between a switch's capture and
 * its apply is held back by the runtime's freeze listener, not by this port.</p>
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

    /**
     * Applies like {@link #apply(UUID, ProfileState, ProfileIsolation)}, except that the entity task
     * first asks {@code abandoned} and, when it answers {@code true}, leaves the player untouched and
     * fails the future. A switch sets it once it failed or timed out, so an apply still waiting for a
     * stalled entity thread does not run after the switch gave up. This default asks once, on the
     * calling thread, for ports that have no entity task of their own.
     */
    default CompletableFuture<Void> apply(final UUID playerId, final ProfileState state, final ProfileIsolation isolation,
                                          final BooleanSupplier abandoned) {
        if (abandoned.getAsBoolean()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "the profile switch was abandoned before its apply ran"));
        }
        return apply(playerId, state, isolation);
    }

    /** Teleports the player; completes when the destination is loaded and the move was accepted. */
    CompletableFuture<Void> teleport(UUID playerId, ProfileState.Location location);

    /**
     * The state a snapshot stores for {@code captured}: its own adapter entries, followed by those of
     * {@code previous} (the profile's last stored state) that no adapter registered on this boot
     * accounts for, so a plugin that is missing on this boot does not lose its data at the next save.
     * Called on the engine's I/O worker; reads no player. This default has no adapter registry, so it
     * keeps every previous entry whose id the capture does not hold.
     */
    default ProfileState carryAdapters(final ProfileState captured, final ProfileState previous) {
        return captured.withAdapters(new ProfileStateAdapters().carryForward(captured.adapters(), previous.adapters()));
    }
}
