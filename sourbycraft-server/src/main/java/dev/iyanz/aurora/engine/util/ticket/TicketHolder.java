package dev.iyanz.aurora.engine.util.ticket;

import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Holder of a ticket, which provides utilities for submitting actions to the holder object.
 *
 * @param <T>
 *     the ticket type, must be a record
 *
 * @author dueris
 */
public class TicketHolder<T extends Record> {
    @Nullable
    private volatile T ticket;

    /**
     * Fetches the current ticket
     *
     * @return the active ticket
     */
    @Nullable
    public T get() {
        return this.ticket;
    }

    /**
     * Fetches and clears the current ticket, atomically with respect to {@link #propagate}: a
     * ticket propagated concurrently is either returned here or left in the holder, never cleared
     * unseen (which used to lose a save ticket, so its {@code save-all} or world save never
     * finished).
     *
     * @return the active ticket
     */
    @Nullable
    public T pop() {
        synchronized (this) {
            final T ticket = this.ticket;
            this.ticket = null;
            return ticket;
        }
    }

    /**
     * Fetches and clears the current ticket if present, throws otherwise
     *
     * @return the active ticket
     *
     * @throws IllegalStateException
     *     if not present
     */
    public T popOrThrow() {
        return Objects.requireNonNull(pop(), "not propagated");
    }

    /**
     * Consumes the active ticket if contained, removing the current ticket and processing it with the
     * {@link Consumer<T>} provided
     *
     * @param ifContained
     *     the consumer
     */
    public void consumeIfPresent(final Consumer<T> ifContained) {
        // fetch and remove, if not null consume ticket
        final T popped = pop();
        if (popped != null) {
            ifContained.accept(popped);
        }
    }

    /**
     * Returns if there is an active ticket currently
     *
     * @return if a ticket is present
     */
    public boolean isPresent() {
        return this.ticket != null;
    }

    /**
     * Pushes a ticket object to the holder. When a ticket is already present (a second
     * {@code save-all} before the region ticked, say) and it is a {@link CoalescingTicket}, the two
     * are merged into one ticket that runs both callbacks.
     *
     * @param ticket
     *     the new ticket
     *
     * @throws IllegalStateException
     *     when a ticket that cannot coalesce is already present
     */
    @SuppressWarnings("unchecked")
    public void propagate(final T ticket) {
        Objects.requireNonNull(ticket, "ticket");
        synchronized (this) {
            final T present = this.ticket;
            if (present == null) {
                this.ticket = ticket;
            } else if (present instanceof CoalescingTicket<?> coalescing) {
                this.ticket = (T) ((CoalescingTicket) coalescing).coalesce(ticket);
            } else {
                throw new IllegalStateException("Ticket already propagated");
            }
        }
    }

    /**
     * Moves the current ticket, if any, into {@code into}, coalescing with a ticket already there.
     * Used when a region merges into another before it consumed its save ticket: the merged
     * chunks are saved, and the ticket reported, by the region that now owns them (audit ENG-5).
     *
     * @return whether a ticket was moved
     */
    public boolean forwardTo(final TicketHolder<T> into) {
        final T ticket = pop();
        if (ticket == null) return false;
        into.propagate(ticket);
        return true;
    }

    /**
     * Fetches the current ticket if present, throws otherwise
     *
     * @return the active ticket
     *
     * @throws IllegalStateException
     *     if not present
     */
    public T getOrThrow() {
        return Objects.requireNonNull(get(), "not propagated");
    }
}
