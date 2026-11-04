package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;

/**
 * Added in Build 54. A salvage claim committed: items of a removed membership moved into a member's committed state
 * ({@link #getResult()}).
 *
 * <p><b>Timing.</b> Fired after the delivery is durable (the claimant's state head, the salvage record and the permanent
 * receipt committed together), before or without the apply to the online player: {@link SalvageDeliveryResult#applied()}
 * is {@code true} in this event even when the apply later fails (the {@link AuroraProfiles#claimSalvage} result reports
 * that). Never fired when the claim was refused or its outcome is {@link ProfileErrorCode#UNKNOWN_OUTCOME}.
 * <b>Thread.</b> Asynchronous, on the profile service's co-op event thread (no region, entity or global tick thread);
 * a handler must not touch world, entity or player state directly. <b>Delivery.</b> Fired once per commit by the node
 * that committed it; not cancellable and not a transaction (a crash after the commit loses the event; the salvage record
 * and its receipt are the source of truth).</p>
 */
public class ProfileSalvageDeliveredEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final SalvageDeliveryResult result;

    @ApiStatus.Internal
    public ProfileSalvageDeliveredEvent(final SalvageDeliveryResult result) {
        super(true);
        this.result = Objects.requireNonNull(result, "result");
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** What was delivered. */
    public SalvageDeliveryResult getResult() {
        return this.result;
    }
}
