package dev.iyanz.sourbycraft.awf.world;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;

/**
 * Build 55: fires the {@code dev.iyanz.sourbycraft.api.world.event} events of {@link AuroraWorldsService} on one
 * daemon thread of its own, {@code SourbyCraft-AWF-world-events}, behind a queue of {@value #QUEUE} events. One thread
 * keeps the events in the order their outcomes were reached; the queue bound keeps a slow listener from holding
 * memory or the world operation: a full queue drops the event, counts it ({@link #dropped()}) and logs the first drop
 * of each burst. The thread starts with the first event and ends after 30 seconds without one.
 *
 * <p>{@link #fire} only enqueues, so it may be called on any thread, a region or the global tick included, and never
 * throws. A listener that throws is logged and does not stop later events.</p>
 */
final class AwfWorldEvents {

    /** Events waiting for the event thread at most. */
    static final int QUEUE = 1024;

    private final Consumer<Event> sink;
    private final AtomicLong dropped = new AtomicLong();
    /** Whether the drop being counted is the first since the last event that was accepted, so a burst logs once. */
    private volatile boolean dropping;
    private final ThreadPoolExecutor lane;

    /** Events fired through the server's plugin manager. */
    AwfWorldEvents() {
        this(event -> {
            if (Bukkit.getServer() != null) Bukkit.getPluginManager().callEvent(event);
        });
    }

    /** As above with the delivery injected, for tests that run without a server. */
    AwfWorldEvents(final Consumer<Event> sink) {
        this.sink = sink;
        this.lane = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE), task -> {
            final Thread thread = new Thread(task, "SourbyCraft-AWF-world-events");
            thread.setDaemon(true);
            return thread;
        });
        this.lane.allowCoreThreadTimeOut(true);
    }

    /** Queues {@code event} for the event thread. Any thread; never blocks, never throws. */
    void fire(final Event event) {
        try {
            this.lane.execute(() -> {
                try {
                    this.sink.accept(event);
                } catch (final RuntimeException failed) {
                    SourbyLogger.warn("Aurora World Fabric: a listener of " + event.getEventName() + " failed: "
                        + failed.getMessage());
                }
            });
            this.dropping = false;
        } catch (final RejectedExecutionException full) {
            final long total = this.dropped.incrementAndGet();
            if (!this.dropping) {
                this.dropping = true;
                SourbyLogger.warn("Aurora World Fabric: " + event.getEventName() + " was dropped (the world event queue"
                    + " of " + QUEUE + " is full or closed; " + total + " dropped since startup); AuroraWorlds is the"
                    + " source of truth");
            }
        } catch (final RuntimeException unexpected) {
            this.dropped.incrementAndGet();
            SourbyLogger.warn("Aurora World Fabric: " + event.getEventName() + " could not be queued: "
                + unexpected.getMessage());
        }
    }

    /** Events dropped because the queue was full or closed, since startup. */
    long dropped() {
        return this.dropped.get();
    }

    /** Events waiting for the event thread now. */
    int queued() {
        return this.lane.getQueue().size();
    }

    /** Stops accepting events; queued ones are still delivered by the (daemon) thread. */
    void close() {
        this.lane.shutdown();
    }
}
