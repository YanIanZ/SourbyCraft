package dev.iyanz.sourbycraft.execution;

import java.util.function.Consumer;

/**
 * Hands a result computed off-thread back to the context that currently owns a subject.
 *
 * <p>This is the execution contract SourbyCraft depends on, stated without naming a backend.
 * It is deliberately not a {@code Runnable} sink: a generic {@code execute(Runnable)} facade
 * hides the four things that actually make this correct, and adopting one would look like
 * independence while losing them.</p>
 *
 * <ul>
 *   <li><b>Deferral.</b> Delivery never runs inline on the submitting thread, including when
 *       the owner is saturated. Work computed off-thread must not mutate gameplay where it
 *       was computed.</li>
 *   <li><b>Admission.</b> Acceptance and refusal are distinguishable, so the caller knows
 *       whether a callback is still coming and who releases its bookkeeping.</li>
 *   <li><b>Current identity.</b> Delivery supplies the owner as it is <em>now</em>, not the
 *       object captured when the work began. A dimension transfer replaces the underlying
 *       entity, so the captured reference can be a different object than the live one.</li>
 *   <li><b>Retirement.</b> An owner that goes away after admission runs the retirement
 *       callback instead of the delivery, and never both.</li>
 * </ul>
 *
 * <p>Retirement callbacks run in a restricted context: they may release bookkeeping, but must
 * not load worlds or chunks, mutate entities, or block.</p>
 *
 * <p>Delivering the current owner is necessary but not sufficient. The receiving callback
 * still has to decide whether its result is <em>still applicable</em> — whether the owner is
 * the one the work was computed for, and whether the state it assumed still holds. This
 * contract carries the identity needed to make that decision; it does not make it.</p>
 *
 * @param <O> the owner handed to the delivery callback
 */
public interface OwnerHandoff<O> {

    /**
     * Asks the context that currently owns the subject to run {@code delivery}.
     *
     * @param delivery   run on the owning context, with the owner as it is at that moment
     * @param retirement run instead of {@code delivery} if the owner is retired after
     *                   admission; must not load worlds or chunks, mutate entities, or block
     * @return {@link Admission#ACCEPTED} when exactly one callback will run later,
     *         {@link Admission#REJECTED} when neither will
     */
    Admission submit(Consumer<? super O> delivery, Runnable retirement);
}
