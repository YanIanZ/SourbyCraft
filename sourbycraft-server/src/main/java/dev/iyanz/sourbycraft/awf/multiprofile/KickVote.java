package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * A kick vote record (format-2 record type 8).
 *
 * <p>Build 51 defines the record so both repositories store and decode it; no command or API opens or
 * casts a vote before Build 52. The electorate is frozen at creation: every current non-target member
 * (OWNER included) with its epoch; a membership or role change cancels the vote. Approval is unanimous
 * by intended policy; {@link #passed} reports the condition and removes nobody — execution is a
 * membership-removal intent that records {@code removalIntentId} here.</p>
 *
 * @param id vote id
 * @param revision the record's own revision
 * @param profileId the profile
 * @param target the member to remove
 * @param targetEpoch the target's membership epoch
 * @param electorate the frozen eligible voters, player to epoch
 * @param approvals approving voter to approval time
 * @param createdAtMillis open time
 * @param expiresAtMillis expiry (commit-time authoritative)
 * @param status the vote status
 * @param removalIntentId the removal operation, or {@code null} unless {@link Status#EXECUTED}
 */
public record KickVote(UUID id, long revision, UUID profileId, UUID target, UUID targetEpoch, Map<UUID, UUID> electorate,
                       Map<UUID, Long> approvals, long createdAtMillis, long expiresAtMillis, Status status,
                       UUID removalIntentId) {

    /** Eligible voters: every member but the target. */
    public static final int MAX_ELECTORATE = AuroraProfile.MAX_MEMBERS - 1;

    public KickVote {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(targetEpoch, "targetEpoch");
        Objects.requireNonNull(status, "status");
        CoopWriteGuard.counter(revision, "vote revision");
        electorate = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(electorate == null ? Map.of() : electorate));
        approvals = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(approvals == null ? Map.of() : approvals));
        if (electorate.containsKey(target)) throw new IllegalArgumentException("the target is not an eligible voter");
        if (electorate.size() > MAX_ELECTORATE) throw new IllegalArgumentException("electorate too large");
        for (final UUID voter : approvals.keySet()) {
            if (!electorate.containsKey(voter)) throw new IllegalArgumentException("approval by a non-eligible voter " + voter);
        }
        if (createdAtMillis < 0 || expiresAtMillis <= createdAtMillis) throw new IllegalArgumentException("vote timestamps");
        if ((status == Status.EXECUTED) != (removalIntentId != null)) {
            throw new IllegalArgumentException("only an EXECUTED vote names its removal intent");
        }
    }

    public boolean expired(final long now) {
        return now >= this.expiresAtMillis;
    }

    public boolean open() {
        return this.status == Status.OPEN;
    }

    /** Whether every eligible voter approved (unanimous policy). */
    public boolean passed() {
        return !this.electorate.isEmpty() && this.approvals.keySet().containsAll(this.electorate.keySet());
    }

    /** Whether the frozen electorate still equals the profile's current non-target members and epochs. */
    public boolean electorateCurrent(final List<ProfileMember> members) {
        int counted = 0;
        for (final ProfileMember member : members) {
            if (member.playerId().equals(this.target)) {
                if (!member.epoch().equals(this.targetEpoch)) return false;
                continue;
            }
            if (!member.epoch().equals(this.electorate.get(member.playerId()))) return false;
            counted++;
        }
        return counted == this.electorate.size();
    }

    /** Vote statuses with stable numeric tags. */
    public enum Status {
        OPEN(1),
        /** Every eligible voter approved; execution pending. */
        PASSED(2),
        /** The removal intent committed. */
        EXECUTED(3),
        DECLINED(4),
        /** Membership or role changed, or the target left. */
        CANCELLED(5),
        EXPIRED(6);

        private final int tag;

        Status(final int tag) {
            this.tag = tag;
        }

        public int tag() {
            return this.tag;
        }

        public static Status fromTag(final int tag, final String where) throws IOException {
            for (final Status status : values()) {
                if (status.tag == tag) return status;
            }
            throw new IOException("vote status tag " + tag + " in " + where + " is not known to this build");
        }
    }
}
