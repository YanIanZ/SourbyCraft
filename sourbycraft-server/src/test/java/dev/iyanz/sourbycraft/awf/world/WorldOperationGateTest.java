package dev.iyanz.sourbycraft.awf.world;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.api.world.WorldOperationBusyException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class WorldOperationGateTest {
    private static WorldOperationGate.Resource world(final String name) {
        return new WorldOperationGate.Resource("world:" + name, false);
    }

    @Test
    void conflictingWorkFailsBeforeItsBodyRunsButOtherWorldsProceed() {
        final WorldOperationGate gate = new WorldOperationGate();
        final CompletableFuture<Void> saving = new CompletableFuture<>();
        final var save = gate.run("save", List.of(world("a")), () -> saving);
        final var deletion = gate.run("delete", List.of(world("a")), () -> {
            fail("conflicting deletion must never start");
            return CompletableFuture.completedFuture(null);
        });
        final var error = assertInstanceOf(WorldOperationBusyException.class,
            assertThrows(CompletionException.class, deletion::join).getCause());
        assertEquals("world:a", error.resource());
        assertEquals("save", error.activeOperation());
        assertEquals("b", gate.run("load", List.of(world("b")), () -> CompletableFuture.completedFuture("b")).join());
        saving.complete(null);
        save.join();
        assertEquals("a", gate.run("load", List.of(world("a")), () -> CompletableFuture.completedFuture("a")).join());
    }

    @Test
    void parallelClonesShareATemplateAndDeletionWaitsForEveryReader() {
        final WorldOperationGate gate = new WorldOperationGate();
        final var shared = new WorldOperationGate.Resource("template:island", true);
        final var exclusive = new WorldOperationGate.Resource("template:island", false);
        final List<WorldOperationGate.Lease> readers = new ArrayList<>();
        for (int i = 0; i < 100; i++) readers.add(gate.acquire("clone", List.of(world("island_" + i), shared)));
        for (int i = 0; i < 99; i++) readers.get(i).close();
        assertThrows(WorldOperationBusyException.class, () -> gate.acquire("deleteTemplate", List.of(exclusive)));
        readers.get(99).close();
        try (var writer = gate.acquire("deleteTemplate", List.of(exclusive))) {
            assertThrows(WorldOperationBusyException.class, () -> gate.acquire("clone", List.of(shared)));
        }
        gate.acquire("clone", List.of(shared)).close();
    }

    @Test
    void callerCancellationOrManualCompletionCannotReleaseLiveWork() {
        for (final boolean cancel : List.of(true, false)) {
            final WorldOperationGate gate = new WorldOperationGate();
            final CompletableFuture<Void> underlying = new CompletableFuture<>();
            final var exposed = gate.run("import", List.of(world("a")), () -> underlying);
            if (cancel) exposed.cancel(true);
            else exposed.complete(null);
            assertFalse(underlying.isDone());
            assertThrows(WorldOperationBusyException.class, () -> gate.acquire("delete", List.of(world("a"))));
            underlying.complete(null);
            gate.acquire("delete", List.of(world("a"))).close();
        }
    }

    @Test
    void failureReleasesAllResourcesAndPartialAdmissionReservesNothing() {
        final WorldOperationGate gate = new WorldOperationGate();
        final RuntimeException failure = new RuntimeException("I/O");
        final var result = gate.run("import", List.of(world("a")), () -> { throw failure; });
        assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
        try (var busy = gate.acquire("load", List.of(world("b")))) {
            assertThrows(WorldOperationBusyException.class, () -> gate.acquire("export", List.of(world("a"), world("b"))));
            gate.acquire("delete", List.of(world("a"))).close();
        }
        final CompletableFuture<Void> source = new CompletableFuture<>();
        final var async = gate.run("save", List.of(world("a")), () -> source);
        source.completeExceptionally(failure);
        assertSame(failure, assertThrows(CompletionException.class, async::join).getCause());
        gate.acquire("load", List.of(world("a"))).close();
    }

    @Test
    void completionCallbacksCanStartTheNextOperation() {
        final WorldOperationGate gate = new WorldOperationGate();
        final CompletableFuture<String> source = new CompletableFuture<>();
        final var chain = gate.run("load", List.of(world("a")), () -> source)
            .thenCompose(value -> gate.run("save", List.of(world("a")), () -> CompletableFuture.completedFuture(value)));
        source.complete("ready");
        assertEquals("ready", chain.join());
    }

    @Test
    void duplicateResourcesUseExclusiveAccessAndCloseIsIdempotent() {
        final WorldOperationGate gate = new WorldOperationGate();
        final var lease = gate.acquire("publish", List.of(world("a"), new WorldOperationGate.Resource("world:a", true)));
        assertThrows(WorldOperationBusyException.class,
            () -> gate.acquire("read", List.of(new WorldOperationGate.Resource("world:a", true))));
        lease.close();
        lease.close();
        gate.acquire("load", List.of(world("a"))).close();
    }

    @Test
    void simultaneousPluginsCannotBothOwnTheSameWorld() throws Exception {
        final WorldOperationGate gate = new WorldOperationGate();
        final CountDownLatch start = new CountDownLatch(1);
        final CompletableFuture<Void> underlying = new CompletableFuture<>();
        try (var workers = Executors.newFixedThreadPool(2)) {
            final java.util.concurrent.Callable<CompletableFuture<Void>> plugin = () -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return gate.run("create", List.of(world("arena")), () -> underlying);
            };
            final var a = workers.submit(plugin);
            final var b = workers.submit(plugin);
            start.countDown();
            final var first = a.get(5, TimeUnit.SECONDS);
            final var second = b.get(5, TimeUnit.SECONDS);
            assertNotEquals(first.isCompletedExceptionally(), second.isCompletedExceptionally());
            underlying.complete(null);
            gate.acquire("delete", List.of(world("arena"))).close();
        }
    }

    @Test
    void blockingOnUnfinishedWorkIsRefusedWhereFinishingItNeedsTheThread() throws Exception {
        // A staged world creation finishes on a later global tick: joining it there never returns.
        final java.util.concurrent.atomic.AtomicBoolean onGlobal = new java.util.concurrent.atomic.AtomicBoolean(true);
        final WorldOperationGate gate = new WorldOperationGate(onGlobal::get);
        final CompletableFuture<String> staged = new CompletableFuture<>();
        final CompletableFuture<String> exposed = gate.run("create", List.of(world("a")), () -> staged);
        final CompletableFuture<Integer> derived = exposed.thenApply(String::length);
        for (final CompletableFuture<?> future : List.of(exposed, derived)) {
            assertThrows(IllegalStateException.class, future::join);
            assertThrows(IllegalStateException.class, future::get);
            assertThrows(IllegalStateException.class, () -> future.get(1, TimeUnit.MILLISECONDS));
        }
        // Elsewhere it simply waits.
        onGlobal.set(false);
        assertThrows(java.util.concurrent.TimeoutException.class, () -> exposed.get(1, TimeUnit.MILLISECONDS));
        // Finished work is never refused, on any thread.
        onGlobal.set(true);
        staged.complete("world");
        assertEquals("world", exposed.join());
        assertEquals(5, derived.get());
    }

    @Test
    void aGateWithoutAThreadRuleNeverRefusesToWait() {
        final WorldOperationGate gate = new WorldOperationGate();
        final CompletableFuture<String> staged = new CompletableFuture<>();
        final CompletableFuture<String> exposed = gate.run("create", List.of(world("a")), () -> staged);
        assertThrows(java.util.concurrent.TimeoutException.class, () -> exposed.get(1, TimeUnit.MILLISECONDS));
        staged.complete("world");
        assertEquals("world", exposed.join());
    }

    @Test
    void aLeaseHeldPastTheThresholdIsReportedOnceAndAReleasedOneNever() {
        final java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        final WorldOperationGate gate = new WorldOperationGate(() -> false, clock::get);
        final CompletableFuture<Void> stuck = new CompletableFuture<>();
        gate.run("save", List.of(world("a")), () -> stuck);
        final var quick = gate.acquire("load", List.of(world("b")));
        clock.addAndGet(TimeUnit.MINUTES.toNanos(4));
        quick.close();
        assertEquals(List.of(), gate.overdue(WorldOperationGate.OVERDUE_NANOS));
        clock.addAndGet(TimeUnit.MINUTES.toNanos(2));
        final List<String> overdue = gate.overdue(WorldOperationGate.OVERDUE_NANOS);
        assertEquals(1, overdue.size());
        assertTrue(overdue.get(0).startsWith("save on [world:a] for 360 s"), overdue.get(0));
        assertEquals(List.of(), gate.overdue(WorldOperationGate.OVERDUE_NANOS), "reported once");
        stuck.complete(null);
        clock.addAndGet(TimeUnit.MINUTES.toNanos(10));
        assertEquals(List.of(), gate.overdue(WorldOperationGate.OVERDUE_NANOS));
    }
}
