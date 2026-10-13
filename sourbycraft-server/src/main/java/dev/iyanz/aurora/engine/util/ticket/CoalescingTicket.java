package dev.iyanz.aurora.engine.util.ticket;

/**
 * A ticket that can absorb a second ticket propagated before the first was consumed, so the
 * holder keeps one ticket that does the work of both instead of refusing the second.
 *
 * @param <T> the ticket type itself
 */
public interface CoalescingTicket<T extends Record & CoalescingTicket<T>> {

    /**
     * One ticket standing for this one and {@code later}: every callback of both runs, and any
     * option one of them asked for is kept.
     */
    T coalesce(T later);
}
