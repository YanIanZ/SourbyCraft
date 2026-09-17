package dev.iyanz.sourbycraft.execution;

/**
 * Whether an owner took responsibility for work handed to it.
 *
 * <p>The distinction matters because it decides who releases the caller's bookkeeping.
 * {@link #ACCEPTED} means exactly one of delivery or retirement will run later, and the
 * caller must not release anything itself. {@link #REJECTED} means neither will ever run.</p>
 */
public enum Admission {
    /** The owner will run exactly one of the delivery or the retirement callback. */
    ACCEPTED,
    /** The owner refused the work; no callback will run. */
    REJECTED
}
