package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The recovery journal of one profile switch (record type 5).
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
 * <p><b>Format 2</b> adds the co-op fields: the player's {@code Q} fence and incarnation, the session
 * generation the switch was admitted under, the membership epochs on both profiles, the expected
 * {@code A}/{@code M}/{@code R} the switch read, the intent revision and request digest of its
 * operation, the account revision the commit produced and a terminal outcome. They are {@code 0} /
 * {@code null} for a format-1 journal; the format-2 decoder requires them. The phases stay the
 * player-apply progress; the operation's durable decision is its {@link ProfileIntent}.</p>
 *
 * @param transitionId unique transition id
 * @param playerId the player being moved
 * @param fromProfile the profile being left, or {@code null} on the player's first switch
 * @param toProfile the target profile
 * @param phase current phase
 * @param deadlineMillis absolute deadline ({@link System#currentTimeMillis()}); passed means abort
 * @param fromSnapshotId the rollback point once it is durable
 * @param toSnapshotId the snapshot applied for {@code toProfile}, once chosen; {@code null} when empty
 * @param fromFence fence of the claim on {@code fromProfile}, {@code 0} when none was taken
 * @param toFence fence of the claim on {@code toProfile}, {@code 0} when none was taken
 * @param incarnation incarnation of the provider that issued the fences, or {@code null}
 * @param failure why the switch failed or why its rollback is pending; at most {@value #MAX_FAILURE_CHARS} characters
 * @param updatedAtMillis journal write time
 * @param coop the format-2 fields, or {@code null} for a format-1 journal
 */
public record ProfileTransition(UUID transitionId, UUID playerId, UUID fromProfile, UUID toProfile,
                                TransitionPhase phase, long deadlineMillis, UUID fromSnapshotId, UUID toSnapshotId,
                                long fromFence, long toFence, String incarnation, String failure,
                                long updatedAtMillis, Coop coop) {

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

    /** A format-1 journal (no co-op fields). */
    public ProfileTransition(final UUID transitionId, final UUID playerId, final UUID fromProfile, final UUID toProfile,
                             final TransitionPhase phase, final long deadlineMillis, final UUID fromSnapshotId,
                             final UUID toSnapshotId, final long fromFence, final long toFence, final String incarnation,
                             final String failure, final long updatedAtMillis) {
        this(transitionId, playerId, fromProfile, toProfile, phase, deadlineMillis, fromSnapshotId, toSnapshotId, fromFence,
            toFence, incarnation, failure, updatedAtMillis, null);
    }

    /** A new journal; {@code fence} is the target claim's fence (the one fence earlier builds recorded). */
    public static ProfileTransition started(final UUID playerId, final UUID from, final UUID to, final long deadlineMillis,
                                            final long fence) {
        return new ProfileTransition(UUID.randomUUID(), playerId, from, to, TransitionPhase.VALIDATING, deadlineMillis,
            null, null, 0L, fence, null, null, System.currentTimeMillis(), null);
    }

    public ProfileTransition at(final TransitionPhase next) {
        return copy(next, this.fromSnapshotId, this.toSnapshotId, this.fromFence, this.toFence, this.incarnation,
            this.failure, this.coop);
    }

    public ProfileTransition withFromSnapshot(final UUID snapshotId) {
        return copy(this.phase, snapshotId, this.toSnapshotId, this.fromFence, this.toFence, this.incarnation, this.failure,
            this.coop);
    }

    public ProfileTransition withToSnapshot(final UUID snapshotId) {
        return copy(this.phase, this.fromSnapshotId, snapshotId, this.fromFence, this.toFence, this.incarnation,
            this.failure, this.coop);
    }

    /** Records the claim on the profile being left and the provider incarnation that issued it. */
    public ProfileTransition withFromFence(final long fence, final String issuedBy) {
        return copy(this.phase, this.fromSnapshotId, this.toSnapshotId, fence, this.toFence, issuedBy, this.failure,
            this.coop);
    }

    /** Records the claim on the target and the provider incarnation that issued it. */
    public ProfileTransition withToFence(final long fence, final String issuedBy) {
        return copy(this.phase, this.fromSnapshotId, this.toSnapshotId, this.fromFence, fence, issuedBy, this.failure,
            this.coop);
    }

    /** Attaches or replaces the co-op fields. */
    public ProfileTransition withCoop(final Coop coop) {
        return copy(this.phase, this.fromSnapshotId, this.toSnapshotId, this.fromFence, this.toFence, this.incarnation,
            this.failure, coop);
    }

    /** A terminal record: the switch failed and nothing remains to roll back. */
    public ProfileTransition failed(final String reason) {
        return copy(TransitionPhase.FAILED, this.fromSnapshotId, this.toSnapshotId, this.fromFence, this.toFence,
            this.incarnation, reason == null ? "unknown" : reason, this.coop);
    }

    /** A pending rollback: the target state may be on the player and the rollback has not completed. */
    public ProfileTransition recovering(final String reason) {
        return copy(TransitionPhase.RECOVERING, this.fromSnapshotId, this.toSnapshotId, this.fromFence, this.toFence,
            this.incarnation, reason == null ? "unknown" : reason, this.coop);
    }

    private ProfileTransition copy(final TransitionPhase phase, final UUID fromSnapshotId, final UUID toSnapshotId,
                                   final long fromFence, final long toFence, final String incarnation, final String failure,
                                   final Coop coop) {
        return new ProfileTransition(this.transitionId, this.playerId, this.fromProfile, this.toProfile, phase,
            this.deadlineMillis, fromSnapshotId, toSnapshotId, fromFence, toFence, incarnation, failure,
            System.currentTimeMillis(), coop);
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
        if (this.coop != null) {
            values.put("operation", this.coop.operationId().toString());
            values.put("session-generation", Long.toString(this.coop.sessionGeneration()));
            values.put("player-fence", Long.toString(this.coop.playerFence()));
            values.put("outcome", this.coop.outcome() == null ? "" : this.coop.outcome());
        }
        return values;
    }

    /**
     * The format-2 journal fields.
     *
     * @param operationId the switch operation ({@link ProfileIntent})
     * @param requestDigest the operation's request digest (32 bytes)
     * @param intentRevision the intent record revision the journal was written against
     * @param playerFence the player's {@code Q} fence
     * @param playerIncarnation the {@code Q} provider's incarnation
     * @param sessionGeneration the account session generation the switch was admitted under
     * @param fromEpoch membership epoch on {@code fromProfile}, or {@code null}
     * @param toEpoch membership epoch on {@code toProfile}
     * @param expectedAccount {@code A} read at admission
     * @param expectedFromMetadata {@code M} of the source read at admission ({@code 0} when none)
     * @param expectedToMetadata {@code M} of the target read at admission
     * @param expectedFromHead {@code R} of the source stream ({@code 0} when none)
     * @param expectedToHead {@code R} of the target stream
     * @param committedAccount {@code A} the commit produced, or {@code 0} before commit
     * @param outcome terminal outcome text, or {@code null}
     */
    public record Coop(UUID operationId, byte[] requestDigest, long intentRevision, long playerFence, String playerIncarnation,
                       long sessionGeneration, UUID fromEpoch, UUID toEpoch, long expectedAccount, long expectedFromMetadata,
                       long expectedToMetadata, long expectedFromHead, long expectedToHead, long committedAccount,
                       String outcome) {

        public static final int MAX_OUTCOME_CHARS = 256;

        public Coop {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(requestDigest, "requestDigest");
            Objects.requireNonNull(playerIncarnation, "playerIncarnation");
            Objects.requireNonNull(toEpoch, "toEpoch");
            if (requestDigest.length != CoopOperation.DIGEST_BYTES) throw new IllegalArgumentException("request digest length");
            requestDigest = requestDigest.clone();
            CoopWriteGuard.counter(intentRevision, "intent revision");
            CoopWriteGuard.counter(playerFence, "player fence");
            CoopWriteGuard.counter(sessionGeneration, "session generation");
            CoopWriteGuard.counter(expectedAccount, "expected account revision");
            CoopWriteGuard.counter(expectedFromMetadata, "expected source metadata revision");
            CoopWriteGuard.counter(expectedToMetadata, "expected target metadata revision");
            CoopWriteGuard.counter(expectedFromHead, "expected source head revision");
            CoopWriteGuard.counter(expectedToHead, "expected target head revision");
            CoopWriteGuard.counter(committedAccount, "committed account revision");
            if (playerIncarnation.length() > MAX_INCARNATION_CHARS) throw new IllegalArgumentException("player incarnation");
            if (outcome != null && outcome.length() > MAX_OUTCOME_CHARS) outcome = outcome.substring(0, MAX_OUTCOME_CHARS);
        }

        @Override
        public byte[] requestDigest() {
            return this.requestDigest.clone();
        }

        public Coop committed(final long committedAccount, final String outcome) {
            return new Coop(this.operationId, this.requestDigest, this.intentRevision, this.playerFence, this.playerIncarnation,
                this.sessionGeneration, this.fromEpoch, this.toEpoch, this.expectedAccount, this.expectedFromMetadata,
                this.expectedToMetadata, this.expectedFromHead, this.expectedToHead, committedAccount, outcome);
        }
    }
}
