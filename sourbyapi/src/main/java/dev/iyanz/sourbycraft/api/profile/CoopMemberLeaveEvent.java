package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.Nullable;

/**
 * A member was removed from a co-op profile (Build 52), by leaving or by a passed kick vote.
 *
 * <p>Timing, thread and delivery are those of {@link CoopMemberJoinEvent}: fired after the removal is
 * durable, asynchronously (never on a region, entity or global tick thread), by the node that committed it,
 * not cancellable, and lost if the node stops between the commit and the firing. At the time of the event the
 * membership epoch is consumed and the member's state is recorded for salvage; nothing has been delivered
 * (salvage delivery is Build 54).</p>
 *
 * <p>Build 53: a member who is online with the co-op as their active profile is captured and switched out to their
 * next profile (or a fresh solo profile) first; the removal and this event follow. The {@link Reason} is still
 * {@link Reason#LEFT} or {@link Reason#KICKED}, so a listener sees the same event as for an offline member and
 * cannot tell the two apart. The capture and switch-out complete before the removal commits, so the event never
 * precedes them.</p>
 */
public class CoopMemberLeaveEvent extends Event {

    /** Why the member is no longer a member. Constants are only appended. */
    public enum Reason {
        /** The member left. */
        LEFT,
        /** A kick vote removed the member. */
        KICKED
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID profileId;
    private final UUID playerId;
    private final Reason reason;
    private final @Nullable UUID voteId;

    @ApiStatus.Internal
    public CoopMemberLeaveEvent(final UUID profileId, final UUID playerId, final Reason reason,
                                final @Nullable UUID voteId) {
        super(true);
        this.profileId = Objects.requireNonNull(profileId, "profileId");
        this.playerId = Objects.requireNonNull(playerId, "playerId");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.voteId = voteId;
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

    /** The removed member. */
    public UUID getPlayerId() {
        return this.playerId;
    }

    public Reason getReason() {
        return this.reason;
    }

    /** The kick vote that removed the member; {@code null} for {@link Reason#LEFT}. */
    public @Nullable UUID getVoteId() {
        return this.voteId;
    }
}
