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
