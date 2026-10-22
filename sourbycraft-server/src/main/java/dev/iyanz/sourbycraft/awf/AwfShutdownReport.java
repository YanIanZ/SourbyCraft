package dev.iyanz.sourbycraft.awf;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * What every AWF storage's close at shutdown could and could not confirm, and the alert that says
 * so. A storage reports here whichever path closes it (the region save of its world, or
 * {@link AwfEngine#closeAll()} for the rest); {@link #record} logs one ERROR per storage whose data
 * is not confirmed, and {@link #alert} is the one aggregate line naming all of them.
 *
 * <p>Wording rule: a commit that was sent but not answered before the deadline is
 * {@link Outcome#UNCERTAIN}, never "failed": the backend may have applied it. Only a commit that was
 * never attempted ({@link Outcome#REFUSED}) is certainly not on the backend.</p>
 */
public final class AwfShutdownReport {

    /** Grep-able marker of an incomplete AWF shutdown; the success line carries {@link #OK_MARKER}. */
    public static final String INCOMPLETE_MARKER = "AWF-SHUTDOWN-INCOMPLETE";
    public static final String OK_MARKER = "AWF-SHUTDOWN-OK";
    /** How many dirty positions each storage's line lists before "+N more". */
    static final int POSITIONS_LISTED = 16;

    public enum Outcome {
        /** Everything written was committed and confirmed (or nothing was dirty). */
        CONFIRMED,
        /** The final commit failed with a backend error before the deadline. */
        FAILED,
        /** The deadline passed before a commit attempt could start: certainly not committed. */
        REFUSED,
        /** A commit was sent (or was running) and not confirmed before the deadline: may or may not be applied. */
        UNCERTAIN
    }

    /**
     * One storage's close.
     *
     * @param dirty chunk positions written since the last confirmed commit (unconfirmed)
     * @param dirtySample up to {@link #POSITIONS_LISTED} of them
     * @param queuedCommits commits queued on the storage lane that the deadline fenced before they started
     * @param refusedWrites writes refused because the storage was closed or fenced at shutdown
     * @param uncertainChunks chunks in a commit sent but not confirmed, 0 for none
     * @param released whether the backend released the store (Redis lease); a lease that is not released expires
     * @param detail the error, or {@code null}
     */
    public record StorageResult(String storage, Outcome outcome, int dirty, List<ChunkKey> dirtySample,
                                int queuedCommits, long refusedWrites, int uncertainChunks, boolean released,
                                String detail) {
        public StorageResult {
            dirtySample = List.copyOf(dirtySample);
        }

        public boolean confirmed() {
            return this.outcome == Outcome.CONFIRMED;
        }

        String describe() {
            final StringBuilder text = new StringBuilder(this.storage).append(": ").append(this.outcome);
            text.append(" - ").append(this.dirty).append(" unconfirmed dirty position(s)");
            if (!this.dirtySample.isEmpty()) {
                text.append(" [");
                for (int i = 0; i < this.dirtySample.size(); i++) {
                    if (i > 0) text.append(' ');
                    text.append(this.dirtySample.get(i).x()).append(',').append(this.dirtySample.get(i).z());
                }
                if (this.dirty > this.dirtySample.size()) text.append(" +").append(this.dirty - this.dirtySample.size()).append(" more");
                text.append(']');
            }
            text.append(", ").append(this.queuedCommits).append(" queued commit(s) fenced, ")
                .append(this.refusedWrites).append(" refused write(s)");
            switch (this.outcome) {
                case UNCERTAIN -> text.append(", a commit").append(this.uncertainChunks > 0
                        ? " of " + this.uncertainChunks + " chunk(s)" : "")
                    .append(" was sent or running and not confirmed before the deadline: the backend may or may not"
                        + " have applied it");
                case REFUSED -> text.append(", no commit attempt could start before the deadline: these chunks are not"
                    + " on the backend");
                case FAILED -> text.append(", the final commit failed: these chunks are lost unless the backend applied"
                    + " the commit after reporting the failure");
                case CONFIRMED -> { }
            }
            if (!this.released) text.append("; its backend claim (lease) was not released and expires on its own");
            if (this.detail != null) text.append(" (").append(this.detail).append(')');
            return text.toString();
        }
    }

    private final List<StorageResult> results = new CopyOnWriteArrayList<>();
    private final Consumer<String> errors;

    /** @param errors where the per-storage ERROR lines go ({@code SourbyLogger.error} in production) */
    public AwfShutdownReport(final Consumer<String> errors) {
        this.errors = errors;
    }

    /** Records a storage's close; logs one ERROR when its data is not confirmed. */
    public void record(final StorageResult result) {
        this.results.add(result);
        if (!result.confirmed()) {
            this.errors.accept("Aurora World Fabric could not confirm the shutdown commit of " + result.describe());
        }
    }

    public List<StorageResult> results() {
        return List.copyOf(this.results);
    }

    public List<StorageResult> unconfirmed() {
        final List<StorageResult> found = new ArrayList<>();
        for (final StorageResult result : this.results) if (!result.confirmed()) found.add(result);
        return found;
    }

    /**
     * The aggregate alert, or empty when every storage confirmed. Lists, by storage name, the
     * unconfirmed dirty positions, the queued commits the deadline fenced, refused writes and the
     * commits whose outcome is unknown.
     */
    public Optional<String> alert(final long deadlineMillis, final long elapsedMillis) {
        final List<StorageResult> bad = unconfirmed();
        if (bad.isEmpty()) return Optional.empty();
        long dirty = 0;
        long queued = 0;
        long refused = 0;
        int uncertain = 0;
        final List<String> uncertainNames = new ArrayList<>();
        for (final StorageResult result : bad) {
            dirty += result.dirty();
            queued += result.queuedCommits();
            refused += result.refusedWrites();
            if (result.outcome() == Outcome.UNCERTAIN) {
                uncertain++;
                uncertainNames.add(result.storage());
            }
        }
        final StringBuilder text = new StringBuilder(INCOMPLETE_MARKER)
            .append(": Aurora World Fabric did not confirm every storage at shutdown (deadline ").append(deadlineMillis)
            .append(" ms, ").append(elapsedMillis).append(" ms since shutdown began). Not confirmed: ").append(bad.size())
            .append(" storage(s), ").append(dirty).append(" dirty chunk position(s), ").append(queued)
            .append(" queued commit(s) fenced, ").append(refused).append(" refused write(s), ").append(uncertain)
            .append(" uncertain commit(s)").append(uncertainNames.isEmpty() ? "" : " " + uncertainNames)
            .append(". The next boot loads whatever the backend holds; uncertain commits may or may not be in it.");
        for (final StorageResult result : bad) text.append(System.lineSeparator()).append("  - ").append(result.describe());
        return Optional.of(text.toString());
    }
}
