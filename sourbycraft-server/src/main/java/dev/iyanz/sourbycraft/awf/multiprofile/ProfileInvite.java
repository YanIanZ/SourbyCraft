package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * A co-op invitation record (format-2 record type 7).
 *
 * <p>The record has its own revision and no reservation field: an operation that consumes it names it in
 * its version vector as a compare-only entry (expected revision {@code N}, after-image at {@code N + 1}),
 * and the repository refuses the commit when the stored revision moved. Expiry is checked at commit
 * time against the repository's clock ({@link #expired}; the accepting intent's deadline is the invite's
 * expiry, so a commit after it is refused), not only when listing. An accepted invite records the epoch
 * it allocated so a replayed accept returns the same epoch. Indexes list <em>open</em> invites only
 * (by profile and by invitee, at most 64 each); a closed invite leaves both indexes in the same commit
 * and keeps its record.</p>
 *
 * @param id invite id
 * @param revision the record's own revision
 * @param profileId the profile
 * @param inviter the inviting member
 * @param inviterEpoch the inviter's membership epoch when the invite was issued
 * @param invitee the invited account
 * @param createdAtMillis issue time
 * @param expiresAtMillis expiry (commit-time authoritative)
 * @param status the invite status
 * @param acceptedEpoch the membership epoch an accept allocated, or {@code null}
 * @param operationId the operation that produced this revision
 */
public record ProfileInvite(UUID id, long revision, UUID profileId, UUID inviter, UUID inviterEpoch, UUID invitee,
                            long createdAtMillis, long expiresAtMillis, Status status, UUID acceptedEpoch, UUID operationId) {

    /** Open invites per profile and per invitee (COOP-DESIGN §9). */
    public static final int MAX_OPEN = 64;

    public ProfileInvite {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(inviter, "inviter");
        Objects.requireNonNull(inviterEpoch, "inviterEpoch");
        Objects.requireNonNull(invitee, "invitee");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(operationId, "operationId");
        CoopWriteGuard.counter(revision, "invite revision");
        if (createdAtMillis < 0 || expiresAtMillis <= createdAtMillis) throw new IllegalArgumentException("invite timestamps");
        if ((status == Status.ACCEPTED) != (acceptedEpoch != null)) {
            throw new IllegalArgumentException("only an ACCEPTED invite names the allocated epoch");
        }
        if (inviter.equals(invitee)) throw new IllegalArgumentException("a member cannot invite itself");
    }

    /** A new OPEN invite at revision 0, issued by {@code inviter} (a current member row) through {@code operationId}. */
    public static ProfileInvite open(final UUID profileId, final ProfileMember inviter, final UUID invitee, final long now,
                                     final long lifetimeMillis, final UUID operationId) {
        if (lifetimeMillis <= 0) throw new IllegalArgumentException("invite lifetime");
        return new ProfileInvite(UUID.randomUUID(), 0L, profileId, inviter.playerId(), inviter.epoch(), invitee, now,
            Math.addExact(now, lifetimeMillis), Status.OPEN, null, operationId);
    }

    public boolean expired(final long now) {
        return now >= this.expiresAtMillis;
    }

    public boolean open() {
        return this.status == Status.OPEN;
    }

    /** Whether this is an open, unexpired invitation of {@code invitee} to {@code profileId}. */
    public boolean openFor(final UUID invitee, final UUID profileId, final long now) {
        return open() && !expired(now) && this.invitee.equals(invitee) && this.profileId.equals(profileId);
    }

    /** The ACCEPTED record at revision + 1, naming the allocated epoch and the accepting operation. */
    public ProfileInvite accepted(final UUID epoch, final UUID operationId) {
        return closed(Status.ACCEPTED, Objects.requireNonNull(epoch, "epoch"), operationId);
    }

    /** The record closed as DECLINED, REVOKED or EXPIRED at revision + 1. */
    public ProfileInvite closed(final Status next, final UUID operationId) {
        if (next == Status.OPEN || next == Status.ACCEPTED) throw new IllegalArgumentException("closed() takes DECLINED, REVOKED or EXPIRED");
        return closed(next, null, operationId);
    }

    private ProfileInvite closed(final Status next, final UUID epoch, final UUID operationId) {
        if (!open()) throw new IllegalStateException("invite " + this.id + " is " + this.status + ", not OPEN");
        return new ProfileInvite(this.id, CoopWriteGuard.next(this.revision, "invite " + this.id), this.profileId, this.inviter,
            this.inviterEpoch, this.invitee, this.createdAtMillis, this.expiresAtMillis, next, epoch,
            Objects.requireNonNull(operationId, "operationId"));
    }

    /** Invite statuses with stable numeric tags. */
    public enum Status {
        OPEN(1),
        ACCEPTED(2),
        /** Closed by the invitee. */
        DECLINED(3),
        /** Closed by the inviter, an OWNER or a MANAGER. */
        REVOKED(4),
        /** Closed by a cleanup after its expiry passed. */
        EXPIRED(5);

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
            throw new IOException("invite status tag " + tag + " in " + where + " is not known to this build");
        }
    }
}
