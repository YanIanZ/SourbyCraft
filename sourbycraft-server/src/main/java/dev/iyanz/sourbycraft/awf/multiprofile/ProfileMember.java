package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.Objects;
import java.util.UUID;

/**
 * One member row of a profile (format 2, record type 2): identity, membership epoch, role and times.
 *
 * <p>The <b>membership epoch</b> is a random UUID allocated when the member is admitted and never
 * reused: a member that leaves and rejoins gets a new epoch, and everything personal to a membership
 * (the member's snapshot stream and {@link MemberStateHead}, personal bank balances, contributions)
 * is keyed by {@code (profile, player, epoch)}. A removed epoch's records stay as forensic data and
 * are never an admissible login source.</p>
 *
 * <p>No personal state, balance or contribution lives here: snapshots are per member head, money and
 * contributions are {@link ProfileSharedState}. A profile has exactly one {@link ProfileRole#OWNER},
 * whose player id equals {@link AuroraProfile#accountId()}. A solo profile holds the owner row only; a
 * co-op profile adds a row per accepted invite ({@link #admitted}) and drops it on leave or kick. The
 * row of a removed membership is gone and its epoch's head is {@link MemberStateHead.State#CONSUMED},
 * so no later operation can present the old epoch as a current membership.</p>
 *
 * @param playerId the member
 * @param epoch membership epoch, see above
 * @param role what the member may do
 * @param joinedAtMillis when this membership was admitted
 * @param lastSeenAtMillis last activity in this profile, for the planned inactivity rule
 */
public record ProfileMember(UUID playerId, UUID epoch, ProfileRole role, long joinedAtMillis, long lastSeenAtMillis) {

    public ProfileMember {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(role, "role");
        if (joinedAtMillis < 0 || lastSeenAtMillis < 0) throw new IllegalArgumentException("member timestamps");
    }

    /** The owner row of a new or migrated profile, with a fresh epoch. */
    public static ProfileMember owner(final UUID accountId, final long joinedAtMillis) {
        return new ProfileMember(accountId, UUID.randomUUID(), ProfileRole.OWNER, joinedAtMillis, joinedAtMillis);
    }

    /**
     * The row of a member admitted now (an accepted invite), with a fresh epoch that is never reused: a player
     * that left and rejoins gets a new one. Never {@link ProfileRole#OWNER}: a profile has exactly one owner row,
     * written at creation.
     */
    public static ProfileMember admitted(final UUID playerId, final ProfileRole role, final long joinedAtMillis) {
        if (role == ProfileRole.OWNER) throw new IllegalArgumentException("a member is admitted as MANAGER or MEMBER, never OWNER");
        return new ProfileMember(playerId, UUID.randomUUID(), role, joinedAtMillis, joinedAtMillis);
    }

    /** Whether the member was last seen {@code inactiveMillis} or longer before {@code now} (the two-member kick rule). */
    public boolean inactiveFor(final long inactiveMillis, final long now) {
        return inactiveMillis > 0 && now - this.lastSeenAtMillis >= inactiveMillis;
    }

    public ProfileMember seenAt(final long millis) {
        return new ProfileMember(this.playerId, this.epoch, this.role, this.joinedAtMillis, millis);
    }

    public ProfileMember withRole(final ProfileRole next) {
        return new ProfileMember(this.playerId, this.epoch, next, this.joinedAtMillis, this.lastSeenAtMillis);
    }

    public boolean is(final UUID playerId, final UUID epoch) {
        return this.playerId.equals(playerId) && this.epoch.equals(epoch);
    }
}
