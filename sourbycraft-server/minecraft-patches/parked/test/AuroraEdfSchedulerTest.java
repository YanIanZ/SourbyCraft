package dev.iyanz.aurora.engine.threadedregions.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.spottedleaf.common.util.TimeUtil;
import ca.spottedleaf.concurrentutil.scheduler.SchedulableTick;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class AuroraEdfSchedulerTest {

    private static AuroraEdfScheduler pool() {
        return new AuroraEdfScheduler(1, task -> {
            final Thread thread = new Thread(task, "aurora-edf-regression");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "scheduler callback did not arrive");
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static void halt(final AuroraEdfScheduler scheduler) {
        scheduler.halt();
        assertTrue(scheduler.join(10_000), "scheduler worker did not stop");
    }

    private static void awaitCondition(final BooleanSupplier condition) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() - deadline < 0, "scheduler wait state did not change");
            LockSupport.parkNanos(250_000L);
        }
    }

    private static final class Tick extends SchedulableTick {
        private final BooleanSupplier action;

        Tick(final long start, final BooleanSupplier action) {
            this.setScheduledStart(start);
            this.action = action;
        }

        @Override public boolean runTick() { return this.action.getAsBoolean(); }
        @Override public boolean hasTasks() { return false; }
        @Override public boolean runTasks(final BooleanSupplier canContinue) {
            throw new AssertionError("EDF must not drain intermediate tasks");
        }
    }

    @Test
    void V38CancellationDuringTickFencesReschedule() {
        final AuroraEdfScheduler scheduler = pool();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch probeRan = new CountDownLatch(1);
        final AtomicInteger runs = new AtomicInteger();
        final SchedulableTick tick = new SchedulableTick() {
            @Override public boolean runTick() {
                final int run = runs.incrementAndGet();
                if (run == 1) {
                    entered.countDown();
                    await(release);
                }
                // A broken cancellation gets another run before the probe, then yields to it.
                this.setScheduledStart(System.nanoTime() + (run == 1
                    ? -TimeUnit.SECONDS.toNanos(1) : TimeUnit.DAYS.toNanos(1)));
                return true;
            }
            @Override public boolean hasTasks() { return false; }
            @Override public boolean runTasks(final BooleanSupplier canContinue) { return true; }
        };
        tick.setScheduledStart(System.nanoTime());
        try {
            scheduler.schedule(tick);
            scheduler.start();
            await(entered);
            assertTrue(scheduler.cancel(tick));
            assertFalse(scheduler.cancel(tick));
            scheduler.schedule(new Tick(System.nanoTime(), () -> {
                probeRan.countDown();
                return false;
            }));
            release.countDown();
            await(probeRan); // same worker has returned the cancelled tick before this callback
            assertEquals(1, runs.get());
        } finally {
            release.countDown();
            halt(scheduler);
        }
    }

    @Test
    void V38AwaitingCancellationKeepsReplacementLinkedAndCompletedTaskTerminal() {
        final AuroraEdfScheduler scheduler = pool();
        final AtomicInteger cancelledRuns = new AtomicInteger();
        final AtomicInteger replacementRuns = new AtomicInteger();
        final CountDownLatch barrier = new CountDownLatch(1);
        final long now = System.nanoTime();
        final Tick cancelled = new Tick(now - TimeUnit.SECONDS.toNanos(2), () -> {
            cancelledRuns.incrementAndGet();
            return false;
        });
        final Tick replacement = new Tick(now - TimeUnit.SECONDS.toNanos(1), () -> {
            replacementRuns.incrementAndGet();
            return false;
        });
        try {
            scheduler.schedule(cancelled);
            scheduler.schedule(replacement);
            scheduler.schedule(new Tick(now, () -> {
                barrier.countDown();
                return false;
            }));
            assertTrue(scheduler.cancel(cancelled));
            assertFalse(scheduler.cancel(cancelled));
            scheduler.start();
            await(barrier);
            assertEquals(0, cancelledRuns.get());
            assertEquals(1, replacementRuns.get());
            assertFalse(scheduler.cancel(replacement));
        } finally {
            halt(scheduler);
        }
    }

    @Test
    void V38LaterDeadlineHandsOffToEarlierQueuedTask() {
        final AuroraEdfScheduler scheduler = pool();
        final AtomicInteger deferredRuns = new AtomicInteger();
        final CountDownLatch earlierRan = new CountDownLatch(1);
        final long now = System.nanoTime();
        final Tick deferred = new Tick(now - TimeUnit.SECONDS.toNanos(2), () -> {
            deferredRuns.incrementAndGet();
            return false;
        });
        try {
            scheduler.schedule(deferred);
            scheduler.schedule(new Tick(now - TimeUnit.SECONDS.toNanos(1), () -> {
                earlierRan.countDown();
                return false;
            }));
            assertTrue(scheduler.updateTickStartToMax(deferred, now + TimeUnit.DAYS.toNanos(1)));
            scheduler.start();
            await(earlierRan);
            assertEquals(0, deferredRuns.get());
            assertTrue(scheduler.cancel(deferred));
        } finally {
            halt(scheduler);
        }
    }

    @Test
    void V38RetimingOnlyAwaitingTaskPreservesPreemption() {
        final AuroraEdfScheduler scheduler = pool();
        final AtomicInteger deferredRuns = new AtomicInteger();
        final CountDownLatch earlierRan = new CountDownLatch(1);
        final long now = System.nanoTime();
        final Tick deferred = new Tick(now - TimeUnit.SECONDS.toNanos(1), () -> {
            deferredRuns.incrementAndGet();
            return false;
        });
        try {
            scheduler.schedule(deferred);
            assertTrue(scheduler.updateTickStartToMax(deferred, now + TimeUnit.DAYS.toNanos(1)));
            assertFalse(scheduler.updateTickStartToMax(deferred, now));
            scheduler.schedule(new Tick(now, () -> {
                earlierRan.countDown();
                return false;
            }));
            scheduler.start();
            await(earlierRan);
            assertEquals(0, deferredRuns.get());
            assertTrue(scheduler.cancel(deferred));
        } finally {
            halt(scheduler);
        }
    }

    @Test
    void V38CancellingParkedTaskReleasesDeadlineBlocker() {
        final AuroraEdfScheduler scheduler = pool();
        final Tick tick = new Tick(System.nanoTime() + TimeUnit.DAYS.toNanos(1), () -> false);
        try {
            scheduler.schedule(tick);
            scheduler.start();
            final Thread worker = scheduler.getCoreThreads()[0];
            awaitCondition(() -> worker.getState() == Thread.State.TIMED_WAITING
                && LockSupport.getBlocker(worker) != null);
            assertTrue(scheduler.cancel(tick));
            awaitCondition(() -> worker.getState() == Thread.State.WAITING
                && LockSupport.getBlocker(worker) == scheduler);
            // No new task or halt was needed to wake the cancelled task's old timed wait.
        } finally {
            halt(scheduler);
        }
    }

    @Test
    void V38InvalidOrHaltedAdmissionDoesNotConsumeTaskAndForeignPoolCannotCancel() {
        assertThrows(IllegalArgumentException.class, () -> new AuroraEdfScheduler(0, Thread::new));
        assertThrows(IllegalArgumentException.class, () -> new AuroraEdfScheduler(-1, Thread::new));
        final AuroraEdfScheduler scheduler = pool();
        final AuroraEdfScheduler other = pool();
        final Tick tick = new Tick(TimeUtil.DEADLINE_NOT_SET, () -> false);
        try {
            assertThrows(IllegalStateException.class, () -> scheduler.schedule(tick));
            tick.setScheduledStart(System.nanoTime() + TimeUnit.DAYS.toNanos(1));
            scheduler.halt();
            assertThrows(IllegalStateException.class, () -> scheduler.schedule(tick));
            other.schedule(tick); // both rejected attempts left the task available
            assertFalse(scheduler.cancel(tick));
            assertFalse(scheduler.updateTickStartToMax(tick, System.nanoTime() + TimeUnit.DAYS.toNanos(2)));
            assertTrue(other.cancel(tick));
            assertFalse(other.cancel(tick));
        } finally {
            halt(scheduler);
            halt(other);
        }
    }
}
