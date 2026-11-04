package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;

/**
 * A co-op invitation was durably created, declined or cancelled (Build 52); read {@link #getInvite()}
 * {@code .state()} to tell which ({@link CoopInviteState#OPEN} on creation).
 *
 * <p>Not fired for an acceptance (that is {@link CoopMemberJoinEvent}) and not fired for expiry: an
 * invitation expires by the clock and is judged at the next commit or listing, so no event marks the instant.
 * Timing, thread and delivery are those of {@link CoopMemberJoinEvent}: after the commit, asynchronously (never
 * on a region, entity or global tick thread), by the node that committed it, not cancellable, and lost if the
 * node stops between the commit and the firing. A plugin that wants to tell the invitee "you were invited"
 * must hand the message to the invitee's entity scheduler itself; the invitee may be offline or on another
 * node, and the service does not message anyone.</p>
 */
public class CoopInviteEvent extends Event {

    private static final HandlerList HANDLERS = new HandlerList();

    private final CoopInviteInfo invite;

    @ApiStatus.Internal
    public CoopInviteEvent(final CoopInviteInfo invite) {
        super(true);
        this.invite = Objects.requireNonNull(invite, "invite");
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** The invitation as committed. */
    public CoopInviteInfo getInvite() {
        return this.invite;
    }
}
