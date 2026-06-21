package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.util.VirtualExecutor;

import java.util.concurrent.CompletableFuture;

/**
 * P6 Async Packet & World subsystems.
 *
 * <p>Helper for off-main-thread data persistence (plugin data files,
 * region snapshots, chunk-system metadata). Routes work onto
 * {@link VirtualExecutor} so the main tick loop never blocks on disk
 * I/O.
 *
 * <p>Combined with patch 0033 (non-flush packet send) and patch 0019
 * (virtual-thread server pools), P6 is end-to-end on the
 * write-and-emit side. Read-side async coverage is owned by Moonrise.
 */
public final class AsyncDataSaver {

    private AsyncDataSaver() {}

    /** Run a save-side IO task on a virtual thread. */
    public static CompletableFuture<Void> save(final Runnable ioTask) {
        return VirtualExecutor.supply(() -> {
            ioTask.run();
            return null;
        });
    }
}
