package io.papermc.paper.threadedregions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ca.spottedleaf.common.util.TimeUtil;
import dev.iyanz.sourbycraft.perf.SchedulerQueueCollector;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

/**
 * The late-tick-start counters behind /perf scheduler (feature patch "lock-free region task queue
 * depth and late tick counts"). The counters are JVM-wide statics, so every assertion is a delta.
 */
@org.bukkit.support.environment.Normal
class TickLateStartCounterTest {

    private static final long MS = 1_000_000L;

    private record Counts(long regionStarted, long regionLate, long globalStarted, long globalLate) {
        static Counts now() {
            return new Counts(TickRegionScheduler.sourbyRegionTicksStarted(), TickRegionScheduler.sourbyLateRegionTicks(),
                TickRegionScheduler.sourbyGlobalTicksStarted(), TickRegionScheduler.sourbyLateGlobalTicks());
        }

        Counts minus(final Counts before) {
            return new Counts(this.regionStarted - before.regionStarted, this.regionLate - before.regionLate,
                this.globalStarted - before.globalStarted, this.globalLate - before.globalLate);
        }
    }

    @Test
    void theEngineThresholdIsTheViewsThreshold() {
        assertEquals(SchedulerQueueCollector.OVERDUE_THRESHOLD_NANOS, TickRegionScheduler.SOURBY_LATE_TICK_THRESHOLD_NANOS);
    }

    @Test
    void aRegionTickIsLateOnlyStrictlyBeyondTheThreshold() {
        final Counts before = Counts.now();
        final long scheduled = 50_000 * MS;
        TickRegionScheduler.sourbyCountTickStart(false, scheduled, scheduled);                       // on time
        TickRegionScheduler.sourbyCountTickStart(false, scheduled, scheduled - 5 * MS);              // early
        TickRegionScheduler.sourbyCountTickStart(false, scheduled, scheduled + MS);                  // exactly 1 ms: jitter
        TickRegionScheduler.sourbyCountTickStart(false, scheduled, scheduled + MS + 1);              // late
        TickRegionScheduler.sourbyCountTickStart(false, scheduled, scheduled + 40 * MS);             // late

        assertEquals(new Counts(5, 2, 0, 0), Counts.now().minus(before));
    }

    @Test
    void globalAndRegionTicksAreCountedApartAndAnUnsetDeadlineIsNotCounted() {
        final Counts before = Counts.now();
        TickRegionScheduler.sourbyCountTickStart(true, 0L, 10 * MS);
        TickRegionScheduler.sourbyCountTickStart(true, 0L, 0L);
        TickRegionScheduler.sourbyCountTickStart(false, TimeUtil.DEADLINE_NOT_SET, 10 * MS);
        TickRegionScheduler.sourbyCountTickStart(true, TimeUtil.DEADLINE_NOT_SET, 10 * MS);

        assertEquals(new Counts(0, 0, 2, 1), Counts.now().minus(before));
    }

    @Test
    void runTickCountsALateStartAndAnOnTimeStartOnTheSchedulerThread() throws Exception {
        final TestHandle handle = new TestHandle();
        final Counts before = Counts.now();

        // Scheduled 50 ms ago: the tick starts late.
        handle.scheduleAt(System.nanoTime() - 50 * MS);
        assertTrue(runOnTickThread(handle));
        // Scheduled far in the future: started early, so not late.
        handle.scheduleAt(System.nanoTime() + 60_000 * MS);
        assertTrue(runOnTickThread(handle));

        // A handle without a region is the global tick's shape (RegionizedServer.GlobalTickTickHandle).
        assertEquals(new Counts(0, 0, 2, 1), Counts.now().minus(before));
        assertEquals(2, handle.ticks);
    }

    @Test
    void aCancelledHandleThatDoesNotTickIsNotCounted() throws Exception {
        final TestHandle handle = new TestHandle();
        handle.scheduleAt(System.nanoTime() - 50 * MS);
        handle.markNonSchedulable();
        final Counts before = Counts.now();

        assertFalse(runOnTickThread(handle));

        assertEquals(new Counts(0, 0, 0, 0), Counts.now().minus(before));
        assertEquals(0, handle.ticks);
    }

    /** runTick insists on a TickThreadRunner, as the scheduler's own threads are. */
    private static boolean runOnTickThread(final TestHandle handle) throws Exception {
        final AtomicReference<Object> result = new AtomicReference<>();
        final Thread thread = new TickRegionScheduler.TickThreadRunner(Thread.currentThread().getThreadGroup(), () -> {
            try {
                result.set(handle.runTick());
            } catch (final Throwable failure) {
                result.set(failure);
            }
        }, "test-tick-runner");
        thread.start();
        thread.join();
        if (result.get() instanceof Throwable failure) {
            throw new AssertionError("runTick failed", failure);
        }
        return (Boolean) result.get();
    }

    private static final class TestHandle extends TickRegionScheduler.RegionScheduleHandle {
        int ticks;

        TestHandle() {
            super(null, TimeUtil.DEADLINE_NOT_SET);
        }

        void scheduleAt(final long start) {
            this.updateScheduledStart(start);
        }

        @Override
        protected boolean tryMarkTicking() {
            return true;
        }

        @Override
        protected boolean markNotTicking() {
            return true;
        }

        @Override
        protected void tickRegion(final long tickCount, final long startTime, final long scheduledEnd) {
            ++this.ticks;
        }

        @Override
        protected void runRegionTasks(final BooleanSupplier canContinue) {}

        @Override
        protected boolean hasIntermediateTasks() {
            return false;
        }
    }
}
