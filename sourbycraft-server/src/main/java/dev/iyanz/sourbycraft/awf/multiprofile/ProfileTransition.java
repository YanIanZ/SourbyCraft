package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The recovery journal of one profile switch.
 *
 * <p>It is written before each phase and removed once the switch committed or was resolved. A crash
 * between phases therefore leaves a record the player's next join (or an operator) acts on, instead
 * of inventory that exists twice or only in memory.</p>
 *
 * <p>It carries the fence of each claim the switch held — {@code fromFence} on the profile being
 * left, {@code toFence} on the target — and the {@link ProfileOwnership#incarnation() incarnation} of
 * the provider that issued them. Recovery compares a fresh claim only with the fence recorded for the
 * same profile, and only when both come from the same incarnation: a process-local provider counts
 * from 1 again after a restart, so its old fences say nothing about a takeover.</p>
 *
 * @param transitionId unique transition id
 * @param playerId the player being moved
 * @param fromProfile the profile being left, or {@code null} on the player's first switch
 * @param toProfile the target profile
 * @param phase current phase
 * @param deadlineMillis absolute deadline ({@link System#currentTimeMillis()}); passed means abort
 * @param fromSnapshotId the rollback point once it is durable: the snapshot of {@code fromProfile},
 *     or on a first switch the archived pre-profile state
 * @param toSnapshotId the snapshot applied for {@code toProfile}, once chosen; {@code null} when the
 *     target had none and started empty
 * @param fromFence fence of the claim on {@code fromProfile}, {@code 0} when none was taken
 * @param toFence fence of the claim on {@code toProfile}, {@code 0} when none was taken
 * @param incarnation incarnation of the provider that issued the fences, or {@code null} (no claim
 *     yet, or a journal written before Build 50)
 * @param failure why the switch failed or why its rollback is pending; at most
 *     {@value #MAX_FAILURE_CHARS} characters
 * @param updatedAtMillis journal write time
 */
public record ProfileTransition(UUID transitionId, UUID playerId, UUID fromProfile, UUID toProfile,
                                TransitionPhase phase, long deadlineMillis, UUID fromSnapshotId, UUID toSnapshotId,
                                long fromFence, long toFence, String incarnation, String failure,
                                long updatedAtMillis) {

    /** Failure text is cut here so a journal record always stays readable. */
    public static final int MAX_FAILURE_CHARS = 1024;
    public static final int MAX_INCARNATION_CHARS = 128;

    public ProfileTransition {
        Objects.requireNonNull(transitionId, "transitionId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(toProfile, "toProfile");
        Objects.requireNonNull(phase, "phase");
        if (deadlineMillis < 0) throw new IllegalArgumentException("deadline");
        if (fromFence < 0 || toFence < 0) throw new IllegalArgumentException("fence");
        if (incarnation != null && (incarnation.isEmpty() || incarnation.length() > MAX_INCARNATION_CHARS)) {
            throw new IllegalArgumentException("incarnation");
        }
        if (failure != null && failure.length() > MAX_FAILURE_CHARS) failure = failure.substring(0, MAX_FAILURE_CHARS);
    }

    /** A new journal; {@code fence} is the target claim's fence (the one fence earlier builds recorded). */
    public static ProfileTransition started(final UUID playerId, final UUID from, final UUID to, final long deadlineMillis,
                                            final long fence) {
        return new ProfileTransition(UUID.randomUUID(), playerId, from, to, TransitionPhase.VALIDATING, deadlineMillis,
            null, null, 0L, fence, null, null, System.currentTimeMillis());
    }

    public ProfileTransition at(final TransitionPhase next) {
        return new ProfileTransition(this.transitionId, this.playerId, this.fromProfile, this.toProfile, next,
            this.deadlineMillis, this.fromSnapshotId, this.toSnapshotId, this.fromFence, this.toFence,
            this.incarnation, this.failure, System.currentTimeMillis());
    }

    public ProfileTransition withFromSnapshot(final UUID snapshotId) {
        return new ProfileTransition(this.transitionId, this.playerId, this.fromProfile, this.toProfile, this.phase,
            this.deadlineMillis, snapshotId, this.toSnapshotId, this.fromFence, this.toFence, this.incarnation,
            this.failure, System.currentTimeMillis());
    }

    public ProfileTransition withToSnapshot(final UUID snapshotId) {
        return new ProfileTransition(this.transitionId, this.playerId, this.fromProfile, this.toProfile, this.phase,
            this.deadlineMillis, this.fromSnapshotId, snapshotId, this.fromFence, this.toFence, this.incarnation,
            this.failure, System.currentTimeMillis());
    }

    /** Records the claim on the profile being left and the provider incarnation that issued it. */
    public ProfileTransition withFromFence(final long fence, final String issuedBy) {
        return new ProfileTransition(this.transitionId, this.playerId, this.fromProfile, this.toProfile, this.phase,
            this.deadlineMillis, this.fromSnapshotId, this.toSnapshotId, fence, this.toFence, issuedBy, this.failure,
            System.currentTimeMillis());
    }

    /** Records the claim on the target and the provider incarnation that issued it. */
    public ProfileTransition withToFence(final long fence, final String issuedBy) {
        return new ProfileTransition(this.transitionId, this.playerId, this.fromProfile, this.toProfile, this.phase,
            this.deadlineMillis, this.fromSnapshotId, this.toSnapshotId, this.fromFence, fence, issuedBy, this.failure,
            System.currentTimeMillis());
    }

    /** A terminal record: the switch failed and nothing remains to roll back. */
    public ProfileTransition failed(final String reason) {
        return new ProfileTransition(this.transitionId, this.playerId, this.fromProfile, this.toProfile,
            TransitionPhase.FAILED, this.deadlineMillis, this.fromSnapshotId, this.toSnapshotId, this.fromFence,
            this.toFence, this.incarnation, reason == null ? "unknown" : reason, System.currentTimeMillis());
    }

    /** A pending rollback: the target state may be on the player and the rollback has not completed. */
    public ProfileTransition recovering(final String reason) {
        return new ProfileTransition(this.transitionId, this.playerId, this.fromProfile, this.toProfile,
            TransitionPhase.RECOVERING, this.deadlineMillis, this.fromSnapshotId, this.toSnapshotId, this.fromFence,
            this.toFence, this.incarnation, reason == null ? "unknown" : reason, System.currentTimeMillis());
    }

    /** The target claim's fence; kept for callers of earlier builds, which recorded only this one. */
    public long fence() {
        return this.toFence;
    }

    public boolean terminal() {
        return this.phase.terminal();
    }

    /**
     * Whether the target state may have reached the player: {@link TransitionPhase#APPLYING_TARGET}
     * is written before the apply is handed to the entity thread, so an earlier phase proves it was
     * not. {@link TransitionPhase#RECOVERING} is a rollback that has not completed.
     */
    public boolean targetMayBeApplied() {
        return switch (this.phase) {
            case APPLYING_TARGET, TELEPORTING, COMMITTING, RECOVERING -> true;
            case VALIDATING, FREEZING, SAVING, PREPARING_TARGET, ACTIVE, FAILED -> false;
        };
    }

    public boolean pastDeadline() {
        return this.deadlineMillis > 0 && System.currentTimeMillis() > this.deadlineMillis;
    }

    public Map<String, String> describe() {
        final Map<String, String> values = new LinkedHashMap<>();
        values.put("transition", this.transitionId.toString());
        values.put("player", this.playerId.toString());
        values.put("from", this.fromProfile == null ? "none" : this.fromProfile.toString());
        values.put("to", this.toProfile.toString());
        values.put("phase", this.phase.name());
        values.put("from-snapshot", this.fromSnapshotId == null ? "none" : this.fromSnapshotId.toString());
        values.put("to-snapshot", this.toSnapshotId == null ? "none" : this.toSnapshotId.toString());
        values.put("from-fence", Long.toString(this.fromFence));
        values.put("to-fence", Long.toString(this.toFence));
        values.put("incarnation", this.incarnation == null ? "" : this.incarnation);
        values.put("failure", this.failure == null ? "" : this.failure);
        return values;
    }
}
