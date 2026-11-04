package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A co-op invitation (Build 52).
 *
 * <p>Build 55 adds the invited profile's name and policy type, so an invitee's menu can show what they are invited to
 * without a second lookup. The Build 52 seven-argument constructor remains (it leaves both {@code null}); the Build 52
 * accessors are unchanged.</p>
 *
 * @param id the invitation id
 * @param profileId the co-op profile
 * @param inviter the member that issued it
 * @param invitee the invited account
 * @param createdAtMillis issue time (repository clock)
 * @param expiresAtMillis expiry (repository clock); {@code aurora.awf.multiprofile.coop.invite-expiry-minutes}
 *     after issue. Acceptance is checked against it again at commit, not only when listed.
 * @param state the current state
 * @param profileName the co-op profile's name as the server reads it when this record is built (Build 55), or
 *     {@code null} when the profile could not be read or the record was built by an older caller
 * @param profileType the co-op profile's policy type (Build 55), or {@code null} as for {@code profileName}
 */
public record CoopInviteInfo(UUID id, UUID profileId, UUID inviter, UUID invitee, long createdAtMillis,
                             long expiresAtMillis, CoopInviteState state, @Nullable String profileName,
                             @Nullable ProfileType profileType) {

    public CoopInviteInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(inviter, "inviter");
        Objects.requireNonNull(invitee, "invitee");
        Objects.requireNonNull(state, "state");
        if (createdAtMillis < 0 || expiresAtMillis <= createdAtMillis) throw new IllegalArgumentException("timestamps");
    }

    /** The Build 52 constructor: no profile name or type. */
    public CoopInviteInfo(final UUID id, final UUID profileId, final UUID inviter, final UUID invitee, final long createdAtMillis,
                          final long expiresAtMillis, final CoopInviteState state) {
        this(id, profileId, inviter, invitee, createdAtMillis, expiresAtMillis, state, null, null);
    }
}
