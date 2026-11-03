package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * A salvage record (format-2 record type 9): what a removed membership left behind, consumed from its
 * source stream and awaiting exactly one delivery into a claimant's head.
 *
 * <p>Build 52 writes salvage in state {@link State#UNAVAILABLE}: the membership-removal intent (leave or
 * executed kick) records the removed member and epoch, the removal operation and the exact source
 * snapshot its head named (id, {@code R} and checksum; {@link #NIL} and empty when the head never
 * published one), with no item payload, because item extraction and delivery are Build 54 (owner
 * decision OD-3). Such a record is never offered to a claimant; it is the durable evidence that the
 * epoch was consumed and that its last snapshot is the one a later extraction may read. It counts
 * toward the per-profile salvage bound (64 outstanding; a removal that would exceed it is refused before
 * anything is consumed). A Build 51 binary refuses an UNAVAILABLE record (unknown state tag 3) rather
 * than misread it.</p>
 *
 * <p>{@link State#AVAILABLE} and {@link State#DELIVERED} keep the Build 51 shape: the item payload is an
 * immutable blob named by length and SHA-256, never embedded here; DELIVERED names the exact destination
 * snapshot and the delivery operation, so a replay returns the receipt and never re-grants.</p>
 *
 * @param id salvage id
 * @param revision the record's own revision
 * @param profileId the profile
 * @param removedMember the removed member
 * @param removedEpoch its consumed membership epoch
 * @param removalIntentId the removal operation that consumed the source
 * @param sourceSnapshotId the exact source snapshot, or {@link #NIL} when the source head never published one
 * @param sourceRevision its head revision ({@code R} of the consumed head)
 * @param sourceChecksum its state checksum, or empty when the source head never published one
 * @param extractionSchema the item extraction schema version ({@code 0} while UNAVAILABLE)
 * @param payloadLength bytes of the item blob ({@code 0} while UNAVAILABLE)
 * @param payloadChecksum hex SHA-256 of the item blob (empty while UNAVAILABLE)
 * @param payloadBlobId the blob's id ({@link #NIL} while UNAVAILABLE)
 * @param state the salvage state
 * @param claimant the receiving member, or {@code null} unless delivered
 * @param claimantEpoch the claimant's epoch, or {@code null} unless delivered
 * @param destinationSnapshotId the exact destination snapshot, or {@code null} unless delivered
 * @param deliveryOperationId the delivery operation, or {@code null} unless delivered
 * @param createdAtMillis creation time
 */
public record ProfileSalvage(UUID id, long revision, UUID profileId, UUID removedMember, UUID removedEpoch, UUID removalIntentId,
                             UUID sourceSnapshotId, long sourceRevision, String sourceChecksum, int extractionSchema,
                             int payloadLength, String payloadChecksum, UUID payloadBlobId, State state, UUID claimant,
                             UUID claimantEpoch, UUID destinationSnapshotId, UUID deliveryOperationId, long createdAtMillis) {

    /** The item blob limit: the snapshot payload limit. */
    public static final int MAX_PAYLOAD_BYTES = ProfileSnapshot.MAX_STATE_BYTES;
    /** Outstanding (not DELIVERED) salvage records per profile (COOP-DESIGN §9). */
    public static final int MAX_OUTSTANDING_PER_PROFILE = 64;
    /** The all-zero UUID an UNAVAILABLE record stores where no snapshot or blob exists. */
    public static final UUID NIL = new UUID(0L, 0L);

    public ProfileSalvage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(removedMember, "removedMember");
        Objects.requireNonNull(removedEpoch, "removedEpoch");
        Objects.requireNonNull(removalIntentId, "removalIntentId");
        Objects.requireNonNull(sourceSnapshotId, "sourceSnapshotId");
        Objects.requireNonNull(sourceChecksum, "sourceChecksum");
        Objects.requireNonNull(payloadChecksum, "payloadChecksum");
        Objects.requireNonNull(payloadBlobId, "payloadBlobId");
        Objects.requireNonNull(state, "state");
        CoopWriteGuard.counter(revision, "salvage revision");
        CoopWriteGuard.counter(sourceRevision, "salvage source revision");
        if (createdAtMillis < 0) throw new IllegalArgumentException("createdAtMillis");
        if (state == State.UNAVAILABLE) {
            if (extractionSchema != 0 || payloadLength != 0 || !payloadChecksum.isEmpty() || !NIL.equals(payloadBlobId)) {
                throw new IllegalArgumentException("an UNAVAILABLE salvage carries no item payload");
            }
            if (NIL.equals(sourceSnapshotId) != sourceChecksum.isEmpty()) {
                throw new IllegalArgumentException("an UNAVAILABLE salvage names its source snapshot and checksum together or neither");
            }
        } else {
            if (extractionSchema < 1) throw new IllegalArgumentException("extraction schema " + extractionSchema);
            if (payloadLength < 1 || payloadLength > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("payload length " + payloadLength);
            if (NIL.equals(sourceSnapshotId) || sourceChecksum.isEmpty() || payloadChecksum.isEmpty() || NIL.equals(payloadBlobId)) {
                throw new IllegalArgumentException("an extracted salvage names its source snapshot and item blob");
            }
        }
        final boolean delivered = state == State.DELIVERED;
        if (delivered != (claimant != null) || delivered != (claimantEpoch != null) || delivered != (destinationSnapshotId != null)
            || delivered != (deliveryOperationId != null)) {
            throw new IllegalArgumentException("exactly a DELIVERED salvage names claimant, epoch, destination and delivery");
        }
    }

    /**
     * The Build 52 removal record: UNAVAILABLE at revision 0, pinning what the consumed head named. {@code head} is
     * the member's head as read before removal, or {@code null} when the stream never had one.
     */
    public static ProfileSalvage unavailable(final UUID profileId, final UUID removedMember, final UUID removedEpoch,
                                             final UUID removalIntentId, final MemberStateHead head, final long now) {
        final boolean ready = head != null && head.state() == MemberStateHead.State.READY;
        return new ProfileSalvage(UUID.randomUUID(), 0L, profileId, removedMember, removedEpoch, removalIntentId,
            ready ? head.snapshotId() : NIL, head == null ? 0L : head.revision(), ready ? head.snapshotChecksum() : "",
            0, 0, "", NIL, State.UNAVAILABLE, null, null, null, null, now);
    }

    /** Whether the record still counts toward the per-profile outstanding bound. */
    public boolean outstanding() {
        return this.state != State.DELIVERED;
    }

    /** Salvage states with stable numeric tags. */
    public enum State {
        /** Consumed from the source and extracted, not yet delivered. */
        AVAILABLE(1),
        /** Delivered exactly once into {@code destinationSnapshotId}. */
        DELIVERED(2),
        /** Build 52: the epoch is consumed, nothing was extracted; not offered to any claimant (extraction is Build 54). */
        UNAVAILABLE(3);

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
            throw new IOException("salvage state tag " + tag + " in " + where + " is not known to this build");
        }
    }
}
