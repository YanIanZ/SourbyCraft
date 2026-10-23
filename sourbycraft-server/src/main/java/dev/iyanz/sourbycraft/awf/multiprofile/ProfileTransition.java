package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The recovery journal of one profile switch.
 *
 * <p>It is written before each phase and removed only after the commit is durable. A crash between
 * phases therefore leaves a record an operator (or the next boot) can act on, instead of inventory
 * that exists twice or only in memory. It also carries the ownership fence the transition was
 * authorised under, so a recovery after a takeover refuses to write with a stale fence.</p>
 *
 * @param transitionId unique transition id
 * @param playerId the player being moved
 * @param fromProfile the profile being left, or {@code null} on a first join
 * @param toProfile the target profile
 * @param phase current phase
 * @param deadlineMillis absolute deadline ({@link System#currentTimeMillis()}); passed means abort
 * @param fromSnapshotId the durable snapshot of {@code fromProfile}, once written
 * @param toSnapshotId the snapshot applied for {@code toProfile}, once known
 * @param fence ownership fence in force, {@code 0} in standalone mode
 * @param failure a short reason when the phase is {@link TransitionPhase#FAILED}
 * @param updatedAtMillis journal write time
 */
public record ProfileTransition(UUID transitionId, UUID playerId, UUID fromProfile, UUID toProfile,
                                TransitionPhase phase, long deadlineMillis, UUID fromSnapshotId, UUID toSnapshotId,
                                long fence, String failure, long updatedAtMillis) {

    public ProfileTransition {
        Objects.requireNonNull(transitionId, "transitionId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(toProfile, "toProfile");
        Objects.requireNonNull(phase, "phase");
        if (deadlineMillis < 0) throw new IllegalArgumentException("deadline");
        if (fence < 0) throw new IllegalArgumentException("fence");
    }

    public static ProfileTransition started(final UUID playerId, final UUID from, final UUID to, final long deadlineMillis,
                                            final long fence) {
        return new ProfileTransition(UUID.randomUUID(), playerId, from, to, TransitionPhase.VALIDATING, deadlineMillis,
            null, null, fence, null, System.currentTimeMillis());
    }

    public ProfileTransition at(final TransitionPhase next) {
        return new ProfileTransition(this.transitionId, this.playerId, this.fromProfile, this.toProfile, next,
            this.deadlineMillis, this.fromSnapshotId, this.toSnapshotId, this.fence, this.failure,
            System.currentTimeMillis());
    }

    public ProfileTransition withFromSnapshot(final UUID snapshotId) {
        return new ProfileTransition(this.transitionId, this.playerId, this.fromProfile, this.toProfile, this.phase,
            this.deadlineMillis, snapshotId, this.toSnapshotId, this.fence, this.failure, System.currentTimeMillis());
    }

    public ProfileTransition withToSnapshot(final UUID snapshotId) {
        return new ProfileTransition(this.transitionId, this.playerId, this.fromProfile, this.toProfile, this.phase,
            this.deadlineMillis, this.fromSnapshotId, snapshotId, this.fence, this.failure, System.currentTimeMillis());
    }

    public ProfileTransition withFence(final long newFence) {
        return new ProfileTransition(this.transitionId, this.playerId, this.fromProfile, this.toProfile, this.phase,
            this.deadlineMillis, this.fromSnapshotId, this.toSnapshotId, newFence, this.failure,
            System.currentTimeMillis());
    }

    public ProfileTransition failed(final String reason) {
        return new ProfileTransition(this.transitionId, this.playerId, this.fromProfile, this.toProfile,
            TransitionPhase.FAILED, this.deadlineMillis, this.fromSnapshotId, this.toSnapshotId, this.fence,
            reason == null ? "unknown" : reason, System.currentTimeMillis());
    }

    public boolean terminal() {
        return this.phase.terminal();
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
        values.put("fence", Long.toString(this.fence));
        values.put("failure", this.failure == null ? "" : this.failure);
        return values;
    }
}
