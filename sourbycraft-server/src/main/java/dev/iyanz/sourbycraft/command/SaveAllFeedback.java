package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.awf.AwfEngine;
import dev.iyanz.sourbycraft.awf.AwfFlushReporting;
import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import dev.iyanz.sourbycraft.perf.RegionIoQueue;
import dev.iyanz.sourbycraft.perf.StorageBacklog;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

/**
 * The end of {@code save-all}: replaces its unconditional "Saved the game" (MC feature patch, one
 * call in {@code SaveAllCommand}). Without AWF storages open it sends exactly the vanilla success;
 * with them it follows {@link AwfFlushReporting}: a pending line now, and for {@code save-all flush}
 * the vanilla success only after every AWF storage confirmed a commit of what the save wrote, else a
 * failure naming the storages that are not durable.
 *
 * <p>Threads: called on the region thread that finished the last region save. The wait and the
 * barriers run on a virtual thread (never a region thread); lines sent later are delivered on the
 * global tick through the {@link RegionOwnership} contract.</p>
 */
public final class SaveAllFeedback {

    private SaveAllFeedback() {
    }

    public static void saved(final CommandSourceStack source, final boolean flush) {
        final Thread caller = Thread.currentThread();
        final AwfFlushReporting.Feedback feedback = new AwfFlushReporting.Feedback() {
            @Override
            public void saved() {
                deliver(caller, () -> source.sendSuccess(() -> Component.translatable("commands.save.success"), true));
            }

            @Override
            public void info(final String message) {
                deliver(caller, () -> source.sendSuccess(() -> Component.literal(message), true));
            }

            @Override
            public void failure(final String message) {
                deliver(caller, () -> source.sendFailure(Component.literal(message)));
            }
        };
        AwfFlushReporting.afterRegionSaves(flush, AwfEngine.openStorages(), SaveAllFeedback::chunkIoIdle,
            task -> Thread.ofVirtual().name("SourbyCraft-Storage-awf-save-all").start(task), System::nanoTime,
            AwfFlushReporting.IO_WAIT_NANOS, () -> LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5)), feedback);
    }

    private static void deliver(final Thread caller, final Runnable send) {
        if (Thread.currentThread() == caller) send.run();
        else RegionOwnership.current().executeOnGlobalTick(send);
    }

    /** Whether no world's chunk I/O controller has a queued or running task (the {@link StorageBacklog} contract). */
    private static boolean chunkIoIdle() {
        for (final StorageBacklog.WorldQueue queue : RegionIoQueue.INSTANCE.sample()) {
            if (queue.total() > 0) return false;
        }
        return true;
    }
}
