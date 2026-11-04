package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Added in Build 54. An outstanding salvage record of a co-op profile: the isolated items (inventory, armour,
 * off-hand, ender chest) a removed membership left behind, waiting to be {@linkplain AuroraProfiles#claimSalvage claimed}.
 * Never carries the items themselves.
 *
 * @param salvageId the record, the argument of {@link AuroraProfiles#claimSalvage}
 * @param profileId the co-op profile
 * @param removedMemberId the player whose membership was removed
 * @param createdAtMillis when the membership was removed (store clock)
 * @param partial whether an earlier claim already delivered part of it (the rest did not fit)
 */
@NullMarked
public record ProfileSalvageInfo(UUID salvageId, UUID profileId, UUID removedMemberId, long createdAtMillis, boolean partial) {

    public ProfileSalvageInfo {
        Objects.requireNonNull(salvageId, "salvageId");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(removedMemberId, "removedMemberId");
    }
}
