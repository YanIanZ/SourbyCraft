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
 * <p>The electorate is frozen at creation: every current non-target member (OWNER included) with its
 * epoch. The member that opens the vote approves implicitly (its approval is in the record from
 * revision 0), so in a two-member co-op the owner's vote passes at once; the two-member case is in
 * addition gated by the inactivity rule the operation builder applies ({@link ProfileMember#inactiveFor}).
 * Approval is unanimous: {@link #passed} reports the condition and removes nobody. Execution is a
 * membership-removal intent whose vector compares this record and the profile's {@code M}; it refuses
 * when {@link #electorateCurrent} is false (a membership or role change since the vote opened) and the
 * vote is then {@link Status#CANCELLED}. One open vote per profile (the by-profile index caps at 1).</p>
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
    /** Open votes per profile (COOP-DESIGN §9). */
    public static final int MAX_OPEN_PER_PROFILE = 1;

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

    /**
     * A new vote at revision 0 against {@code target} in {@code profile}, opened by {@code initiator} (whose approval
     * is implicit). The electorate is every current member but the target, with their epochs. The vote is
     * {@link Status#PASSED} from the start when the initiator is the whole electorate.
     */
    public static KickVote open(final AuroraProfile profile, final ProfileMember target, final ProfileMember initiator, final long now,
                                final long lifetimeMillis) {
        if (lifetimeMillis <= 0) throw new IllegalArgumentException("vote lifetime");
        if (target.playerId().equals(initiator.playerId())) throw new IllegalArgumentException("a member cannot open a vote against itself");
        final Map<UUID, UUID> electorate = new LinkedHashMap<>();
        for (final ProfileMember member : profile.members()) {
            if (!member.playerId().equals(target.playerId())) electorate.put(member.playerId(), member.epoch());
        }
        final Map<UUID, Long> approvals = new LinkedHashMap<>();
        approvals.put(initiator.playerId(), now);
        final boolean unanimous = approvals.keySet().containsAll(electorate.keySet());
        return new KickVote(UUID.randomUUID(), 0L, profile.id(), target.playerId(), target.epoch(), electorate, approvals, now,
            Math.addExact(now, lifetimeMillis), unanimous ? Status.PASSED : Status.OPEN, null);
    }

    public boolean expired(final long now) {
        return now >= this.expiresAtMillis;
    }

    public boolean open() {
        return this.status == Status.OPEN;
    }

    /** Whether the vote still waits for a decision (OPEN or PASSED but not executed). */
    public boolean undecided() {
        return this.status == Status.OPEN || this.status == Status.PASSED;
    }

    /** Whether every eligible voter approved (unanimous policy). */
    public boolean passed() {
        return !this.electorate.isEmpty() && this.approvals.keySet().containsAll(this.electorate.keySet());
    }

    /** Whether {@code (voter, epoch)} is in the frozen electorate. */
    public boolean eligible(final UUID voter, final UUID epoch) {
        return epoch != null && epoch.equals(this.electorate.get(voter));
    }

    /** Whether the frozen electorate still equals the profile's current non-target members and epochs. */
    public boolean electorateCurrent(final List<ProfileMember> members) {
        int counted = 0;
        boolean targetSeen = false;
        for (final ProfileMember member : members) {
            if (member.playerId().equals(this.target)) {
                if (!member.epoch().equals(this.targetEpoch)) return false;
                targetSeen = true;
                continue;
            }
            if (!member.epoch().equals(this.electorate.get(member.playerId()))) return false;
            counted++;
        }
        return targetSeen && counted == this.electorate.size();
    }

    /**
     * The record with {@code voter}'s approval at revision + 1; {@link Status#PASSED} when it was the last one.
     * Refuses a voter outside the electorate or a vote that is not OPEN.
     */
    public KickVote approved(final UUID voter, final UUID voterEpoch, final long now) {
        if (!open()) throw new IllegalStateException("vote " + this.id + " is " + this.status + ", not OPEN");
        if (!eligible(voter, voterEpoch)) throw new IllegalArgumentException(voter + "/" + voterEpoch + " is not an eligible voter");
        final Map<UUID, Long> next = new LinkedHashMap<>(this.approvals);
        next.putIfAbsent(voter, now);
        final boolean unanimous = next.keySet().containsAll(this.electorate.keySet());
        return new KickVote(this.id, CoopWriteGuard.next(this.revision, "vote " + this.id), this.profileId, this.target, this.targetEpoch,
            this.electorate, next, this.createdAtMillis, this.expiresAtMillis, unanimous ? Status.PASSED : Status.OPEN, null);
    }

    /** The record closed as DECLINED, CANCELLED or EXPIRED at revision + 1 (from OPEN or PASSED). */
    public KickVote closed(final Status next) {
        if (next != Status.DECLINED && next != Status.CANCELLED && next != Status.EXPIRED) {
            throw new IllegalArgumentException("closed() takes DECLINED, CANCELLED or EXPIRED");
        }
        if (!undecided()) throw new IllegalStateException("vote " + this.id + " is " + this.status + " and cannot close");
        return new KickVote(this.id, CoopWriteGuard.next(this.revision, "vote " + this.id), this.profileId, this.target, this.targetEpoch,
            this.electorate, this.approvals, this.createdAtMillis, this.expiresAtMillis, next, null);
    }

    /** The EXECUTED record at revision + 1, naming the removal intent; requires {@link #passed}. */
    public KickVote executed(final UUID removalIntentId) {
        if (!undecided() || !passed()) throw new IllegalStateException("vote " + this.id + " has not passed (" + this.status + ")");
        return new KickVote(this.id, CoopWriteGuard.next(this.revision, "vote " + this.id), this.profileId, this.target, this.targetEpoch,
            this.electorate, this.approvals, this.createdAtMillis, this.expiresAtMillis, Status.EXECUTED,
            Objects.requireNonNull(removalIntentId, "removalIntentId"));
    }

    /** Vote statuses with stable numeric tags. */
    public enum Status {
        OPEN(1),
        /** Every eligible voter approved; execution pending. */
        PASSED(2),
        /** The removal intent committed. */
        EXECUTED(3),
        /** An eligible voter declined. */
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

        /** Whether a vote in this status occupies the profile's single open-vote slot. */
        public boolean occupiesSlot() {
            return this == OPEN || this == PASSED;
        }

        public static Status fromTag(final int tag, final String where) throws IOException {
            for (final Status status : values()) {
                if (status.tag == tag) return status;
            }
            throw new IOException("vote status tag " + tag + " in " + where + " is not known to this build");
        }
    }
}
