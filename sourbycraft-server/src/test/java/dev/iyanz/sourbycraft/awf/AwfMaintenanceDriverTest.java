package dev.iyanz.sourbycraft.awf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** AWF-3: AWF upkeep on its own thread, isolated, rate-limited, with a clean stop. */
@Timeout(30)
class AwfMaintenanceDriverTest {

    private static final long SECOND = TimeUnit.SECONDS.toNanos(1L);
    /** Longer than the class timeout: a close that waits where it must not fails the test. */
    private static final long LONG_STOP_WAIT = TimeUnit.SECONDS.toNanos(60L);

    /** Captures the pass instead of running it on a thread. */
    private static final class ManualScheduler implements AwfMaintenanceDriver.Scheduler {
        Runnable pass;
        long period;
        int starts;
        int cancels;

        @Override
        public AwfMaintenanceDriver.Cancellation start(final Runnable pass, final long periodNanos) {
            this.pass = pass;
            this.period = periodNanos;
            ++this.starts;
            return new AwfMaintenanceDriver.Cancellation() {
                @Override
                public void cancel() {
                    ++ManualScheduler.this.cancels;
                }

                @Override
                public boolean await(final long nanos) {
                    return true; // no thread of its own
                }
            };
        }
    }

    private record Log(String message, Throwable failure) {}

    private static AwfMaintenanceDriver driver(final ManualScheduler scheduler, final AtomicLong clock,
                                               final List<Log> logs, final AwfMaintenanceDriver.Step... steps) {
        return driver(scheduler, AwfMaintenanceDriver.STOP_WAIT_NANOS, clock, logs, steps);
    }

    private static AwfMaintenanceDriver driver(final AwfMaintenanceDriver.Scheduler scheduler, final long stopWait,
                                               final AtomicLong clock, final List<Log> logs,
                                               final AwfMaintenanceDriver.Step... steps) {
        final BiConsumer<String, Throwable> logger = (message, failure) -> logs.add(new Log(message, failure));
        return new AwfMaintenanceDriver(List.of(steps), scheduler, SECOND, stopWait, clock::get, logger);
    }

    private static Thread daemon(final Runnable task, final String name) {
        final Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static Runnable awaiting(final CountDownLatch entered, final CountDownLatch release) {
        return () -> {
            entered.countDown();
            try {
                release.await();
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        };
    }

    /** Spins (no sleep) until the condition holds; fails after 10 s. */
    private static void awaitTrue(final BooleanSupplier condition, final String what) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, what);
            Thread.yield();
        }
    }

    private static void join(final Thread... threads) throws InterruptedException {
        for (final Thread thread : threads) {
            if (thread != null) thread.join(10_000L);
        }
    }

    @Test
    void startSchedulesOncePerPeriodAndIsIdempotent() {
        final ManualScheduler scheduler = new ManualScheduler();
        final AtomicInteger runs = new AtomicInteger();
        final AwfMaintenanceDriver driver = driver(scheduler, new AtomicLong(), new ArrayList<>(),
            new AwfMaintenanceDriver.Step("count", runs::incrementAndGet));

        assertFalse(driver.running());
        driver.start();
        driver.start();

        assertEquals(1, scheduler.starts);
        assertEquals(SECOND, scheduler.period);
        assertTrue(driver.running());
        assertEquals(0, runs.get(), "the first pass is the scheduler's, one period later");
        scheduler.pass.run();
        scheduler.pass.run();
        assertEquals(2, runs.get());
        assertEquals(2L, driver.passes());
    }

    @Test
    void aThrowingStepIsLoggedRateLimitedAndTheOtherStepAndNextPassStillRun() {
        final ManualScheduler scheduler = new ManualScheduler();
        final AtomicLong clock = new AtomicLong();
        final List<Log> logs = new ArrayList<>();
        final AtomicInteger after = new AtomicInteger();
        final IllegalStateException boom = new IllegalStateException("lane full");
        final AwfMaintenanceDriver driver = driver(scheduler, clock, logs,
            new AwfMaintenanceDriver.Step("AWF commits", () -> { throw boom; }),
            new AwfMaintenanceDriver.Step("watch", after::incrementAndGet));
        driver.start();

        for (int second = 0; second < 5; ++second) {
            clock.set(second * SECOND);
            scheduler.pass.run();
        }
        assertEquals(5, after.get(), "the step after the failing one ran every pass");
        assertEquals(5L, driver.passes(), "and no pass was lost");
        assertEquals(1, logs.size(), "the failure is logged once, not once a second");
        assertTrue(logs.get(0).message().contains("'AWF commits'"), logs.get(0).message());
        assertEquals(boom, logs.get(0).failure());

        clock.set(AwfMaintenanceDriver.ERROR_LOG_INTERVAL_NANOS - 1L);
        scheduler.pass.run();
        assertEquals(1, logs.size(), "still inside the minute");

        clock.set(AwfMaintenanceDriver.ERROR_LOG_INTERVAL_NANOS);
        scheduler.pass.run();
        assertEquals(2, logs.size(), "a minute after the last report it is logged again");
        assertTrue(logs.get(1).message().contains("5 more failure(s)"), logs.get(1).message());
        assertEquals(7, after.get());
    }

    @Test
    void eachStepIsRateLimitedOnItsOwn() {
        final ManualScheduler scheduler = new ManualScheduler();
        final AtomicLong clock = new AtomicLong();
        final List<Log> logs = new ArrayList<>();
        final AtomicInteger secondFails = new AtomicInteger();
        final AwfMaintenanceDriver driver = driver(scheduler, clock, logs,
            new AwfMaintenanceDriver.Step("first", () -> { throw new IllegalStateException("first"); }),
            new AwfMaintenanceDriver.Step("second", () -> {
                if (secondFails.incrementAndGet() >= 2) throw new IllegalStateException("second");
            }));
        driver.start();
        scheduler.pass.run();
        clock.set(SECOND);
        scheduler.pass.run();

        assertEquals(2, logs.size(), "the second step's first failure is not hidden by the first step's limit");
        assertTrue(logs.get(1).message().contains("'second'"));
    }

    @Test
    void aThrowingLoggerDoesNotStopThePass() {
        final ManualScheduler scheduler = new ManualScheduler();
        final AtomicInteger after = new AtomicInteger();
        final AwfMaintenanceDriver driver = new AwfMaintenanceDriver(List.of(
                new AwfMaintenanceDriver.Step("fails", () -> { throw new IllegalStateException("x"); }),
                new AwfMaintenanceDriver.Step("after", after::incrementAndGet)),
            scheduler, SECOND, () -> 0L, (message, failure) -> { throw new IllegalStateException("logger"); });
        driver.start();
        scheduler.pass.run();
        assertEquals(1, after.get());
    }

    @Test
    void closeIsIdempotentAndNoPassRunsAfterIt() throws Exception {
        final ManualScheduler scheduler = new ManualScheduler();
        final AtomicInteger runs = new AtomicInteger();
        final AwfMaintenanceDriver driver = driver(scheduler, new AtomicLong(), new ArrayList<>(),
            new AwfMaintenanceDriver.Step("count", runs::incrementAndGet));
        driver.start();
        scheduler.pass.run();

        driver.close();
        driver.close();
        assertEquals(1, scheduler.cancels, "the schedule is cancelled exactly once");
        assertFalse(driver.running());
        assertTrue(driver.awaitTerminated(0L));

        // A pass the scheduler had already dequeued when close ran must not reach the steps.
        scheduler.pass.run();
        assertEquals(1, runs.get());
        assertEquals(1L, driver.passes());

        driver.start();
        assertEquals(1, scheduler.starts, "a closed driver does not start again");
    }

    @Test
    void closeBeforeStartNeverSchedules() throws Exception {
        final ManualScheduler scheduler = new ManualScheduler();
        final AwfMaintenanceDriver driver = driver(scheduler, new AtomicLong(), new ArrayList<>(),
            new AwfMaintenanceDriver.Step("noop", () -> {}));
        driver.close();
        driver.start();
        assertEquals(0, scheduler.starts);
        assertEquals(0, scheduler.cancels);
        assertTrue(driver.awaitTerminated(0L));
    }

    @Test
    void closeWaitsForAPassInFlightAndTheRestOfThePassIsSkipped() throws Exception {
        final ManualScheduler scheduler = new ManualScheduler();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final AwfMaintenanceDriver driver = driver(scheduler, LONG_STOP_WAIT, new AtomicLong(), new ArrayList<>(),
            new AwfMaintenanceDriver.Step("slow", () -> {
                awaiting(entered, release).run();
                events.add("first step finished");
            }),
            new AwfMaintenanceDriver.Step("next", () -> events.add("second step ran")));
        driver.start();
        Thread passThread = null;
        Thread closer = null;
        try {
            passThread = daemon(scheduler.pass, "pass");
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            closer = daemon(() -> {
                driver.close();
                events.add("close returned");
            }, "closer");
            awaitTrue(driver::closeWaitingForPass, "close never waited for the pass");
            assertEquals(List.of(), events, "close has not returned while the pass runs");

            release.countDown();
            join(closer, passThread);
            assertEquals(List.of("first step finished", "close returned"), events,
                "the pass ended after its current step, before close returned");
        } finally {
            release.countDown();
            driver.close();
            join(closer, passThread);
        }
    }

    @Test
    void aConcurrentSecondCloseWaitsForTheSameEnd() throws Exception {
        final ManualScheduler scheduler = new ManualScheduler();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final AwfMaintenanceDriver driver = driver(scheduler, LONG_STOP_WAIT, new AtomicLong(), new ArrayList<>(),
            new AwfMaintenanceDriver.Step("slow", () -> {
                awaiting(entered, release).run();
                events.add("pass finished");
            }));
        driver.start();
        Thread passThread = null;
        Thread first = null;
        Thread second = null;
        try {
            passThread = daemon(scheduler.pass, "pass");
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            first = daemon(() -> {
                driver.close();
                events.add("first close returned");
            }, "first closer");
            awaitTrue(driver::closeWaitingForPass, "the first close never waited");
            final Thread secondCloser = daemon(() -> {
                driver.close();
                events.add("second close returned");
            }, "second closer");
            second = secondCloser;
            // A second close that returned early would be TERMINATED here, with its event recorded.
            awaitTrue(() -> secondCloser.getState() == Thread.State.TIMED_WAITING
                || secondCloser.getState() == Thread.State.TERMINATED, "second closer never settled");
            assertEquals(List.of(), events, "neither close returned while the pass runs");

            release.countDown();
            join(first, second, passThread);
            assertEquals("pass finished", events.get(0));
            assertEquals(3, events.size(), events.toString());
        } finally {
            release.countDown();
            driver.close();
            join(first, second, passThread);
        }
    }

    @Test
    void aCloseThatOverrunsItsWaitIsLoggedAndThePassEndsAfterItsCurrentStep() throws Exception {
        final ManualScheduler scheduler = new ManualScheduler();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<Log> logs = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger second = new AtomicInteger();
        final AwfMaintenanceDriver driver = driver(scheduler, TimeUnit.MILLISECONDS.toNanos(1L), new AtomicLong(), logs,
            new AwfMaintenanceDriver.Step("stuck", awaiting(entered, release)),
            new AwfMaintenanceDriver.Step("next", second::incrementAndGet));
        driver.start();
        Thread passThread = null;
        try {
            passThread = daemon(scheduler.pass, "pass");
            assertTrue(entered.await(10, TimeUnit.SECONDS));

            driver.close(); // gives up after the 1 ms stop wait
            assertEquals(1, logs.size());
            assertTrue(logs.get(0).message().contains("still running"), logs.get(0).message());
            assertFalse(driver.awaitTerminated(0L), "not quiescent while the stuck step runs");

            release.countDown();
            assertTrue(driver.awaitTerminated(TimeUnit.SECONDS.toNanos(10L)), "the pass ends after its step");
            assertEquals(0, second.get(), "no step after the close started");
        } finally {
            release.countDown();
            join(passThread);
        }
    }

    @Test
    void closeFromInsideAStepDoesNotWaitForItselfAndEndsThePass() throws Exception {
        final AtomicReference<AwfMaintenanceDriver> self = new AtomicReference<>();
        final AtomicInteger afterClose = new AtomicInteger();
        final AtomicReference<Thread> worker = new AtomicReference<>();
        final CountDownLatch closed = new CountDownLatch(1);
        final AwfMaintenanceDriver driver = driver(AwfMaintenanceDriver.daemonThread(), LONG_STOP_WAIT,
            new AtomicLong(), new ArrayList<>(),
            new AwfMaintenanceDriver.Step("closes", () -> {
                worker.set(Thread.currentThread());
                self.get().close(); // would wait 60 s for its own thread if it did not know
                closed.countDown();
            }),
            new AwfMaintenanceDriver.Step("after", afterClose::incrementAndGet));
        self.set(driver);
        try {
            driver.start();
            assertTrue(closed.await(10, TimeUnit.SECONDS), "close returned on the driver's own thread");
            assertTrue(driver.awaitTerminated(TimeUnit.SECONDS.toNanos(10L)));
            assertEquals(0, afterClose.get(), "the pass ended after the step that closed it");
            assertEquals(1L, driver.passes());
            worker.get().join(10_000L);
            assertFalse(worker.get().isAlive(), "the driver's thread ended");
        } finally {
            driver.close();
        }
    }

    @Test
    void aPassRequestedWhileOneRunsIsSkippedNotRunConcurrently() throws Exception {
        final ManualScheduler scheduler = new ManualScheduler();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger inside = new AtomicInteger();
        final AtomicInteger maxInside = new AtomicInteger();
        final AtomicInteger runs = new AtomicInteger();
        final AwfMaintenanceDriver driver = driver(scheduler, new AtomicLong(), new ArrayList<>(),
            new AwfMaintenanceDriver.Step("commits", () -> {
                maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                runs.incrementAndGet();
                try {
                    awaiting(entered, release).run();
                } finally {
                    inside.decrementAndGet();
                }
            }));
        driver.start();
        Thread first = null;
        try {
            first = daemon(scheduler.pass, "first pass");
            assertTrue(entered.await(10, TimeUnit.SECONDS));

            scheduler.pass.run(); // returns at once instead of running the step a second time
            assertEquals(1, runs.get());
            assertEquals(1L, driver.overlaps());

            release.countDown();
            join(first);
            scheduler.pass.run(); // a later pass runs normally
            assertEquals(2, runs.get());
            assertEquals(1, maxInside.get(), "the step never ran on two threads at once");
        } finally {
            release.countDown();
            driver.close();
            join(first);
        }
    }

    @Test
    void realDaemonThreadRunsWithFixedDelayNeverOverlapsAndEndsOnClose() throws Exception {
        final CountDownLatch twenty = new CountDownLatch(20);
        final AtomicInteger inside = new AtomicInteger();
        final AtomicInteger maxInside = new AtomicInteger();
        final AtomicInteger runs = new AtomicInteger();
        final List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
        final AwfMaintenanceDriver driver = AwfMaintenanceDriverTesting.onDaemonThread(
            TimeUnit.MILLISECONDS.toNanos(1L), () -> {
                maxInside.accumulateAndGet(inside.incrementAndGet(), Math::max);
                threads.add(Thread.currentThread());
                runs.incrementAndGet();
                inside.decrementAndGet();
                twenty.countDown();
            }, (message, failure) -> {});
        try {
            driver.start();
            assertTrue(twenty.await(10, TimeUnit.SECONDS), "20 passes within 10 s at a 1 ms delay");
        } finally {
            driver.close();
        }
        assertTrue(driver.awaitTerminated(0L), "close returned quiescent");
        final int atClose = runs.get();
        final Thread worker = threads.get(0);
        // awaitTermination returns as the worker finishes its last task; let it unwind.
        worker.join(10_000L);
        assertEquals(AwfMaintenanceDriver.THREAD_NAME, worker.getName());
        assertTrue(worker.isDaemon());
        assertTrue(threads.stream().allMatch(thread -> thread == worker), "one thread");
        assertEquals(1, maxInside.get());
        assertFalse(worker.isAlive(), "the thread ended, so no pass can follow");
        assertEquals(atClose, runs.get());
        assertEquals(atClose, driver.passes());
    }

    @Test
    void aSchedulerThatFailsPartWayLeavesNoExecutorRunning() {
        final AtomicReference<ScheduledThreadPoolExecutor> made = new AtomicReference<>();
        final AwfMaintenanceDriver.Scheduler failing = AwfMaintenanceDriver.daemonThread(() -> {
            final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1) {
                @Override
                public ScheduledFuture<?> scheduleWithFixedDelay(final Runnable command, final long initialDelay,
                                                                 final long delay, final TimeUnit unit) {
                    super.scheduleWithFixedDelay(command, initialDelay, delay, unit); // thread now started
                    throw new OutOfMemoryError("simulated");
                }
            };
            made.set(executor);
            return executor;
        });
        final AwfMaintenanceDriver driver = driver(failing, SECOND, new AtomicLong(), new ArrayList<>(),
            new AwfMaintenanceDriver.Step("noop", () -> {}));

        assertThrows(OutOfMemoryError.class, driver::start);
        assertNotNull(made.get());
        assertTrue(made.get().isShutdown(), "the executor was shut down on the failed start");
        assertFalse(driver.running());
        driver.close();
    }

    @Test
    void theStandardDriverIsNotStartedUntilAsked() {
        final AwfMaintenanceDriver driver = AwfMaintenanceDriver.standard();
        assertFalse(driver.running());
        driver.close();
        assertFalse(driver.running());
    }
}
