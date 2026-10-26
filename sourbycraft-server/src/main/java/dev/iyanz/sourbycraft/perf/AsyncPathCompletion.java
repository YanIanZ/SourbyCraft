package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.execution.Admission;
import dev.iyanz.sourbycraft.execution.OwnerHandoff;
import java.util.function.Consumer;

/** Completes an async solve on the entity owner, with cleanup on every retirement path. */
public final class AsyncPathCompletion {
    private AsyncPathCompletion() {}

    /**
     * Delivers a completed solve to whichever context owns the subject now.
     *
     * <p>The pending flag is released before the result is applied, so a callback that
     * decides to discard the result still leaves the caller able to submit again.</p>
     *
     * @param owner   the handoff for the subject the solve was computed for
     * @param apply   applies the result, and receives the owner <em>as it is on delivery</em>
     *                so it can check the result is still the right one to apply
     * @param release releases the caller's pending bookkeeping; runs exactly once on every
     *                path, including refusal and retirement
     */
    public static <O> void deliver(final OwnerHandoff<O> owner, final Consumer<? super O> apply,
                                   final Runnable release) {
        deliver(owner, apply, release, () -> {});
    }

    /**
     * As {@link #deliver(OwnerHandoff, Consumer, Runnable)}, and also says when the result never reached the owner.
     *
     * @param retired runs, right after {@code release}, only when {@code apply} never will: in the handoff's
     *                retirement callback (the owner went away after admission) or when the handoff refused the
     *                work. At most once, never together with {@code apply}. It runs in the retirement context
     *                ({@link OwnerHandoff#submit}) or on the thread that called this method, so it may only count or release
     *                bookkeeping: no world, chunk or entity access and no blocking.
     */
    public static <O> void deliver(final OwnerHandoff<O> owner, final Consumer<? super O> apply,
                                   final Runnable release, final Runnable retired) {
        final Admission admission = owner.submit(delivered -> {
            release.run();
            apply.accept(delivered);
        }, () -> {
            release.run();
            retired.run();
        });
        if (admission == Admission.REJECTED) {
            release.run();
            retired.run();
        }
    }
}
