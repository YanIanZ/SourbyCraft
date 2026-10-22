package dev.iyanz.sourbycraft.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The bounded wait behind the legacy synchronous events. Positive cases run the work inside
 * {@code post} so nothing depends on timing; the negative cases use a few milliseconds of bound and
 * a target that never runs the work, or holds it until the test releases it.
 */
class BoundedHandoffTest {

    private static final long LONG = TimeUnit.SECONDS.toNanos(30L);
    private static final long SHORT = TimeUnit.MILLISECONDS.toNanos(20L);

    @Test
    void workRunByTheOwnerCompletesAndIsVisible() {
        final int[] value = new int[1];
        final BoundedHandoff.Result result = BoundedHandoff.run((body, retired) -> {
            body.run();
            return true;
        }, () -> value[0] = 42, LONG, LONG);
        assertEquals(BoundedHandoff.Outcome.COMPLETED, result.outcome());
        assertTrue(result.finished());
        assertEquals(42, value[0]);
    }

    @Test
    void workThatNeverStartsIsAbandonedAndNeverRunsLater() {
        final List<Runnable> queued = new ArrayList<>();
        final AtomicInteger ran = new AtomicInteger();
        final BoundedHandoff.Result result = BoundedHandoff.run((body, retired) -> queued.add(body), ran::incrementAndGet, SHORT, LONG);
        assertEquals(BoundedHandoff.Outcome.NOT_STARTED, result.outcome());
        queued.get(0).run(); // the stalled owner finally gets to it
        assertEquals(0, ran.get(), "an abandoned body must not run");
    }

    @Test
    void workThatOverrunsIsReportedWithoutWaitingForIt() throws InterruptedException {
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final BoundedHandoff.Result result = BoundedHandoff.run((body, retired) -> {
            Thread.ofPlatform().daemon().start(body);
            return true;
        }, () -> {
            try {
                release.await();
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            finished.countDown();
        }, LONG, SHORT);
        assertEquals(BoundedHandoff.Outcome.OVERRAN, result.outcome());
        assertFalse(result.finished());
        release.countDown();
        assertTrue(finished.await(30L, TimeUnit.SECONDS), "the running body is not interrupted, only no longer waited for");
    }

    @Test
    void aRetiredTargetEndsTheWaitAtOnceAndTheBodyNeverRuns() {
        final AtomicInteger ran = new AtomicInteger();
        final List<Runnable> bodies = new ArrayList<>();
        final BoundedHandoff.Result result = BoundedHandoff.run((body, retired) -> {
            bodies.add(body);
            retired.run();
            return true;
        }, ran::incrementAndGet, LONG, LONG);
        assertEquals(BoundedHandoff.Outcome.RETIRED, result.outcome());
        bodies.get(0).run();
        assertEquals(0, ran.get());
    }

    @Test
    void aRefusedPostAndAThrowingPostAreRefusals() {
        assertEquals(BoundedHandoff.Outcome.REFUSED, BoundedHandoff.run((body, retired) -> false, () -> { }, LONG, LONG).outcome());
        final IllegalStateException boom = new IllegalStateException("scheduler gone");
        final BoundedHandoff.Result thrown = BoundedHandoff.run((body, retired) -> {
            throw boom;
        }, () -> { }, LONG, LONG);
        assertEquals(BoundedHandoff.Outcome.REFUSED, thrown.outcome());
        assertSame(boom, thrown.failure());
    }

    @Test
    void aThrowingBodyIsReportedAsFailedWithItsThrowable() {
        final IllegalArgumentException boom = new IllegalArgumentException("listener broke");
        final BoundedHandoff.Result result = BoundedHandoff.run((body, retired) -> {
            body.run();
            return true;
        }, () -> {
            throw boom;
        }, LONG, LONG);
        assertEquals(BoundedHandoff.Outcome.FAILED, result.outcome());
        assertSame(boom, result.failure());
    }

    @Test
    void anInterruptedCallerStopsWaitingAndKeepsItsInterruptFlag() {
        final List<Runnable> queued = new ArrayList<>();
        final AtomicInteger ran = new AtomicInteger();
        Thread.currentThread().interrupt();
        try {
            final BoundedHandoff.Result result = BoundedHandoff.run((body, retired) -> queued.add(body), ran::incrementAndGet, LONG, LONG);
            assertEquals(BoundedHandoff.Outcome.INTERRUPTED, result.outcome());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        queued.get(0).run();
        assertEquals(0, ran.get(), "abandoned on interrupt");
    }
}
