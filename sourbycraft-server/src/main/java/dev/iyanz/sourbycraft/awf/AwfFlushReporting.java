package dev.iyanz.sourbycraft.awf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * What {@code save-all} reports for Aurora World Fabric (audit D4). On a region thread an AWF flush
 * only starts a commit, so the command's "Saved the game" said nothing about AWF; with AWF storages
 * open, the command now reports in two steps:
 * <ol>
 *   <li>when every region has saved: a <b>pending</b> line (never containing "Saved the game", so a
 *       script waiting for that text does not proceed early);</li>
 *   <li>off the region threads: wait until the chunk I/O queue has handed the save's chunks to AWF
 *       (bounded), then one {@link AwfRegionStorage#flushBarrier() barrier} per open storage; vanilla
 *       success only once <i>every</i> barrier confirmed a commit, otherwise a failure naming each
 *       storage that is not durable and how many chunks it still holds only in memory.</li>
 * </ol>
 * Without {@code flush}, nothing is waited for (vanilla {@code save-all} does not promise durability
 * either); success is sent as before, plus one line saying how many AWF chunks wait for the next
 * periodic commit when there are any. With no AWF storage open the command is unchanged.
 */
public final class AwfFlushReporting {

    /** How long the barrier waits for the chunk I/O queue to go idle before it reports the save unconfirmed. */
    public static final long IO_WAIT_NANOS = 30_000_000_000L;

    /** The command's output channel. {@code saved} is the vanilla success ("Saved the game"). */
    public interface Feedback {
        void saved();

        void info(String message);

        void failure(String message);
    }

    private AwfFlushReporting() {
    }

    /** The pending line, sent before any waiting starts. */
    static String pendingMessage(final int storages) {
        return "Aurora World Fabric: waiting for " + storages + " storage(s) to commit what this save wrote (pending;"
            + " success or failure follows)";
    }

    /**
     * Called once every region has saved. Never blocks the caller.
     *
     * @param ioIdle whether the chunk I/O queue has no tasks (every saved chunk has reached its storage)
     * @param waiter runs the wait and the barriers; must not be a region thread
     * @param clock monotonic nanoseconds, for the I/O wait bound
     * @param pause sleeps between I/O idle checks; tests pass a no-op
     * @return the per-storage results once reported
     */
    public static CompletableFuture<List<AwfRegionStorage.FlushResult>> afterRegionSaves(
            final boolean flush, final Collection<AwfRegionStorage> storages, final BooleanSupplier ioIdle,
            final Executor waiter, final LongSupplier clock, final long ioWaitNanos, final Runnable pause,
            final Feedback feedback) {
        final List<AwfRegionStorage> open = new ArrayList<>();
        for (final AwfRegionStorage storage : storages) {
            if (!storage.closed() && !storage.discarding()) open.add(storage);
        }
        if (open.isEmpty()) {
            feedback.saved();
            return CompletableFuture.completedFuture(List.of());
        }
        if (!flush) {
            feedback.saved();
            long dirty = 0;
            for (final AwfRegionStorage storage : open) dirty += storage.dirtyCount();
            if (dirty > 0) {
                feedback.info("Aurora World Fabric: " + dirty + " chunk(s) are in memory and are committed by the next"
                    + " periodic commit; use save-all flush to wait for a confirmed commit");
            }
            return CompletableFuture.completedFuture(List.of());
        }
        feedback.info(pendingMessage(open.size()));
        final CompletableFuture<List<AwfRegionStorage.FlushResult>> done = new CompletableFuture<>();
        try {
            waiter.execute(() -> {
                try {
                    final boolean drained = awaitIdle(ioIdle, clock, ioWaitNanos, pause);
                    final List<CompletableFuture<AwfRegionStorage.FlushResult>> barriers = new ArrayList<>();
                    for (final AwfRegionStorage storage : open) barriers.add(storage.flushBarrier());
                    CompletableFuture.allOf(barriers.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
                        final List<AwfRegionStorage.FlushResult> results = new ArrayList<>();
                        for (int i = 0; i < barriers.size(); i++) {
                            final CompletableFuture<AwfRegionStorage.FlushResult> barrier = barriers.get(i);
                            results.add(barrier.isCompletedExceptionally() || !barrier.isDone()
                                ? new AwfRegionStorage.FlushResult(open.get(i).name(), AwfRegionStorage.FlushStatus.FAILED,
                                    open.get(i).dirtyCount(), "barrier failed")
                                : barrier.join());
                        }
                        report(results, drained, ioWaitNanos, feedback);
                        done.complete(results);
                    });
                } catch (final RuntimeException failed) {
                    feedback.failure("Save NOT confirmed: Aurora World Fabric could not check its storages (" + failed + ")");
                    done.completeExceptionally(failed);
                }
            });
        } catch (final RuntimeException rejected) {
            feedback.failure("Save NOT confirmed: Aurora World Fabric could not start its durability check (" + rejected + ")");
            done.completeExceptionally(rejected);
        }
        return done;
    }

    private static boolean awaitIdle(final BooleanSupplier ioIdle, final LongSupplier clock, final long waitNanos,
                                     final Runnable pause) {
        final long start = clock.getAsLong();
        while (!ioIdle.getAsBoolean()) {
            if (clock.getAsLong() - start >= waitNanos) return false;
            pause.run();
        }
        return true;
    }

    /** Sends the final line: vanilla success only when every storage is durable and the I/O queue drained. */
    static void report(final List<AwfRegionStorage.FlushResult> results, final boolean drained, final long ioWaitNanos,
                       final Feedback feedback) {
        int durable = 0;
        long chunks = 0;
        final List<AwfRegionStorage.FlushResult> failed = new ArrayList<>();
        for (final AwfRegionStorage.FlushResult result : results) {
            switch (result.status()) {
                case DURABLE -> {
                    durable++;
                    chunks += result.chunks();
                }
                case FAILED -> failed.add(result);
                case SKIPPED -> { }
            }
        }
        if (failed.isEmpty() && drained) {
            feedback.saved();
            feedback.info("Aurora World Fabric: " + durable + " storage(s) durable, " + chunks + " chunk(s) committed");
            return;
        }
        final StringBuilder text = new StringBuilder("Save NOT durable: ");
        if (!drained) {
            text.append("chunk I/O did not go idle within ").append(ioWaitNanos / 1_000_000_000L)
                .append(" s, so chunks still queued there are not confirmed");
            if (!failed.isEmpty()) text.append("; ");
        }
        if (!failed.isEmpty()) {
            long dirty = 0;
            for (final AwfRegionStorage.FlushResult result : failed) dirty += result.chunks();
            text.append("Aurora World Fabric could not commit ").append(failed.size()).append(" of ").append(results.size())
                .append(" storage(s), ").append(dirty).append(" chunk(s) only in memory:");
            for (final AwfRegionStorage.FlushResult result : failed) {
                text.append(' ').append(result.storage()).append(" (").append(result.chunks()).append(" dirty: ")
                    .append(result.error()).append(");");
            }
            text.setLength(text.length() - 1);
        }
        text.append(". Commits are retried; run save-all flush again to check.");
        feedback.failure(text.toString());
    }
}
