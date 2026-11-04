package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.Objects;
import java.util.Set;
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
 *
 * <p><b>Adapter contract (Build 53).</b> {@link #captureDetailed} returns the state together with what the
 * adapter registry decided for this capture ({@link ProfileStateAdapters.Capture}: best-effort keys to preserve,
 * keys cleared); {@link #carryAdapters(ProfileState, ProfileState, ProfileStateAdapters.Capture)} merges the
 * previous snapshot's entries with it; {@link #validateApply} refuses, before any apply, a state whose required
 * adapter data has no registration ({@code ADAPTER_MISSING}). A capture or apply whose required adapter fails
 * completes exceptionally with {@code ADAPTER_REQUIRED_FAILED}. The defaults keep the Build 52 behaviour for
 * ports without an adapter registry.</p>
 */
public interface PlayerStatePort {

    /** One capture: the state and the adapter registry's decisions for it. */
    record CaptureResult(ProfileState state, ProfileStateAdapters.Capture adapters) {

        public CaptureResult {
            Objects.requireNonNull(state, "state");
            adapters = adapters == null ? new ProfileStateAdapters.Capture(state.adapters(), java.util.Set.of(), java.util.Set.of())
                : adapters;
        }

        /** A result whose adapter decisions are exactly the state's entries (nothing preserved or cleared). */
        public static CaptureResult of(final ProfileState state) {
            return new CaptureResult(state, null);
        }
    }

    boolean online(UUID playerId);

    /** The player's current state for the requested categories, read on the entity thread. */
    CompletableFuture<ProfileState> capture(UUID playerId, ProfileIsolation isolation);

    /**
     * {@link #capture} with the adapter registry's decisions. This default wraps {@link #capture}: every adapter
     * entry of the state counts as captured, nothing is preserved or cleared.
     */
    default CompletableFuture<CaptureResult> captureDetailed(final UUID playerId, final ProfileIsolation isolation) {
        return capture(playerId, isolation).thenApply(CaptureResult::of);
    }

    /** Applies the categories the isolation setting enables, on the entity thread. */
    CompletableFuture<Void> apply(UUID playerId, ProfileState state, ProfileIsolation isolation);

    /**
     * {@link #apply(UUID, ProfileState, ProfileIsolation)} where the adapters named in {@code untouchedAdapterIds} are
     * not reset for data missing from {@code state} (Build 55 review P2-4: {@code state} is, or was merged from, a
     * capture in which those best-effort adapters failed, so the player holds their only copy). This default, for ports
     * without an adapter registry, applies the state as it is.
     */
    default CompletableFuture<Void> apply(final UUID playerId, final ProfileState state, final ProfileIsolation isolation,
                                          final Set<String> untouchedAdapterIds) {
        return apply(playerId, state, isolation);
    }

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

    /**
     * Refuses a state that cannot be applied completely on this node: stored data of a required adapter whose
     * version has no registration ({@code ADAPTER_MISSING}). Called on the engine's I/O worker before the apply is
     * handed to the entity thread; reads no player. This default, for ports without a registry, accepts every state.
     */
    default void validateApply(final ProfileState state) {}

    /**
     * When this server last wrote the player's vanilla playerdata file ({@code <world>/playerdata/<uuid>.dat}),
     * in epoch milliseconds, or empty when there is none or it cannot be read (Build 53 authoritative login
     * compares it with the committed head to decide an adoption). Reads only a file attribute; any thread.
     */
    default java.util.OptionalLong vanillaPlayerdataModifiedMillis(final UUID playerId) {
        return java.util.OptionalLong.empty();
    }

    /** Teleports the player; completes when the destination is loaded and the move was accepted. */
    CompletableFuture<Void> teleport(UUID playerId, ProfileState.Location location);

    /**
     * The state a snapshot stores for {@code captured}: its own adapter entries, followed by those of
     * {@code previous} (the profile's last stored state) that no adapter registered on this boot
     * accounts for, so a plugin that is missing on this boot does not lose its data at the next save.
     * Called on the engine's I/O worker; reads no player. This default has no adapter registry, so it
     * keeps every previous entry whose id the capture does not hold. Fails with {@code STATE_TOO_LARGE} when the
     * entries do not fit the snapshot's adapter budget together.
     */
    default ProfileState carryAdapters(final ProfileState captured, final ProfileState previous) {
        return captured.withAdapters(new ProfileStateAdapters().carryForward(captured.adapters(), previous.adapters()));
    }

    /**
     * {@link #carryAdapters(ProfileState, ProfileState)} honouring the capture's decisions: entries of
     * {@code previous} the capture preserved are kept even though their adapter is registered, entries it cleared
     * are dropped. This default ignores the decisions.
     */
    default ProfileState carryAdapters(final ProfileState captured, final ProfileState previous,
                                       final ProfileStateAdapters.Capture capture) {
        return carryAdapters(captured, previous);
    }
}
