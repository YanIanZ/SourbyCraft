package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.Objects;
import java.util.UUID;

/**
 * A durable state snapshot: a profile's (format 1, record type 4) or one member's (format 2, record
 * type 10, keyed by {@code (profile, member, epoch)}).
 *
 * <p>{@code revision} is, for a member snapshot, the head revision {@code R} it was published at —
 * never the profile's {@code M} or the shared {@code S}; its {@link MemberStateHead} names this exact
 * snapshot by id, revision and checksum, and a lookup that finds any of the three differing (older
 * <em>or newer</em>) fails closed. For a format-1 snapshot it is the profile revision the snapshot
 * was captured at, compared with the profile's revision by the solo engine path. {@code checksum} is
 * the SHA-256 of {@code state}, verified on read. {@code schemaVersion} is the state codec schema.</p>
 *
 * @param profileId the profile
 * @param memberId the member the state belongs to, or {@code null} for a format-1 profile snapshot
 * @param membershipEpoch the member's epoch, or {@code null} for a format-1 snapshot
 * @param snapshotId unique id
 * @param schemaVersion state codec schema
 * @param revision {@code R} (member snapshot) or the profile revision (format 1)
 * @param state encoded {@link ProfileState}, 1..{@value #MAX_STATE_BYTES} bytes
 * @param checksum hex SHA-256 of {@code state}
 * @param createdAtMillis capture time
 * @param operationId the operation that produced it, or {@code null}
 */
public record ProfileSnapshot(UUID profileId, UUID memberId, UUID membershipEpoch, UUID snapshotId, int schemaVersion,
                              long revision, byte[] state, String checksum, long createdAtMillis, UUID operationId) {

    public static final int MAX_STATE_BYTES = 8 * 1024 * 1024;

    public ProfileSnapshot {
        Objects.requireNonNull(profileId, "profileId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(checksum, "checksum");
        if ((memberId == null) != (membershipEpoch == null)) {
            throw new IllegalArgumentException("a member snapshot names both member and epoch");
        }
        if (schemaVersion < 1) throw new IllegalArgumentException("schema " + schemaVersion);
        CoopWriteGuard.counter(revision, "snapshot revision");
        if (state == null || state.length == 0 || state.length > MAX_STATE_BYTES) {
            throw new IllegalArgumentException("snapshot state must be 1-" + MAX_STATE_BYTES + " bytes");
        }
        state = state.clone();
    }

    /** A format-1 profile snapshot (no member, epoch or operation). */
    public ProfileSnapshot(final UUID profileId, final UUID snapshotId, final int schemaVersion, final long revision,
                           final byte[] state, final String checksum, final long createdAtMillis) {
        this(profileId, null, null, snapshotId, schemaVersion, revision, state, checksum, createdAtMillis, null);
    }

    /** A member snapshot for {@code head}'s stream at {@code revision}. */
    public static ProfileSnapshot forMember(final UUID profileId, final UUID memberId, final UUID epoch, final long revision,
                                            final byte[] state, final UUID operationId) {
        return new ProfileSnapshot(profileId, memberId, epoch, UUID.randomUUID(), ProfileState.SCHEMA, revision, state,
            ProfileStateCodec.checksum(state), System.currentTimeMillis(), operationId);
    }

    @Override
    public byte[] state() {
        return this.state.clone();
    }

    public int size() {
        return this.state.length;
    }

    /** Whether this is a member snapshot (format 2) rather than a format-1 profile snapshot. */
    public boolean member() {
        return this.memberId != null;
    }

    /** Whether this snapshot belongs to the stream {@code (profile, member, epoch)}. */
    public boolean inStream(final UUID profileId, final UUID memberId, final UUID epoch) {
        return this.profileId.equals(profileId) && Objects.equals(this.memberId, memberId)
            && Objects.equals(this.membershipEpoch, epoch);
    }
}
