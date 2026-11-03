package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.Expected;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ProfileClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileIntent.BlobRef;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileIntent.SnapshotRef;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One co-op membership operation, fully decided in Java from the records the caller read, ready to run as a
 * recoverable intent (COOP-DESIGN §5): the pre-reservation vector, the post-reservation vector, the framed
 * after-images at the revisions COMMIT installs, the exact snapshots to pin and the commit deadline. Built by
 * {@link CoopMembership}; run by the engine as {@code stage → prepareIntent → commitIntent} (abort on a refused
 * commit, {@code resolveIntent} on recovery), exactly like the solo intents of Build 51.
 *
 * <p>Nothing here is applied: a plan is a proposal whose every premise (claims, the revision of every record it
 * read, the reservations, the deadline) the repository re-checks atomically at prepare and again at commit. A
 * record that moved in between makes the commit {@code STALE_STATE}; the caller re-reads and builds a new plan
 * with a new operation id. A replay of the same operation id returns the stored outcome without writing; the
 * values a replay must reproduce (the allocated epoch, the alias, the record ids) are in the after-images, so the
 * caller reads them back from the installed records rather than allocating again.</p>
 *
 * <p>Vector rules the repositories enforce (both backends the same): a record the plan reserves is listed at
 * {@code N} before and {@code N + 1} after, its after-image at {@code N + 2} (a reserved record without an
 * after-image is released by the commit at {@code N + 2} as well); a record the plan only compares (an invite,
 * vote or salvage record, which carries no reservation field) is listed at {@code N} before and after, its
 * after-image, when any, at {@code N + 1}; a record the plan creates is {@link CoopWriteGuard#ABSENT} before and
 * after, its after-image at {@code 0}. Accounts are never ABSENT (a missing account is revision 0). Every
 * membership plan reserves the profile's metadata record ({@link CoopMembership}).</p>
 *
 * @param op the operation (id, kind, actor, request digest); the engine issues it before building the plan
 * @param profileId the profile every plan acts on (the {@code P} claim's resource)
 * @param affectedAccount the account other than the actor's whose {@code Q} the operation must hold (a kicked
 *     member), or {@code null} when the actor's claim is the only one
 * @param before the pre-reservation vector
 * @param after the post-reservation vector (what COMMIT compares)
 * @param images the after-images to stage
 * @param pins exact snapshots kept alive while the intent is pending
 * @param deadlineMillis absolute store-clock deadline COMMIT refuses after ({@code 0} = none); an invite accept
 *     carries the invite's expiry, a vote approval the vote's
 * @param result what the operation allocates, for the caller's reply
 */
public record CoopIntentPlan(CoopOperation op, UUID profileId, UUID affectedAccount, List<Expected> before, List<Expected> after,
                             List<Image> images, List<SnapshotRef> pins, long deadlineMillis, Result result) {

    public CoopIntentPlan {
        Objects.requireNonNull(op, "op");
        Objects.requireNonNull(profileId, "profileId");
        before = List.copyOf(Objects.requireNonNull(before, "before"));
        after = List.copyOf(Objects.requireNonNull(after, "after"));
        images = List.copyOf(Objects.requireNonNull(images, "images"));
        pins = List.copyOf(pins == null ? List.of() : pins);
        result = result == null ? Result.NONE : result;
        if (before.size() != after.size() || after.isEmpty()) throw new IllegalArgumentException("a plan names the same records before and after");
        for (int i = 0; i < before.size(); i++) {
            final Expected b = before.get(i);
            final Expected a = after.get(i);
            if (!b.sameRecord(a)) throw new IllegalArgumentException("vector entry " + i + " names different records before and after");
            final boolean reserve = b.revision() != CoopWriteGuard.ABSENT && a.revision() == b.revision() + 1L;
            if (!reserve && a.revision() != b.revision()) {
                throw new IllegalArgumentException(b.describe() + " moves from " + b.revision() + " to " + a.revision()
                    + "; a reservation advances a record exactly once");
            }
        }
        if (deadlineMillis < 0) throw new IllegalArgumentException("deadline");
        for (final Image image : images) {
            final Expected target = image.target();
            Expected named = null;
            for (final Expected entry : after) {
                if (entry.sameRecord(target)) named = entry;
            }
            if (named == null) throw new IllegalArgumentException("after-image of " + target.describe() + " is not in the vector");
            final long expected = named.revision() == CoopWriteGuard.ABSENT ? 0L : named.revision() + 1L;
            if (image.revision() != expected) {
                throw new IllegalArgumentException("after-image of " + target.describe() + " is at " + image.revision() + ", the commit writes " + expected);
            }
        }
    }

    /** Stages every after-image with the repository; the references go into the intent. Staging writes nothing canonical. */
    public List<BlobRef> stage(final ProfileRepository repository) throws IOException {
        final List<BlobRef> refs = new ArrayList<>(this.images.size());
        for (final Image image : this.images) {
            refs.add(repository.stageAfterImage(this.op.operationId(), image.recordType(), image.id(), image.member(), image.epoch(),
                image.revision(), image.payload()));
        }
        return refs;
    }

    /**
     * The guard of the PREPARE: the profile claim, the actor's session claim, the affected account's claim when
     * {@link #affectedAccount} is set (the kicked member's; {@code null} otherwise), the intent expected absent and
     * the pre-reservation vector.
     */
    public CoopWriteGuard prepareGuard(final ProfileClaim profile, final PlayerClaim actor, final PlayerClaim affected) {
        requireClaims(profile, actor, affected);
        return CoopWriteGuard.intent(this.op, profile, actor, affected, CoopWriteGuard.ABSENT, this.before, Set.of());
    }

    /** The PREPARED intent of this plan from its staged after-images. */
    public ProfileIntent prepared(final CoopWriteGuard prepareGuard, final List<BlobRef> refs) {
        return ProfileIntent.prepared(prepareGuard, this.after, List.of(), refs, this.pins, this.deadlineMillis);
    }

    /** The guard of a commit, abort or resolve of {@code stored}: its revision, its post-reservation vector, its own reservation. */
    public CoopWriteGuard intentGuard(final ProfileClaim profile, final PlayerClaim actor, final PlayerClaim affected,
                                      final ProfileIntent stored) {
        requireClaims(profile, actor, affected);
        return CoopWriteGuard.intent(this.op, profile, actor, affected, stored.revision(), stored.expectedAfter(),
            Set.of(this.op.operationId()));
    }

    private void requireClaims(final ProfileClaim profile, final PlayerClaim actor, final PlayerClaim affected) {
        if (profile == null || !profile.profileId().equals(this.profileId)) {
            throw new IllegalArgumentException("the plan needs the profile claim P on " + this.profileId);
        }
        if (actor == null || !actor.playerId().equals(this.op.actor())) {
            throw new IllegalArgumentException("the plan needs the actor's session claim Q on " + this.op.actor());
        }
        if (this.affectedAccount != null && (affected == null || !affected.playerId().equals(this.affectedAccount))) {
            throw new IllegalArgumentException("the plan needs the affected account's claim Q on " + this.affectedAccount);
        }
        if (this.affectedAccount == null && affected != null) {
            throw new IllegalArgumentException("the plan affects no second account; pass no affected claim");
        }
    }

    /**
     * One framed after-image: record type, identity, stream (for a head), the revision COMMIT installs and the payload
     * (encoded with that revision, carrying no reservation).
     */
    public record Image(byte recordType, UUID id, UUID member, UUID epoch, long revision, byte[] payload) {

        public Image {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(payload, "payload");
            if ((member == null) != (epoch == null)) throw new IllegalArgumentException("member and epoch go together");
            CoopWriteGuard.counter(revision, "after-image revision");
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return this.payload.clone();
        }

        /** The vector entry this image installs. */
        public Expected target() {
            return switch (this.recordType) {
                case ProfileRecords.TYPE_ACCOUNT -> Expected.account(this.id, 0L);
                case ProfileRecords.TYPE_PROFILE -> Expected.metadata(this.id, 0L);
                case ProfileRecords.TYPE_SHARED -> Expected.shared(this.id, 0L);
                case ProfileRecords.TYPE_MEMBER_HEAD -> Expected.head(this.id, this.member, this.epoch, 0L);
                default -> Expected.record(this.id, 0L);
            };
        }
    }

    /**
     * What a plan allocates. {@code recordId} is the invite, vote or salvage record the operation creates or
     * decides; {@code epoch} the membership epoch admitted (accept) or consumed (leave, kick); {@code alias} the
     * account-local alias an accept gives the new member; {@code salvageId} the salvage record a removal writes.
     */
    public record Result(UUID recordId, UUID epoch, String alias, UUID salvageId) {

        public static final Result NONE = new Result(null, null, null, null);
    }
}
