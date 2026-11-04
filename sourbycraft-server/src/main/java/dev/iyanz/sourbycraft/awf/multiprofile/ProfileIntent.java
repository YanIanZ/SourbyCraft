package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ClaimKind;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.Expected;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The durable decision record of one co-op operation (format-2 record type 12).
 *
 * <p>Protocol (COOP-DESIGN §5): {@code PREPARED → COMMITTED → FINALIZED}, or {@code PREPARED → ABORTED};
 * {@link Phase#RECOVERY_REQUIRED} records an unresolved outcome without implying rollback. A PREPARED
 * intent holds the complete immutable after-images (as {@link BlobRef}s to staged blobs, never embedded
 * snapshots), the expected version vector <em>before</em> its reservations were installed and the vector
 * <em>after</em> (installing a reservation advances each containing record once), the claims it was
 * admitted under and the exact snapshots it pins. COMMIT compares the post-reservation vector and
 * advances every changed record once more; ABORT clears only its own reservations with one more
 * increment; after COMMITTED the operation always rolls forward. Receipt lookup by operation id precedes
 * any retry, so a replayed prepare, commit or abort returns the stored result and increments nothing;
 * the same id with another request digest is {@code INVALID_REQUEST}.</p>
 *
 * <p>Bounds (§9): at most {@value #MAX_REFS} references of each kind and {@value #MAX_BYTES} encoded
 * bytes; a reader that finds a reservation it cannot resolve returns {@code RECOVERY_REQUIRED} and never
 * treats a partially published relationship as valid. Build 51 wires the intent protocol for the solo
 * operations; the membership, shared and salvage kinds decode but are issued by later builds.</p>
 *
 * @param operationId the operation (also the record key)
 * @param revision the record's own revision
 * @param kind the operation kind
 * @param actor the acting account
 * @param requestDigest the immutable request digest
 * @param expectedBefore the version vector read before reservations were installed
 * @param expectedAfter the version vector after reservations (what COMMIT compares)
 * @param profileClaim the {@code P} held at prepare, or {@code null}
 * @param playerClaims the {@code Q} claims held at prepare (actor first), possibly empty for migration/cleanup
 * @param phase the durable phase
 * @param beforeImages references to the exact records read (rollback evidence)
 * @param afterImages references to the staged after-images COMMIT installs
 * @param pins exact snapshots this operation keeps alive while it is not FINALIZED or ABORTED
 * @param createdAtMillis prepare time
 * @param deadlineMillis admission deadline ({@code 0} = none)
 * @param updatedAtMillis last phase write
 * @param outcome terminal result or error text, at most {@value #MAX_OUTCOME_CHARS} characters, or {@code null}
 */
public record ProfileIntent(UUID operationId, long revision, CoopOperation.Kind kind, UUID actor, byte[] requestDigest,
                            List<Expected> expectedBefore, List<Expected> expectedAfter, ClaimRef profileClaim,
                            List<ClaimRef> playerClaims, Phase phase, List<BlobRef> beforeImages, List<BlobRef> afterImages,
                            List<SnapshotRef> pins, long createdAtMillis, long deadlineMillis, long updatedAtMillis,
                            String outcome) {

    public static final int MAX_REFS = 64;
    public static final int MAX_CLAIMS = 4;
    public static final int MAX_OUTCOME_CHARS = 1024;
    /** Encoded intent limit: references only, no embedded snapshots. */
    public static final int MAX_BYTES = 256 * 1024;

    public ProfileIntent {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(requestDigest, "requestDigest");
        Objects.requireNonNull(phase, "phase");
        if (requestDigest.length != CoopOperation.DIGEST_BYTES) throw new IllegalArgumentException("request digest length");
        requestDigest = requestDigest.clone();
        CoopWriteGuard.counter(revision, "intent revision");
        expectedBefore = bounded(expectedBefore, "expectedBefore");
        expectedAfter = bounded(expectedAfter, "expectedAfter");
        if (expectedAfter.isEmpty()) throw new IllegalArgumentException("an intent compares at least one revision");
        playerClaims = bounded(playerClaims, "playerClaims");
        if (playerClaims.size() > MAX_CLAIMS) throw new IllegalArgumentException("too many player claims");
        for (final ClaimRef claim : playerClaims) {
            if (claim.kind() != ClaimKind.PLAYER) throw new IllegalArgumentException("player claims must be Q claims");
        }
        if (profileClaim != null && profileClaim.kind() != ClaimKind.PROFILE) {
            throw new IllegalArgumentException("the profile claim must be a P claim");
        }
        if (profileClaim == null && playerClaims.isEmpty()
            && kind != CoopOperation.Kind.MIGRATION && kind != CoopOperation.Kind.CLEANUP) {
            throw new IllegalArgumentException(kind + " requires a claim");
        }
        beforeImages = bounded(beforeImages, "beforeImages");
        afterImages = bounded(afterImages, "afterImages");
        pins = bounded(pins, "pins");
        if (createdAtMillis < 0 || deadlineMillis < 0 || updatedAtMillis < 0) throw new IllegalArgumentException("intent times");
        if (outcome != null && outcome.length() > MAX_OUTCOME_CHARS) outcome = outcome.substring(0, MAX_OUTCOME_CHARS);
    }

    private static <T> List<T> bounded(final List<T> list, final String what) {
        final List<T> copy = List.copyOf(list == null ? List.of() : list);
        if (copy.size() > MAX_REFS) throw new IllegalArgumentException(what + " has more than " + MAX_REFS + " entries");
        return copy;
    }

    @Override
    public byte[] requestDigest() {
        return this.requestDigest.clone();
    }

    /** A PREPARED intent for {@code guard}'s operation at revision 0. */
    public static ProfileIntent prepared(final CoopWriteGuard guard, final List<Expected> expectedAfter,
                                         final List<BlobRef> beforeImages, final List<BlobRef> afterImages,
                                         final List<SnapshotRef> pins, final long deadlineMillis) {
        final long now = System.currentTimeMillis();
        final CoopOperation op = guard.operation();
        final List<ClaimRef> players = new java.util.ArrayList<>(2);
        if (guard.actor() != null) players.add(ClaimRef.of(guard.actor()));
        if (guard.affected() != null) players.add(ClaimRef.of(guard.affected()));
        return new ProfileIntent(op.operationId(), 0L, op.kind(), op.actor(), op.requestDigest(), guard.vector(), expectedAfter,
            guard.profile() == null ? null : ClaimRef.of(guard.profile()), players, Phase.PREPARED, beforeImages, afterImages,
            pins, now, deadlineMillis, now, null);
    }

    /** The intent at {@code next}; refuses a transition the protocol does not allow. */
    public ProfileIntent at(final Phase next, final String outcome) {
        if (!this.phase.mayBecome(next)) {
            throw new IllegalStateException("intent " + this.operationId + " is " + this.phase + " and cannot become " + next);
        }
        return new ProfileIntent(this.operationId, CoopWriteGuard.next(this.revision, "intent " + this.operationId), this.kind,
            this.actor, this.requestDigest, this.expectedBefore, this.expectedAfter, this.profileClaim, this.playerClaims, next,
            this.beforeImages, this.afterImages, this.pins, this.createdAtMillis, this.deadlineMillis,
            System.currentTimeMillis(), outcome);
    }

    /** The same record at an explicit revision (decoders only). */
    public ProfileIntent atRevision(final long revision) {
        return new ProfileIntent(this.operationId, revision, this.kind, this.actor, this.requestDigest, this.expectedBefore,
            this.expectedAfter, this.profileClaim, this.playerClaims, this.phase, this.beforeImages, this.afterImages, this.pins,
            this.createdAtMillis, this.deadlineMillis, this.updatedAtMillis, this.outcome);
    }

    public boolean sameRequest(final byte[] digest) {
        return digest != null && java.security.MessageDigest.isEqual(this.requestDigest, digest);
    }

    /** Whether the intent still holds reservations and pins (not FINALIZED or ABORTED). */
    public boolean pending() {
        return !this.phase.terminal();
    }

    /** Whether the operation is decided and must roll forward on recovery. */
    public boolean committed() {
        return this.phase == Phase.COMMITTED || this.phase == Phase.FINALIZED;
    }

    public boolean pastDeadline(final long now) {
        return this.deadlineMillis > 0 && now > this.deadlineMillis;
    }

    /** Durable phases with stable numeric tags and the allowed transitions. */
    public enum Phase {
        /** Reservations installed, after-images staged; not yet decided. */
        PREPARED(1),
        /** Decided: roll forward on recovery, never undo. */
        COMMITTED(2),
        /** Canonical after-images installed, reservations and pins released. Terminal. */
        FINALIZED(3),
        /** Decided against before commit; own reservations cleared. Terminal. */
        ABORTED(4),
        /** Outcome unknown (lost reply, crash mid-publication); an operator or recovery resolves it. */
        RECOVERY_REQUIRED(5);

        private final int tag;

        Phase(final int tag) {
            this.tag = tag;
        }

        public int tag() {
            return this.tag;
        }

        public static Phase fromTag(final int tag, final String where) throws IOException {
            for (final Phase phase : values()) {
                if (phase.tag == tag) return phase;
            }
            throw new IOException("intent phase tag " + tag + " in " + where + " is not known to this build");
        }

        public boolean terminal() {
            return this == FINALIZED || this == ABORTED;
        }

        public boolean mayBecome(final Phase next) {
            return switch (this) {
                case PREPARED -> next == COMMITTED || next == ABORTED || next == RECOVERY_REQUIRED;
                case COMMITTED -> next == FINALIZED || next == RECOVERY_REQUIRED;
                case RECOVERY_REQUIRED -> next == COMMITTED || next == FINALIZED || next == ABORTED;
                case FINALIZED, ABORTED -> false;
            };
        }
    }

    /**
     * Evidence of a claim held at prepare: enough to compare fences later for the same resource and
     * incarnation, never a token that re-authorises a write.
     */
    public record ClaimRef(ClaimKind kind, UUID id, String node, String incarnation, UUID ownerToken, long fence) {

        public ClaimRef {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(node, "node");
            Objects.requireNonNull(incarnation, "incarnation");
            Objects.requireNonNull(ownerToken, "ownerToken");
            if (fence < 1L || fence > CoopWriteGuard.MAX_COUNTER) throw new IllegalArgumentException("fence " + fence);
        }

        public static ClaimRef of(final CoopWriteGuard.ProfileClaim claim) {
            return new ClaimRef(ClaimKind.PROFILE, claim.profileId(), claim.node(), claim.incarnation(), claim.ownerToken(),
                claim.fence());
        }

        public static ClaimRef of(final CoopWriteGuard.PlayerClaim claim) {
            return new ClaimRef(ClaimKind.PLAYER, claim.playerId(), claim.node(), claim.incarnation(), claim.ownerToken(),
                claim.fence());
        }
    }

    /**
     * A reference to one framed record or staged blob: its record type, identity, revision, length and
     * payload SHA-256. After-images are encoded with their final revisions.
     *
     * @param recordType the {@link ProfileRecords} type byte
     * @param id the record's primary id (player, profile, operation, invite...)
     * @param member member id for a head or member snapshot, else {@code null}
     * @param epoch epoch for a head or member snapshot, else {@code null}
     * @param revision the record revision the blob carries
     * @param length payload bytes
     * @param sha256 payload digest (32 bytes)
     */
    public record BlobRef(byte recordType, UUID id, UUID member, UUID epoch, long revision, int length, byte[] sha256) {

        public BlobRef {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(sha256, "sha256");
            if ((member == null) != (epoch == null)) throw new IllegalArgumentException("member and epoch go together");
            CoopWriteGuard.counter(revision, "blob revision");
            if (length < 0) throw new IllegalArgumentException("length " + length);
            if (sha256.length != 32) throw new IllegalArgumentException("sha256 length");
            sha256 = sha256.clone();
        }

        @Override
        public byte[] sha256() {
            return this.sha256.clone();
        }
    }

    /** An exact pinned snapshot: {@code (profile, member, epoch, snapshotId)}; {@code member}/{@code epoch} null for a format-1 snapshot. */
    public record SnapshotRef(UUID profileId, UUID member, UUID epoch, UUID snapshotId) {

        public SnapshotRef {
            Objects.requireNonNull(profileId, "profileId");
            Objects.requireNonNull(snapshotId, "snapshotId");
            if ((member == null) != (epoch == null)) throw new IllegalArgumentException("member and epoch go together");
        }

        public static SnapshotRef of(final ProfileSnapshot snapshot) {
            return new SnapshotRef(snapshot.profileId(), snapshot.memberId(), snapshot.membershipEpoch(), snapshot.snapshotId());
        }
    }
}
