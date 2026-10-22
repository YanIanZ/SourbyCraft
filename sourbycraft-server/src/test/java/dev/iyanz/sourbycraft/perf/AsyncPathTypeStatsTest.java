package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Per-entity-type async path counters: attribution, the key cap, and the capture/submit hand-off. */
class AsyncPathTypeStatsTest {

    @BeforeEach
    void clean() {
        AsyncPathProcessor.shutdown();
        AsyncPathTypeStats.resetForTest();
    }

    @AfterEach
    void restore() {
        AsyncPathProcessor.shutdown();
        AsyncPathTypeStats.resetForTest();
    }

    private static AsyncPathTypeStats.TypeCount row(final String type) {
        return AsyncPathTypeStats.top(1000).stream().filter(r -> r.type().equals(type))
            .findFirst().orElse(null);
    }

    @Test
    void aSolveIsAdmittedAndSolvedUnderTheNotedType() throws Exception {
        AsyncPathProcessor.setEnabled(true);
        AsyncPathTypeStats.noteSubject("minecraft:zombie");
        final CompletableFuture<String> done = AsyncPathProcessor.trySubmit(() -> "path");
        assertNotNull(done);
        assertEquals("path", done.get(5, TimeUnit.SECONDS));

        final var zombie = row("minecraft:zombie");
        assertNotNull(zombie);
        assertEquals(1, zombie.admitted());
        assertEquals(0, zombie.refused());
        // solved is counted in the task's finally, which runs before the future completes
        assertEquals(1, zombie.solved());
    }

    @Test
    void theNotedSubjectIsConsumedByOneSubmitOnly() throws Exception {
        AsyncPathProcessor.setEnabled(true);
        AsyncPathTypeStats.noteSubject("minecraft:creeper");
        AsyncPathProcessor.trySubmit(() -> 1).get(5, TimeUnit.SECONDS);
        // No capture before this one: it must not inherit the creeper.
        AsyncPathProcessor.trySubmit(() -> 2).get(5, TimeUnit.SECONDS);

        assertEquals(1, row("minecraft:creeper").admitted());
        assertEquals(1, row(AsyncPathTypeStats.UNKNOWN).admitted());
    }

    @Test
    void theNotedSubjectIsPerThread() throws Exception {
        AsyncPathTypeStats.noteSubject("minecraft:cow");
        final String[] seen = new String[1];
        final Thread other = new Thread(() -> seen[0] = AsyncPathTypeStats.takeSubject());
        other.start();
        other.join();
        assertEquals(AsyncPathTypeStats.UNKNOWN, seen[0]);
        assertEquals("minecraft:cow", AsyncPathTypeStats.takeSubject());
    }

    @Test
    void aRefusalWhileStoppedIsCountedAndConsumesTheSubject() {
        AsyncPathProcessor.shutdown(); // terminal: refuses until started again
        AsyncPathTypeStats.noteSubject("minecraft:skeleton");
        assertNull(AsyncPathProcessor.trySubmit(() -> "never"));

        final var skeleton = row("minecraft:skeleton");
        assertNotNull(skeleton);
        assertEquals(1, skeleton.refused());
        assertEquals(0, skeleton.admitted());
        assertEquals(AsyncPathTypeStats.UNKNOWN, AsyncPathTypeStats.takeSubject(), "consumed by the refusal");
    }

    @Test
    void aSaturatedQueueRefusesUnderTheTypeAndARejectionIsNotAlsoAnAdmission() throws Exception {
        AsyncPathProcessor.setEnabled(true);
        final CountDownLatch hold = new CountDownLatch(1);
        final int workers = AsyncPathProcessor.poolThreads();
        final CountDownLatch busy = new CountDownLatch(workers);
        final long globalAdmittedBefore = AsyncPathProcessor.stats().admitted();
        final long globalRefusedBefore = AsyncPathProcessor.stats().refused();
        for (int i = 0; i < workers; i++) {
            AsyncPathProcessor.submit(() -> {
                busy.countDown();
                await(hold);
                return null;
            });
        }
        assertTrue(busy.await(5, TimeUnit.SECONDS));
        int accepted = 0;
        int refused = 0;
        try {
            for (int i = 0; i < 100_000 && refused == 0; i++) {
                AsyncPathTypeStats.noteSubject("minecraft:spider");
                if (AsyncPathProcessor.trySubmit(() -> {
                    await(hold);
                    return null;
                }) == null) refused++; else accepted++;
            }
        } finally {
            hold.countDown();
        }
        assertEquals(1, refused, "the bounded queue must eventually refuse");
        final var spider = row("minecraft:spider");
        assertEquals(1, spider.refused());
        assertEquals(accepted, spider.admitted(), "a rejected execution is a refusal, not also an admission");
        assertEquals(globalAdmittedBefore + workers + accepted, AsyncPathProcessor.stats().admitted(),
            "the global counter must not count the rejected submission as admitted either");
        assertEquals(globalRefusedBefore + 1, AsyncPathProcessor.stats().refused());
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void distinctTypesAreCappedAndTheRestShareOther() {
        for (int i = 0; i < AsyncPathTypeStats.MAX_TYPES + 10; i++) {
            AsyncPathTypeStats.slotFor("mod:mob" + i).admitted();
        }
        assertEquals(AsyncPathTypeStats.MAX_TYPES, AsyncPathTypeStats.trackedTypes());
        final var other = row(AsyncPathTypeStats.OTHER);
        assertNotNull(other);
        assertEquals(10, other.admitted());
        // A type tracked before the cap keeps counting under its own key.
        AsyncPathTypeStats.slotFor("mod:mob0").admitted();
        assertEquals(2, row("mod:mob0").admitted());
        assertEquals(AsyncPathTypeStats.MAX_TYPES, AsyncPathTypeStats.trackedTypes());
    }

    @Test
    void topOrdersByDemandThenNameAndAppendsOtherLast() {
        AsyncPathTypeStats.slotFor("a:a").admitted();
        AsyncPathTypeStats.slotFor("b:b").admitted();
        AsyncPathTypeStats.slotFor("b:b").refused();
        AsyncPathTypeStats.slotFor("c:c").admitted();
        AsyncPathTypeStats.slotFor("c:c").admitted();
        AsyncPathTypeStats.slotFor("d:d").admitted();
        for (int i = 0; i < AsyncPathTypeStats.MAX_TYPES; i++) AsyncPathTypeStats.slotFor("fill:" + i).solved();

        final List<AsyncPathTypeStats.TypeCount> top = AsyncPathTypeStats.top(3);
        // b:b and c:c tie at demand 2 (name breaks the tie); a:a and d:d tie at 1.
        assertEquals(List.of("b:b", "c:c", "a:a"),
            top.subList(0, 3).stream().map(AsyncPathTypeStats.TypeCount::type).toList());
        assertEquals(AsyncPathTypeStats.OTHER, top.get(top.size() - 1).type(), "overflow solves land in other");
        assertEquals(4, top.size());
    }

    @Test
    void concurrentFirstUseOfManyTypesNeverExceedsTheCapAndLosesNothing() throws Exception {
        final int threads = 16;
        final int typesPerThread = 20; // 320 distinct keys, far past the cap of 64
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            final CyclicBarrier start = new CyclicBarrier(threads);
            final List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                final int id = t;
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    for (int i = 0; i < typesPerThread; i++) {
                        AsyncPathTypeStats.slotFor("mod:t" + id + "_" + i).admitted();
                    }
                    return null;
                }));
            }
            for (final Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertEquals(AsyncPathTypeStats.MAX_TYPES, AsyncPathTypeStats.trackedTypes(),
            "exactly the cap, never more (and never fewer once enough types raced in)");
        final long total = AsyncPathTypeStats.top(1000).stream()
            .mapToLong(AsyncPathTypeStats.TypeCount::admitted).sum();
        assertEquals((long) threads * typesPerThread, total, "every admission is in a bucket");
        assertEquals((long) threads * typesPerThread - AsyncPathTypeStats.MAX_TYPES,
            row(AsyncPathTypeStats.OTHER).admitted());
    }

    @Test
    void aRequestKeepsTheBucketItWasAdmittedUnder() {
        for (int i = 0; i < AsyncPathTypeStats.MAX_TYPES; i++) AsyncPathTypeStats.slotFor("fill:" + i);
        final var overflowing = AsyncPathTypeStats.slotFor("late:type");
        assertEquals(overflowing, AsyncPathTypeStats.slotFor("late:type"), "same bucket every time");
        overflowing.admitted();
        overflowing.solved();
        final var other = row(AsyncPathTypeStats.OTHER);
        assertEquals(1, other.admitted());
        assertEquals(1, other.solved());
        assertNull(row("late:type"));
    }

    @Test
    void aStaleNotedSubjectCannotSurviveANewCaptureThatNotesNothing() {
        AsyncPathTypeStats.noteSubject("minecraft:pig");
        AsyncPathTypeStats.clearSubject();
        assertEquals(AsyncPathTypeStats.UNKNOWN, AsyncPathTypeStats.takeSubject());
    }
}
