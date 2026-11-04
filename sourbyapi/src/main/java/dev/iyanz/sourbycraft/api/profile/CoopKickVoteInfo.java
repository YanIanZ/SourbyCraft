package dev.iyanz.sourbycraft.api.profile;

import java.util.Objects;
import java.util.UUID;

/**
 * A co-op kick vote (Build 52).
 *
 * <p>The electorate is every member of the profile except the target, the owner included, frozen when the
 * vote opens. Approval must be unanimous. The starter's approval is counted when the vote opens, so a vote
 * the owner starts already carries the owner's approval (the owner's implicit vote); a vote a manager
 * starts still needs the owner's approval. A target never votes and a target that is the owner is refused.
 * In a two-member co-op the target must also have been inactive for at least
 * {@code coop.kick-vote.inactive-days}, else the start is refused with
 * {@link ProfileErrorCode#TARGET_NOT_INACTIVE}. Any membership or role change cancels an open vote
 * ({@link ProfileErrorCode#ELECTORATE_CHANGED}).</p>
 *
 * @param id the vote id
 * @param profileId the co-op profile
 * @param target the member to remove. The owner can never be a target.
 * @param votes approvals cast so far
 * @param electorateSize eligible voters; the vote passes when {@code votes == electorateSize}
 * @param createdAtMillis open time (repository clock)
 * @param expiresAtMillis expiry (repository clock); {@code coop.kick-vote.expiry-minutes} after opening
 * @param state the current state
 */
public record CoopKickVoteInfo(UUID id, UUID profileId, UUID target, int votes, int electorateSize,
                               long createdAtMillis, long expiresAtMillis, CoopKickVoteState state) {

    public CoopKickVoteInfo {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(state, "state");
        if (votes < 0 || electorateSize < 0 || votes > electorateSize) throw new IllegalArgumentException("vote counts");
        if (createdAtMillis < 0 || expiresAtMillis <= createdAtMillis) throw new IllegalArgumentException("timestamps");
    }
}
