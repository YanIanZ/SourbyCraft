package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * An open vote value for removing a member; not wired yet into the 26.2 profile engine.
 * The map holds one entry per voter and rejects the target. The threshold and expiry helpers
 * report their conditions independently; neither removes a member.
 */
public record KickVote(UUID profileId, UUID target, Map<UUID, Long> votes, long createdAtMillis,
                       long expiresAtMillis) {

    public KickVote {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(target, "target");
        votes = votes == null ? Map.of() : Map.copyOf(votes);
        if (votes.containsKey(target)) throw new IllegalArgumentException("the target cannot vote");
        if (createdAtMillis < 0 || expiresAtMillis <= createdAtMillis) {
            throw new IllegalArgumentException("vote timestamps");
        }
    }

    public boolean expired(final long now) {
        return now >= this.expiresAtMillis;
    }

    public KickVote withVote(final UUID member, final long now) {
        final java.util.Map<UUID, Long> next = new java.util.LinkedHashMap<>(this.votes);
        next.put(member, now);
        return new KickVote(this.profileId, this.target, next, this.createdAtMillis, this.expiresAtMillis);
    }

    public boolean passed(final int eligibleVoters) {
        return this.votes.size() >= Math.max(1, eligibleVoters);
    }
}
