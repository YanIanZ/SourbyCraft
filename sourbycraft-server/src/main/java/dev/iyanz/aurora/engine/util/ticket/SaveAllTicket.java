package dev.iyanz.aurora.engine.util.ticket;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Represents a ticket for the {@code save-all} command trigger, propagated in
 * {@link io.papermc.paper.threadedregions.TickRegions.TickRegionData#canvas$saveAllTicket}, at the start of each region
 * tick this ticket is consumed and upon consumption the chunks and players in the region in question will be saved and
 * optionally flushed depending on the {@link SaveAllTicket#flush() argument}
 *
 * @param callback
 *     the callback on completion of the save
 * @param exceptionPropagator
 *     the exception callback for if errors occur
 * @param flush
 *     if the region should flush when doing the chunk save or not
 *
 * @author dueris
 */
public record SaveAllTicket(Runnable callback, Consumer<Throwable> exceptionPropagator, boolean flush)
    implements CoalescingTicket<SaveAllTicket> {

    /**
     * Two saves asked of one region before it ticked: one save, flushed if either asked for it,
     * reporting to both callers. Each caller's callback runs even if the other's throws, so no
     * caller (a {@code save-all} countdown, a world save barrier) is left waiting.
     */
    @Override
    public SaveAllTicket coalesce(final SaveAllTicket later) {
        return new SaveAllTicket(RunAll.of(this.callback, later.callback),
            AcceptAll.of(this.exceptionPropagator, later.exceptionPropagator), this.flush || later.flush);
    }

    /**
     * Splits this ticket among the regions a region split into (audit ENG-5): one part per region,
     * each saving its region's chunks with this ticket's flush option. This ticket's callback runs
     * once, when the last part has run its callback, so a {@code save-all} (or a world save
     * barrier) is not told the save finished while a part of the old region is still unsaved.
     * Every part reports failures to this ticket's exception callback.
     *
     * @param parts how many regions; at least 1
     */
    public List<SaveAllTicket> split(final int parts) {
        if (parts < 1) throw new IllegalArgumentException("a split has at least one part");
        if (parts == 1) return List.of(this);
        final java.util.concurrent.atomic.AtomicInteger remaining = new java.util.concurrent.atomic.AtomicInteger(parts);
        final Runnable whole = this.callback;
        final List<SaveAllTicket> split = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            final java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
            split.add(new SaveAllTicket(() -> {
                // A part counts once even if its callback is invoked twice.
                if (done.compareAndSet(false, true) && remaining.decrementAndGet() == 0) whole.run();
            }, this.exceptionPropagator, this.flush));
        }
        return List.copyOf(split);
    }

    /**
     * A ticket that completes {@code future} when its region has saved, off the calling thread
     * (audit ENG-10): the callback can run under the regioniser's write lock (a region destroyed
     * while holding the ticket), and a caller's synchronous dependent of the future must not run
     * there. A failure reported first completes the future exceptionally; the callback that always
     * follows it does not overwrite that.
     */
    public static SaveAllTicket completing(final java.util.concurrent.CompletableFuture<Void> future, final boolean flush) {
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        return new SaveAllTicket(() -> completeOffThread(future, failure.get()),
            thrown -> failure.compareAndSet(null, thrown), flush);
    }

    private static void completeOffThread(final java.util.concurrent.CompletableFuture<Void> future, final Throwable failure) {
        Thread.ofVirtual().name("Aurora save ticket completion").start(() -> {
            if (failure != null) future.completeExceptionally(failure);
            else future.complete(null);
        });
    }

    /**
     * Coalesced callbacks, kept flat rather than nested so a ticket that absorbed many others runs
     * them in order without a deep call chain. Every one runs even if an earlier one throws; the
     * first failure is rethrown afterwards with the others suppressed.
     */
    record RunAll(List<Runnable> callbacks) implements Runnable {
        static RunAll of(final Runnable first, final Runnable later) {
            final List<Runnable> all = new ArrayList<>();
            if (first instanceof RunAll(final List<Runnable> earlier)) all.addAll(earlier);
            else all.add(first);
            if (later instanceof RunAll(final List<Runnable> more)) all.addAll(more);
            else all.add(later);
            return new RunAll(List.copyOf(all));
        }

        @Override
        public void run() {
            RuntimeException failure = null;
            for (final Runnable callback : this.callbacks) {
                try {
                    callback.run();
                } catch (final RuntimeException failed) {
                    if (failure == null) failure = failed;
                    else failure.addSuppressed(failed);
                }
            }
            if (failure != null) throw failure;
        }
    }

    /** As {@link RunAll}, for exception callbacks. */
    record AcceptAll(List<Consumer<Throwable>> callbacks) implements Consumer<Throwable> {
        static AcceptAll of(final Consumer<Throwable> first, final Consumer<Throwable> later) {
            final List<Consumer<Throwable>> all = new ArrayList<>();
            if (first instanceof AcceptAll(final List<Consumer<Throwable>> earlier)) all.addAll(earlier);
            else all.add(first);
            if (later instanceof AcceptAll(final List<Consumer<Throwable>> more)) all.addAll(more);
            else all.add(later);
            return new AcceptAll(List.copyOf(all));
        }

        @Override
        public void accept(final Throwable thrown) {
            RuntimeException failure = null;
            for (final Consumer<Throwable> callback : this.callbacks) {
                try {
                    callback.accept(thrown);
                } catch (final RuntimeException failed) {
                    if (failure == null) failure = failed;
                    else failure.addSuppressed(failed);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
