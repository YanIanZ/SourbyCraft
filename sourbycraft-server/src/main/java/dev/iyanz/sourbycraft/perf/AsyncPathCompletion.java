package dev.iyanz.sourbycraft.perf;

import io.papermc.paper.threadedregions.EntityScheduler;

/** Completes an async solve on the entity owner, with cleanup on every retirement path. */
public final class AsyncPathCompletion {
    private AsyncPathCompletion() {}

    public static void schedule(final EntityScheduler scheduler, final Runnable apply, final Runnable release) {
        try {
            // Always enqueue: unlike scheduleOrExecute this provides a retirement callback
            // for an entity removed after admission but before the next owning-region tick.
            if (!scheduler.schedule(entity -> {
                release.run();
                apply.run();
            }, entity -> release.run(), 1L)) {
                release.run();
            }
        } catch (final RuntimeException failure) {
            release.run();
        }
    }
}
