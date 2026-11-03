package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;
import java.util.UUID;

/**
 * A co-op invitation (Build 52).
 *
 * @param id the invitation id
 * @param profileId the co-op profile
 * @param inviter the member that issued it
 * @param invitee the invited account
 * @param createdAtMillis issue time (repository clock)
 * @param expiresAtMillis expiry (repository clock); {@code aurora.awf.multiprofile.coop.invite-expiry-minutes}
 *     after issue. Acceptance is checked against it again at commit, not only when listed.
 * @param state the current state
 */
public record CoopInviteInfo(UUID id, UUID profileId, UUID inviter, UUID invitee, long createdAtMillis,
                             long expiresAtMillis, CoopInviteState state) {

    public CoopInviteInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(inviter, "inviter");
        Objects.requireNonNull(invitee, "invitee");
        Objects.requireNonNull(state, "state");
        if (createdAtMillis < 0 || expiresAtMillis <= createdAtMillis) throw new IllegalArgumentException("timestamps");
    }
}
