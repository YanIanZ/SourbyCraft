package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * The head of one member's snapshot stream ({@code R(profile, member, epoch)}, format-2 record type 11).
 *
 * <p>A head is an independent record, so publishing one member's snapshot rewrites nothing of the
 * profile metadata and nothing of another member. {@code revision} is {@code R}: it advances exactly
 * once per published snapshot, initialisation, consumption or delivery into this stream, and never for
 * metadata or shared-state changes. The head names its authoritative snapshot <em>exactly</em> — id,
 * revision and checksum — and {@link #matches} refuses a snapshot that differs in any of the three,
 * older or newer: "latest" is never substituted for the named one.</p>
 *
 * <p>{@link State#UNINITIALIZED}: the membership exists but no state was ever published; login then
 * initialises an empty state under CAS, it does not capture the player's current inventory into the
 * new profile. {@link State#READY}: {@code snapshotId} is the state to restore. {@link State#CONSUMED}:
 * the membership was removed and its items extracted by {@code consumingOperationId}; the stream is
 * forensic data and never an admissible login source. A rejoin allocates a new epoch and a new head.</p>
 *
 * @param profileId the profile
 * @param memberId the member
 * @param epoch the membership epoch
 * @param revision {@code R}
 * @param state the head state
 * @param snapshotId the exact authoritative snapshot, or {@code null} unless {@link State#READY}
 * @param snapshotChecksum its state checksum, or {@code null} unless {@link State#READY}
 * @param consumingOperationId the removal operation, or {@code null} unless {@link State#CONSUMED}
 * @param pendingIntent operation holding a reservation on this head, or {@code null}
 */
public record MemberStateHead(UUID profileId, UUID memberId, UUID epoch, long revision, State state, UUID snapshotId,
                              String snapshotChecksum, UUID consumingOperationId, UUID pendingIntent) {

    public MemberStateHead {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(epoch, "epoch");
        Objects.requireNonNull(state, "state");
        CoopWriteGuard.counter(revision, "head revision");
        if (state == State.READY) {
            Objects.requireNonNull(snapshotId, "a READY head names its snapshot");
            Objects.requireNonNull(snapshotChecksum, "a READY head names its snapshot checksum");
        } else if (snapshotId != null || snapshotChecksum != null) {
            throw new IllegalArgumentException("only a READY head names a snapshot");
        }
        if ((state == State.CONSUMED) != (consumingOperationId != null)) {
            throw new IllegalArgumentException("a CONSUMED head names its consuming operation, no other head does");
        }
    }

    /** The head of a new membership: revision 0, nothing published. */
    public static MemberStateHead uninitialized(final UUID profileId, final UUID memberId, final UUID epoch) {
        return new MemberStateHead(profileId, memberId, epoch, 0L, State.UNINITIALIZED, null, null, null, null);
    }

    /**
     * The head after publishing {@code snapshot} into this stream: {@code R + 1}, READY, naming it
     * exactly. The snapshot must carry the new revision and belong to this stream.
     */
    public MemberStateHead published(final ProfileSnapshot snapshot) {
        final long next = CoopWriteGuard.next(this.revision, "head " + describe());
        if (!snapshot.inStream(this.profileId, this.memberId, this.epoch)) {
            throw new IllegalArgumentException("snapshot " + snapshot.snapshotId() + " is not in stream " + describe());
        }
        if (snapshot.revision() != next) {
            throw new IllegalArgumentException("snapshot " + snapshot.snapshotId() + " is revision " + snapshot.revision()
                + ", the head publishes " + next);
        }
        if (this.state == State.CONSUMED) throw new IllegalStateException("stream " + describe() + " is consumed");
        return new MemberStateHead(this.profileId, this.memberId, this.epoch, next, State.READY, snapshot.snapshotId(),
            snapshot.checksum(), null, this.pendingIntent);
    }

    /** The head after its membership was removed and its items extracted by {@code operationId}. */
    public MemberStateHead consumed(final UUID operationId) {
        return new MemberStateHead(this.profileId, this.memberId, this.epoch,
            CoopWriteGuard.next(this.revision, "head " + describe()), State.CONSUMED, null, null,
            Objects.requireNonNull(operationId, "operationId"), this.pendingIntent);
    }

    /** The same head at an explicit revision (after-images: the revision COMMIT installs, after the reservation). */
    public MemberStateHead atRevision(final long revision) {
        return new MemberStateHead(this.profileId, this.memberId, this.epoch, revision, this.state, this.snapshotId,
            this.snapshotChecksum, this.consumingOperationId, this.pendingIntent);
    }

    /** Installs or clears ({@code null}) the reservation of an intent; a committed mutation, {@code R} advances. */
    public MemberStateHead withPendingIntent(final UUID operationId) {
        return new MemberStateHead(this.profileId, this.memberId, this.epoch,
            CoopWriteGuard.next(this.revision, "head " + describe()), this.state, this.snapshotId, this.snapshotChecksum,
            this.consumingOperationId, operationId);
    }

    /**
     * Whether {@code snapshot} is exactly the state this head names: same stream, same id, same
     * revision and same checksum. A READY head with any difference, and any non-READY head, is
     * {@code false}; callers then fail closed instead of choosing another snapshot.
     */
    public boolean matches(final ProfileSnapshot snapshot) {
        return this.state == State.READY && snapshot != null && snapshot.inStream(this.profileId, this.memberId, this.epoch)
            && this.snapshotId.equals(snapshot.snapshotId()) && this.revision == snapshot.revision()
            && this.snapshotChecksum.equals(snapshot.checksum());
    }

    /** Whether a login may restore from this head (READY only). */
    public boolean restorable() {
        return this.state == State.READY;
    }

    public String describe() {
        return this.profileId + "/" + this.memberId + "/" + this.epoch;
    }

    /** Head states, with stable numeric tags for the record format. */
    public enum State {
        /** Membership exists; no state published yet. */
        UNINITIALIZED(1),
        /** {@code snapshotId} is the authoritative state. */
        READY(2),
        /** Removed and extracted; never restored. */
        CONSUMED(3);

        private final int tag;

        State(final int tag) {
            this.tag = tag;
        }

        public int tag() {
            return this.tag;
        }

        public static State fromTag(final int tag, final String where) throws IOException {
            for (final State state : values()) {
                if (state.tag == tag) return state;
            }
            throw new IOException("member head state tag " + tag + " in " + where + " is not known to this build");
        }
    }
}
