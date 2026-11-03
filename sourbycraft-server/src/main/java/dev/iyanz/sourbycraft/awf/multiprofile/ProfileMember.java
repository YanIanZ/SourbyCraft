package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.Objects;
import java.util.UUID;

/**
 * One player's proposed membership of a profile; not wired yet on 26.2.
 *
 * <p>The personal state is not stored here; this record is identity, role and contribution. The intended profile invariant
 * requires exactly one {@link ProfileRole#OWNER}, whose player id equals the profile's account id.</p>
 *
 * @param playerId the member
 * @param role what the member may do
 * @param joinedAtMillis when the member joined
 * @param lastSeenAtMillis last activity in this profile, for inactivity protection
 * @param contribution a monotonic counter the game layer may use for leaderboards (collection
 *     contribution is intended to be tracked per collection in ProfileSharedState.contributions(),
 *     which is not part of this slice)
 * @param personalBank this member's personal bank balance in minor units (hypixel: 20% of the shared
 *     capacity, 10% interest)
 */
public record ProfileMember(UUID playerId, ProfileRole role, long joinedAtMillis, long lastSeenAtMillis,
                            long contribution, long personalBank) {

    public ProfileMember {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(role, "role");
        if (joinedAtMillis < 0 || lastSeenAtMillis < 0) throw new IllegalArgumentException("member timestamps");
        if (contribution < 0) throw new IllegalArgumentException("contribution " + contribution);
        if (personalBank < 0) throw new IllegalArgumentException("personalBank " + personalBank);
    }

    /** A member with no personal bank balance (the common case for old records). */
    public ProfileMember(final UUID playerId, final ProfileRole role, final long joinedAtMillis,
                         final long lastSeenAtMillis, final long contribution) {
        this(playerId, role, joinedAtMillis, lastSeenAtMillis, contribution, 0L);
    }

    public ProfileMember seenAt(final long millis) {
        return new ProfileMember(this.playerId, this.role, this.joinedAtMillis, millis, this.contribution,
            this.personalBank);
    }

    public ProfileMember withRole(final ProfileRole next) {
        return new ProfileMember(this.playerId, next, this.joinedAtMillis, this.lastSeenAtMillis, this.contribution,
            this.personalBank);
    }

    public ProfileMember contribute(final long amount) {
        if (amount <= 0) return this;
        return new ProfileMember(this.playerId, this.role, this.joinedAtMillis, this.lastSeenAtMillis,
            this.contribution + amount, this.personalBank);
    }

    public ProfileMember withPersonalBank(final long balance) {
        return new ProfileMember(this.playerId, this.role, this.joinedAtMillis, this.lastSeenAtMillis,
            this.contribution, Math.max(0L, balance));
    }
}
