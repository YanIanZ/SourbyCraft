package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * A salvage record (format-2 record type 9): the isolated items of a removed membership, consumed from
 * its source stream and awaiting exactly one delivery into a claimant's head.
 *
 * <p>Build 51 defines the record shape only, so both repositories can store and decode it; no path
 * creates, lists or delivers salvage (Build 54, owner decision OD-3). The invariants the shape
 * encodes: the source snapshot is pinned by id, revision and checksum; the item payload is an
 * immutable blob named by length and SHA-256, never embedded here; {@link State#DELIVERED} names the
 * exact destination snapshot and the delivery operation, so a replay returns the receipt and never
 * re-grants.</p>
 *
 * @param id salvage id
 * @param revision the record's own revision
 * @param profileId the profile
 * @param removedMember the removed member
 * @param removedEpoch its consumed membership epoch
 * @param removalIntentId the removal operation that consumed the source
 * @param sourceSnapshotId the exact source snapshot
 * @param sourceRevision its head revision
 * @param sourceChecksum its state checksum
 * @param extractionSchema the item extraction schema version
 * @param payloadLength bytes of the item blob
 * @param payloadChecksum hex SHA-256 of the item blob
 * @param payloadBlobId the blob's id
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
        if (extractionSchema < 1) throw new IllegalArgumentException("extraction schema " + extractionSchema);
        if (payloadLength < 1 || payloadLength > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("payload length " + payloadLength);
        if (createdAtMillis < 0) throw new IllegalArgumentException("createdAtMillis");
        final boolean delivered = state == State.DELIVERED;
        if (delivered != (claimant != null) || delivered != (claimantEpoch != null) || delivered != (destinationSnapshotId != null)
            || delivered != (deliveryOperationId != null)) {
            throw new IllegalArgumentException("exactly a DELIVERED salvage names claimant, epoch, destination and delivery");
        }
    }

    /** Salvage states with stable numeric tags. */
    public enum State {
        /** Consumed from the source, not yet delivered. */
        AVAILABLE(1),
        /** Delivered exactly once into {@code destinationSnapshotId}. */
        DELIVERED(2);

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
