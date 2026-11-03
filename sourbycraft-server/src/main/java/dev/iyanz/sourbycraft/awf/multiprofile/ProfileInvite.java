package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.Objects;
import java.util.UUID;

/**
 * A pending co-op invitation value; not wired yet into the 26.2 engine or persistence.
 * One invitation per (profile, invitee), replacement on re-invite and engine expiry checks are
 * intended policy, not enforced by this record.
 */
public record ProfileInvite(UUID id, UUID profileId, UUID inviter, UUID invitee, long createdAtMillis,
                            long expiresAtMillis) {

    public ProfileInvite {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(inviter, "inviter");
        Objects.requireNonNull(invitee, "invitee");
        if (createdAtMillis < 0 || expiresAtMillis <= createdAtMillis) {
            throw new IllegalArgumentException("invite timestamps");
        }
    }

    public boolean expired(final long now) {
        return now >= this.expiresAtMillis;
    }
}
