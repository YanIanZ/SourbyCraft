package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.Objects;
import java.util.UUID;

/**
 * A durable profile state snapshot.
 *
 * <p>{@code revision} is the profile revision the snapshot was captured at; a snapshot with a lower
 * revision than the profile's current one is stale and is never applied by a switch. {@code checksum}
 * is the SHA-256 of {@code state}, verified on read. {@code schemaVersion} is the codec schema, so an
 * older snapshot can be upgraded by the codec rather than silently misread.</p>
 */
public record ProfileSnapshot(UUID profileId, UUID snapshotId, int schemaVersion, long revision, byte[] state,
                              String checksum, long createdAtMillis) {

    public static final int MAX_STATE_BYTES = 8 * 1024 * 1024;

    public ProfileSnapshot {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(checksum, "checksum");
        if (schemaVersion < 1) throw new IllegalArgumentException("schema " + schemaVersion);
        if (revision < 0) throw new IllegalArgumentException("revision " + revision);
        if (state == null || state.length == 0 || state.length > MAX_STATE_BYTES) {
            throw new IllegalArgumentException("snapshot state must be 1-" + MAX_STATE_BYTES + " bytes");
        }
        state = state.clone();
    }

    @Override
    public byte[] state() {
        return this.state.clone();
    }

    public int size() {
        return this.state.length;
    }
}
