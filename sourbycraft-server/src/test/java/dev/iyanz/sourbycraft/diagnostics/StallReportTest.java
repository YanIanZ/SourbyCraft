package dev.iyanz.sourbycraft.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StallReportTest {

    private static StallReport.Context context(final StallRecorder.Kind kind, final String globalMarker) {
        final StallRecorder.Stalled stalled = new StallRecorder.Stalled("global tick", true,
            TimeUnit.SECONDS.toNanos(12L), TimeUnit.SECONDS.toNanos(11L));
        return new StallReport.Context(new StallRecorder.DumpRequest(3L, 2, 6, kind, List.of(stalled),
            TimeUnit.SECONDS.toNanos(14L), null), 1_790_000_000_000L, 10L, 10L, globalMarker,
            "RegionScheduleHandle", List.of("  world: 2 region(s), 40 chunks, 12 entities, 1 players"));
    }

    @Test
    void renderHasEverySectionAndTheThreadsOfThisJvm() {
        final String text = StallReport.render(context(StallRecorder.Kind.REPEAT, "NoSuchTickHandle"));
        for (final String heading : List.of("=== SourbyCraft stall recorder: stall #3, dump 2 of at most 6 ===",
            "the global tick has completed no tick for 12.0 s (a tick has been running for 11.0 s)",
            "--- the stalled thread(s) ---", "--- memory ---", "heap:", "non-heap:", "container/host memory limit:",
            "--- garbage collection", "--- load ---", "--- loaded chunks, entities, players",
            "world: 2 region(s), 40 chunks, 12 entities, 1 players", "--- deadlocks ---", "--- all threads (",
            "=== end of dump 2 ===")) {
            assertTrue(text.contains(heading), "missing: " + heading + "\n" + text.substring(0, Math.min(text.length(), 3000)));
        }
        assertTrue(text.contains("\"" + Thread.currentThread().getName() + "\""), "this thread is in the dump");
        assertTrue(text.contains("   at ") && text.contains(StallReportTest.class.getName() + ".renderHasEverySection"), "full stacks, not the JDK's 8-frame toString");
        assertTrue(text.contains("no thread has its tick code on the stack"), "global stalled but no matching thread says so");
    }

    @Test
    void theFinalDumpSaysWhy() {
        final String text = StallReport.render(context(StallRecorder.Kind.FINAL, "x"));
        assertTrue(text.contains("FINAL: the JVM is shutting down while the stall is still open"), text);
    }

    @Test
    void theStalledThreadIsFoundByAFrameMarkerAndShownInFull() throws Exception {
        final CountDownLatch inside = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread ticking = new Thread(() -> GlobalTickStandIn.tick(inside, release), "stand-in global tick thread");
        ticking.setDaemon(true);
        ticking.start();
        try {
            assertTrue(inside.await(10, TimeUnit.SECONDS));
            final String text = StallReport.render(context(StallRecorder.Kind.FIRST, "GlobalTickStandIn"));
            final int start = text.indexOf("--- the stalled thread(s) ---");
            final int end = text.indexOf("--- memory ---");
            final String stalledSection = text.substring(start, end);
            assertTrue(stalledSection.contains("Thread(s) running the global tick"), stalledSection);
            assertTrue(stalledSection.contains("\"stand-in global tick thread\""), stalledSection);
            assertTrue(stalledSection.contains("GlobalTickStandIn.tick"), stalledSection);
            assertFalse(stalledSection.contains("no thread has its tick code"), stalledSection);
        } finally {
            release.countDown();
            ticking.join(10_000L);
        }
    }

    @Test
    void aThreadBlockedOnAMonitorShowsTheLockAndItsOwner() throws Exception {
        final Object lock = new Object();
        final CountDownLatch held = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread owner = new Thread(() -> {
            synchronized (lock) {
                held.countDown();
                await(release);
            }
        }, "lock-owner-thread");
        owner.setDaemon(true);
        owner.start();
        assertTrue(held.await(10, TimeUnit.SECONDS));
        final Thread blocked = new Thread(() -> {
            synchronized (lock) {
                // acquired after release
            }
        }, "lock-waiter-thread");
        blocked.setDaemon(true);
        blocked.start();
        try {
            ThreadInfo waiter = null;
            for (int i = 0; i < 2000 && (waiter == null || waiter.getThreadState() != Thread.State.BLOCKED); ++i) {
                waiter = ManagementFactory.getThreadMXBean().getThreadInfo(blocked.threadId(), Integer.MAX_VALUE);
                if (waiter.getThreadState() != Thread.State.BLOCKED) {
                    Thread.onSpinWait();
                    Thread.sleep(5L); // polling a real thread's state, not timing a code path
                }
            }
            final ThreadInfo[] all = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);
            String waiterText = null;
            String ownerText = null;
            for (final ThreadInfo info : all) {
                if (info.getThreadName().equals("lock-waiter-thread")) waiterText = StallReport.formatThread(info, 5_000_000L);
                if (info.getThreadName().equals("lock-owner-thread")) ownerText = StallReport.formatThread(info, -1L);
            }
            assertTrue(waiterText.contains("state=BLOCKED"), waiterText);
            assertTrue(waiterText.contains("held by \"lock-owner-thread\""), waiterText);
            assertTrue(waiterText.contains("cpu=5.0ms"), waiterText);
            assertTrue(ownerText.contains("- locked <"), "the owner shows the monitor it holds: " + ownerText);
        } finally {
            release.countDown();
            owner.join(10_000L);
            blocked.join(10_000L);
        }
    }

    @Test
    void formatThreadPrintsEveryFrameNotJustEight() throws Exception {
        final CountDownLatch bottom = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final Thread deep = new Thread(() -> recurse(40, bottom, release), "deep-stack-thread");
        deep.setDaemon(true);
        deep.start();
        try {
            assertTrue(bottom.await(10, TimeUnit.SECONDS));
            final ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(deep.threadId(), Integer.MAX_VALUE);
            final String text = StallReport.formatThread(info, -1L);
            assertTrue(text.split("\n").length > 40, text);
        } finally {
            release.countDown();
            deep.join(10_000L);
        }
    }

    @Test
    void stackContainsIgnoresAnEmptyMarker() {
        final ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(Thread.currentThread().threadId(), Integer.MAX_VALUE);
        assertTrue(StallReport.stackContains(info, "StallReportTest"));
        assertFalse(StallReport.stackContains(info, ""));
        assertFalse(StallReport.stackContains(info, null));
        assertEquals("1.0 MiB", StallReport.mib(1048576L));
        assertEquals("?", StallReport.mib(-1L));
    }

    private static void recurse(final int depth, final CountDownLatch bottom, final CountDownLatch release) {
        if (depth == 0) {
            bottom.countDown();
            await(release);
            return;
        }
        recurse(depth - 1, bottom, release);
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stands in for the engine's global tick handle: only its class name matters to the marker. */
    static final class GlobalTickStandIn {
        static void tick(final CountDownLatch inside, final CountDownLatch release) {
            inside.countDown();
            await(release);
        }
    }
}
