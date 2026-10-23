package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.Objects;
import java.util.UUID;

/**
 * A live claim on a profile by one node.
 *
 * <p>{@code epoch} changes whenever the claim is taken over. It is deliberately not derived from a
 * TTL: an expired claim is reclaimed with a higher epoch, so a node that was frozen and resumes
 * writing cannot pass a validation that compares epochs. {@code expiresAtMillis} is advisory (it
 * comes from the claim service's clock) and must never be used as the only check.</p>
 */
public record ProfileSession(UUID profileId, String nodeId, UUID ownerToken, long epoch, long expiresAtMillis,
                             String state) {

    public ProfileSession {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(ownerToken, "ownerToken");
        if (epoch < 1) throw new IllegalArgumentException("epoch " + epoch);
        if (state == null || state.isEmpty()) state = "ACTIVE";
    }

    public boolean sameAs(final ProfileSession other) {
        return other != null && this.profileId.equals(other.profileId) && this.nodeId.equals(other.nodeId)
            && this.ownerToken.equals(other.ownerToken) && this.epoch == other.epoch;
    }
}
