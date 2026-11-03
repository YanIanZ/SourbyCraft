package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;

/**
 * A player became a member of a co-op profile (Build 52).
 *
 * <p><b>Timing.</b> Fired after the membership is durable, never before and never when the commit failed or
 * its outcome is {@link ProfileErrorCode#UNKNOWN_OUTCOME}. <b>Thread.</b> This is an asynchronous event
 * ({@link #isAsynchronous()} is true): it is fired from the thread that completes the profile service's
 * storage work, which is neither a region, an entity nor the global tick thread. A handler must not touch
 * world, entity or player state directly and must return quickly; schedule such work on the right scheduler.
 * <b>Delivery.</b> Fired by the node that committed the change, once per commit it observes. It is not
 * cancellable and not a transaction: a crash between the commit and the firing loses the event, and the
 * store (listing members) is the source of truth, not the event stream.</p>
 */
public class CoopMemberJoinEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID profileId;
    private final UUID playerId;
    private final CoopRole role;
    private final UUID inviteId;

    @ApiStatus.Internal
    public CoopMemberJoinEvent(final UUID profileId, final UUID playerId, final CoopRole role, final UUID inviteId) {
        super(true);
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.role = Objects.requireNonNull(role, "role");
        this.inviteId = Objects.requireNonNull(inviteId, "inviteId");
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** The co-op profile. */
    public UUID getProfileId() {
        return this.profileId;
    }

    /** The new member. */
    public UUID getPlayerId() {
        return this.playerId;
    }

    /** The role the member joined with ({@link CoopRole#MEMBER} in Build 52). */
    public CoopRole getRole() {
        return this.role;
    }

    /** The invitation that was accepted. */
    public UUID getInviteId() {
        return this.inviteId;
    }
}
