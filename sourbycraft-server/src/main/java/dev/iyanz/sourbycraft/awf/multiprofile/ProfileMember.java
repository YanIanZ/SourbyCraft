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
 * whose player id equals {@link AuroraProfile#accountId()}. Build 51 stores one owner row per profile;
 * membership changes are Build 52.</p>
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
