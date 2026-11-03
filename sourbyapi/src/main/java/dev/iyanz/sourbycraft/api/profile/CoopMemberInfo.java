package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;
import java.util.UUID;

/**
 * One member of a co-op profile (Build 52).
 *
 * @param playerId the member's account id
 * @param role the member's role
 * @param joinedAtMillis when the membership was created (epoch milliseconds, repository clock)
 * @param lastSeenAtMillis the member's last recorded activity in this profile. Build 52 records the join
 *     time and updates it when the member's membership changes; it is the value the two-member inactivity
 *     rule reads, and it is not updated by gameplay.
 */
public record CoopMemberInfo(UUID playerId, CoopRole role, long joinedAtMillis, long lastSeenAtMillis) {

    public CoopMemberInfo {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(role, "role");
        if (joinedAtMillis < 0 || lastSeenAtMillis < 0) throw new IllegalArgumentException("negative timestamp");
    }
}
