package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * A co-op invitation record (format-2 record type 7).
 *
 * <p>Build 51 defines the record so both repositories store and decode it; no command or API creates,
 * accepts or revokes an invite before Build 52. Expiry is checked at commit time against the
 * repository's clock, not only when listing; an accepted invite records the epoch it allocated so a
 * replayed accept returns the same epoch.</p>
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
    }

    public boolean expired(final long now) {
        return now >= this.expiresAtMillis;
    }

    public boolean open() {
        return this.status == Status.OPEN;
    }

    /** Invite statuses with stable numeric tags. */
    public enum Status {
        OPEN(1),
        ACCEPTED(2),
        DECLINED(3),
        REVOKED(4),
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
