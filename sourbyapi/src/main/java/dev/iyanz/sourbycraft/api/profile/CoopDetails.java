package dev.iyanz.sourbycraft.api.profile;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The co-op view of a profile (Build 52). {@link ProfileInfo} and {@link ProfileDetails} are unchanged;
 * this record is the additive view, returned by {@link AuroraProfiles#getCoopDetails(UUID)} and by
 * {@link AuroraProfiles#convertToCoop(UUID, UUID)}.
 *
 * <p>Build 53: every listed member can {@linkplain AuroraProfiles#switchProfile switch into} the profile and has
 * their own state in it. The record is unchanged and the member list says nothing about who is online.</p>
 *
 * @param profileId the profile
 * @param label the shared display label the owner chose, or an empty string when none was set. It is
 *     not the profile's immutable name and not any member's persisted alias; changing it changes neither.
 * @param members the members, the owner first then by join time. At most the server's
 *     {@code coop.max-members} (never more than 8).
 * @param maxMembers the server's configured co-op size cap, owner included
 */
public record CoopDetails(UUID profileId, String label, List<CoopMemberInfo> members, int maxMembers) {

    public CoopDetails {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(label, "label");
        members = List.copyOf(members);
        if (maxMembers < 2 || maxMembers > 8) throw new IllegalArgumentException("maxMembers " + maxMembers);
    }
}
