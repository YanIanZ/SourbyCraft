package dev.iyanz.sourbycraft.perf;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import dev.iyanz.sourbycraft.core.AuroraRuntime;
import dev.iyanz.sourbycraft.execution.RegionOwnerHandoff;
import dev.iyanz.sourbycraft.perf.AsyncPathNavigationModel.ManualOwner;
import dev.iyanz.sourbycraft.perf.AsyncPathNavigationModel.Route;
import io.papermc.paper.threadedregions.EntityScheduler;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What happens to an async path solve that is in flight when the world around it changes: the mob is
 * removed, the world unloads, the server shuts down, or the navigation moves on to another path
 * (a moved target is always a replaced path, see {@link AsyncPathValidity}).
 *
 * <p>The real {@link AsyncPathProcessor}, {@link AsyncPathCompletion}, {@link AsyncPathValidity} and
 * {@link EntityScheduler} are used; the navigation glue is {@link AsyncPathNavigationModel}, a mirror
 * of patch 0006 (see its javadoc and {@link AsyncPathPatchShapeTest}). Solves are held by latches and
 * the "next owning tick" is driven by the test: no sleeps.</p>
 *
 * <p>Not covered here, because it needs a bootstrapped server: the patched lambda's own bytecode and a
 * real {@code Mob}'s removal path.</p>
 */
class AsyncPathCancellationTest {
    private static final Object TARGET_A = "target-a";
    private static final Object TARGET_B = "target-b";

    /** A solve held until the test lets it go; optionally deaf to interruption. */
    private static final class Gate {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean ranToEnd = new AtomicBoolean();
        final AtomicBoolean interrupted = new AtomicBoolean();

        Supplier<Route> solve(final Route result, final boolean ignoreInterrupt) {
            return () -> {
                this.started.countDown();
                while (true) {
                    try {
                        this.release.await();
                        break;
                    } catch (final InterruptedException interrupt) {
                        this.interrupted.set(true);
                        if (!ignoreInterrupt) {
                            throw new IllegalStateException("solve interrupted");
                        }
                    }
                }
                this.ranToEnd.set(true);
                return result;
            };
        }

        void awaitStarted() throws InterruptedException {
            assertTrue(this.started.await(10, TimeUnit.SECONDS), "the solve never started");
        }
    }

    private final List<Gate> gates = new ArrayList<>();
    private int baseline;

    private Gate gate() {
        final Gate gate = new Gate();
        this.gates.add(gate);
        return gate;
    }

    @BeforeEach
    void startPool() {
        AsyncPathProcessor.shutdown();
        AsyncPathProcessor.setEnabled(true);
        AsyncPathProcessor.resetFailureLogForTest();
        // A solve from an earlier test that ignored interruption can still be unwinding after
        // shutdown(); taking the baseline before it decrements makes every count here off by one.
        awaitDrained();
        this.baseline = AsyncPathProcessor.outstanding();
    }

    @AfterEach
    void stopPool() throws Exception {
        this.gates.forEach(g -> g.release.countDown());
        AsyncPathProcessor.shutdown();
        AsyncPathProcessor.resetFailureLogForTest();
        resetRuntime();
        awaitDrained();
    }

    /** Waits (bounded) for in-flight solves of earlier tests to release their count. */
    private static void awaitDrained() {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (AsyncPathProcessor.outstanding() != 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private static void resetRuntime() throws Exception {
        final Method reset = AuroraRuntime.class.getDeclaredMethod("resetForTest");
        reset.setAccessible(true);
        reset.invoke(null);
    }

    /** The decrement runs in a dependent of the result future, just after the result is visible. */
    private void assertOutstandingReturnsToBaseline(final String why) {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (AsyncPathProcessor.outstanding() != this.baseline && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertEquals(this.baseline, AsyncPathProcessor.outstanding(), why);
    }

    private static AsyncPathNavigationModel navigation() {
        return new AsyncPathNavigationModel(new Route(TARGET_A), TARGET_A);
    }

    // ---------------------------------------------------------------------------------------------
    // (a) the mob is removed, dies, or is replaced while its solve is in flight
    // ---------------------------------------------------------------------------------------------

    @Test
    void aMobRemovedWhileItsSolveIsRunningNeverGetsTheResultAndNothingLeaks() throws Exception {
        final var nav = navigation();
        final var owner = new ManualOwner(nav.mob);
        final Route before = nav.path;
        final Gate gate = gate();
        assertTrue(nav.kick(gate.solve(new Route(TARGET_B), false), owner));
        gate.awaitStarted();
        assertTrue(nav.pending.get());
        assertEquals(this.baseline + 1, AsyncPathProcessor.outstanding());

        owner.retire(); // the entity is removed while the worker is still solving
        gate.release.countDown();
        assertTrue(owner.awaitSubmission(), "the finished solve must still reach the owner (and be refused)");

        assertTrue(nav.awaitReleases(1));
        assertEquals(1, owner.rejected, "a retired owner refuses the delivery");
        assertEquals(0, nav.applied.get(), "the result of a removed mob is discarded, not applied");
        assertSame(before, nav.path);
        assertFalse(nav.pending.get(), "pending bookkeeping is released");
        assertEquals(1, nav.releaseCount.get(), "released exactly once");
        assertOutstandingReturnsToBaseline("a discarded solve must not stay counted");
    }

    @Test
    void aMobRemovedAfterTheSolveFinishedButBeforeDeliveryIsRetiredNotApplied() throws Exception {
        final var nav = navigation();
        final var owner = new ManualOwner(nav.mob);
        final Gate gate = gate();
        assertTrue(nav.kick(gate.solve(new Route(TARGET_B), false), owner));
        gate.release.countDown();
        assertTrue(owner.awaitSubmission());
        assertEquals(1, owner.queued(), "delivery is waiting for the owning tick, not run inline");
        assertTrue(nav.pending.get(), "still pending until the owner runs the callback");

        owner.retire();

        assertEquals(1, nav.releaseCount.get(), "the retirement callback released it");
        assertEquals(0, nav.applied.get());
        assertFalse(nav.pending.get());
        owner.tick();
        assertEquals(1, nav.releaseCount.get(), "retirement and delivery never both run");
        assertOutstandingReturnsToBaseline("completed solve");
    }

    @Test
    void aRealEntitySchedulerRetiredMidSolveDiscardsTheResultAndReleases() throws Exception {
        // EntityScheduler.retire() is what both entity removal and WorldUnloadDrain call.
        final var scheduler = new EntityScheduler(mock(CraftEntity.class));
        final var nav = navigation();
        final Gate gate = gate();
        assertTrue(nav.kick(gate.solve(new Route(TARGET_B), false), new SchedulerOwner(scheduler)));
        gate.awaitStarted();

        scheduler.retire();
        gate.release.countDown();

        assertTrue(nav.awaitReleases(1), "a retired scheduler refuses the late delivery and the caller releases");
        assertEquals(0, nav.applied.get());
        assertFalse(nav.pending.get());
        assertEquals(1, nav.releaseCount.get());
        assertOutstandingReturnsToBaseline("retired scheduler");
    }

    @Test
    void aRealEntitySchedulerRetiredAfterAcceptanceReleasesViaRetirement() throws Exception {
        final var scheduler = new EntityScheduler(mock(CraftEntity.class));
        final var owner = new SchedulerOwner(scheduler);
        final var nav = navigation();
        final Gate gate = gate();
        assertTrue(nav.kick(gate.solve(new Route(TARGET_B), false), owner));
        gate.release.countDown();
        // the solve completes and is admitted to the scheduler (due on the next owning tick) ...
        assertTrue(owner.admitted.await(10, TimeUnit.SECONDS));
        assertEquals(dev.iyanz.sourbycraft.execution.Admission.ACCEPTED, owner.admission);
        assertTrue(nav.pending.get(), "admitted: waiting for the owning tick");
        assertEquals(0, nav.releaseCount.get());

        scheduler.retire(); // ... and the entity goes away before that tick

        assertEquals(1, nav.releaseCount.get(), "the retirement callback released it, synchronously");
        assertEquals(0, nav.applied.get());
        assertFalse(nav.pending.get());
        assertOutstandingReturnsToBaseline("retired after acceptance");
    }

    @Test
    void anEntityReplacedByADimensionTransferGetsNoResultButIsReleasedToSolveAgain() throws Exception {
        final var nav = navigation();
        final var owner = new ManualOwner(nav.mob);
        final Route before = nav.path;
        final Gate gate = gate();
        assertTrue(nav.kick(gate.solve(new Route(TARGET_B), false), owner));
        gate.release.countDown();
        assertTrue(owner.awaitSubmission());

        owner.replaceOwner(new Object()); // the live entity is now a different object
        owner.tick();

        assertEquals(0, nav.applied.get(), "a result for the replaced entity is not applied to the new one");
        assertSame(before, nav.path);
        assertFalse(nav.pending.get(), "released before the check, so a new solve may be submitted");
        assertEquals(1, nav.releaseCount.get());
        assertOutstandingReturnsToBaseline("dimension transfer");
    }

    @Test
    void aSolverThatFailedOrFoundNoPathLeavesTheLivePathAlone() throws Exception {
        for (final Supplier<Route> solve : List.<Supplier<Route>>of(
            () -> { throw new IllegalStateException("fixture: solver failed"); },
            () -> null,
            () -> new Route(null))) {
            final var nav = navigation();
            final var owner = new ManualOwner(nav.mob);
            final Route before = nav.path;
            assertTrue(nav.kick(solve, owner));
            assertTrue(owner.awaitSubmission());
            owner.tick();
            assertEquals(0, nav.applied.get());
            assertSame(before, nav.path, "the mob keeps following its live path");
            assertFalse(nav.pending.get());
            assertEquals(1, nav.releaseCount.get());
        }
        assertOutstandingReturnsToBaseline("failed solves");
    }

    @Test
    void manyMobsRemovedMidSolveAllReleaseAndTheCountReturnsToBaseline() throws Exception {
        final int mobs = 64;
        final Gate shared = gate();
        final List<AsyncPathNavigationModel> navs = new ArrayList<>();
        final List<ManualOwner> owners = new ArrayList<>();
        for (int i = 0; i < mobs; i++) {
            final var nav = navigation();
            final var owner = new ManualOwner(nav.mob);
            navs.add(nav);
            owners.add(owner);
            assertTrue(nav.kick(shared.solve(new Route(TARGET_B), false), owner), "mob " + i);
        }
        shared.awaitStarted();
        assertEquals(this.baseline + mobs, AsyncPathProcessor.outstanding());

        owners.forEach(ManualOwner::retire);
        shared.release.countDown();

        for (int i = 0; i < mobs; i++) {
            assertTrue(owners.get(i).awaitSubmission(), "mob " + i);
            assertTrue(navs.get(i).awaitReleases(1), "mob " + i);
            assertEquals(0, navs.get(i).applied.get());
            assertFalse(navs.get(i).pending.get());
        }
        assertOutstandingReturnsToBaseline("64 removed mobs");
    }

    // ---------------------------------------------------------------------------------------------
    // (b) the world unloads / the server shuts down mid-solve
    // ---------------------------------------------------------------------------------------------

    @Test
    void shutdownInterruptsARunningSolveAndTheMobKeepsItsPath() throws Exception {
        final var nav = navigation();
        final var owner = new ManualOwner(nav.mob);
        final Route before = nav.path;
        final Gate gate = gate();
        assertTrue(nav.kick(gate.solve(new Route(TARGET_B), false), owner));
        gate.awaitStarted();

        AsyncPathProcessor.shutdown();

        assertTrue(owner.awaitSubmission(), "the failed solve still completes its future");
        owner.tick();
        assertTrue(gate.interrupted.get(), "shutdownNow interrupts a running solve");
        assertFalse(gate.ranToEnd.get());
        assertEquals(0, nav.applied.get());
        assertSame(before, nav.path);
        assertFalse(nav.pending.get());
        assertEquals(1, nav.releaseCount.get());
        assertOutstandingReturnsToBaseline("interrupted solve");
    }

    @Test
    void shutdownCancelsEverythingQueuedWithoutRunningItAndReleasesEachCaller() throws Exception {
        final int workers = AsyncPathProcessor.poolThreads();
        final Gate[] holders = new Gate[workers];
        for (int i = 0; i < workers; i++) {
            holders[i] = gate();
            assertNotNull(AsyncPathProcessor.trySubmit(holders[i].solve(null, true)));
        }
        for (final Gate holder : holders) {
            holder.awaitStarted();
        }
        final int queuedMobs = 8;
        final List<AsyncPathNavigationModel> navs = new ArrayList<>();
        final List<ManualOwner> owners = new ArrayList<>();
        final List<Gate> queuedGates = new ArrayList<>();
        for (int i = 0; i < queuedMobs; i++) {
            final var nav = navigation();
            final var owner = new ManualOwner(nav.mob);
            final Gate queued = gate();
            navs.add(nav);
            owners.add(owner);
            queuedGates.add(queued);
            assertTrue(nav.kick(queued.solve(new Route(TARGET_B), false), owner));
        }
        assertEquals(this.baseline + workers + queuedMobs, AsyncPathProcessor.outstanding());

        AsyncPathProcessor.shutdown();

        for (int i = 0; i < queuedMobs; i++) {
            assertTrue(owners.get(i).awaitSubmission(), "queued mob " + i);
            owners.get(i).tick();
            assertEquals(0, navs.get(i).applied.get());
            assertFalse(navs.get(i).pending.get(), "queued mob " + i + " released");
            assertEquals(1, navs.get(i).releaseCount.get());
            assertEquals(1L, queuedGates.get(i).started.getCount(), "a queued solve never started");
        }
        // the two workers' deaf solves finish only now; accounting settles afterwards
        for (final Gate holder : holders) {
            holder.release.countDown();
        }
        assertOutstandingReturnsToBaseline("after the held solves finished");
    }

    @Test
    void aSolveThatIgnoresInterruptionFinishesAfterShutdownAndStillBalancesTheBooks() throws Exception {
        // documented in shutdown(): "a solver that ignores interruption may continue and complete its
        // future later". What must hold regardless is that it is counted until then, released exactly
        // once, and not counted afterwards.
        final var nav = navigation();
        final var owner = new ManualOwner(nav.mob);
        final Gate gate = gate();
        assertTrue(nav.kick(gate.solve(new Route(TARGET_B), true), owner));
        gate.awaitStarted();

        AsyncPathProcessor.shutdown();
        assertEquals(this.baseline + 1, AsyncPathProcessor.outstanding(), "still running, still counted");
        assertEquals(0, nav.releaseCount.get());

        gate.release.countDown();
        assertTrue(owner.awaitSubmission());
        owner.tick();
        assertTrue(nav.awaitReleases(1));
        assertEquals(1, nav.releaseCount.get(), "exactly one release");
        assertFalse(nav.pending.get());
        assertOutstandingReturnsToBaseline("late completion");
    }

    @Test
    void afterShutdownTheNavigationDeclinesTheOffloadAndTouchesNothing() {
        final var nav = navigation();
        final var owner = new ManualOwner(nav.mob);
        final Route before = nav.path;
        AsyncPathProcessor.shutdown();
        final long refusedBefore = AsyncPathProcessor.stats().refused();

        final Gate gate = gate();
        assertFalse(nav.kick(gate.solve(new Route(TARGET_B), false), owner), "no capacity: caller runs its synchronous path");

        assertFalse(nav.pending.get(), "pending was never set");
        assertSame(before, nav.path);
        assertEquals(0, owner.queued());
        assertEquals(1L, gate.started.getCount(), "the solve never ran");
        assertEquals(this.baseline, AsyncPathProcessor.outstanding());
        assertEquals(refusedBefore, AsyncPathProcessor.stats().refused(),
            "declined by hasCapacity() before trySubmit, so not counted as a refused submission");
    }

    @Test
    void whenTheRuntimeIsStoppingAdmissionClosesButAnInFlightSolveIsLeftToFinish() throws Exception {
        assumeTrue(AuroraRuntime.state() == AuroraRuntime.State.NEW, "another test left the runtime state changed");
        final var nav = navigation();
        final var owner = new ManualOwner(nav.mob);
        final Gate gate = gate();
        assertTrue(nav.kick(gate.solve(new Route(TARGET_B), false), owner));
        gate.awaitStarted();

        AuroraRuntime.transition(AuroraRuntime.State.BOOTSTRAPPING);
        AuroraRuntime.transition(AuroraRuntime.State.RUNNING);
        AuroraRuntime.transition(AuroraRuntime.State.STOPPING);

        assertFalse(AsyncPathProcessor.hasCapacity(), "a stopping runtime has no capacity");
        final long refusedBefore = AsyncPathProcessor.stats().refused();
        assertNull(AsyncPathProcessor.trySubmit(() -> "late"));
        assertEquals(refusedBefore + 1, AsyncPathProcessor.stats().refused());
        assertEquals(this.baseline + 1, AsyncPathProcessor.outstanding(), "the refused solve is not counted");

        // runtime state gates admission only; it does not cancel what was admitted
        gate.release.countDown();
        assertTrue(owner.awaitSubmission());
        owner.tick();
        assertTrue(gate.ranToEnd.get());
        assertEquals(1, nav.applied.get(), "an admitted, still valid solve is delivered and applied");
        assertOutstandingReturnsToBaseline("stopping runtime");
    }

    // ---------------------------------------------------------------------------------------------
    // (c) the target moves (the path is replaced) mid-solve
    // ---------------------------------------------------------------------------------------------

    @Test
    void aSynchronousRecomputeDuringTheSolveMakesTheAsyncResultStaleAndItIsNotRetried() throws Exception {
        final var nav = navigation();
        final var owner = new ManualOwner(nav.mob);
        final Gate gate = gate();
        final long admittedBefore = AsyncPathProcessor.stats().admitted();
        assertTrue(nav.recompute(gate.solve(new Route("async-result"), false), owner, () -> new Route("unused")));
        gate.awaitStarted();
        assertTrue(nav.pending.get());

        // the target moved: the chase goal re-paths synchronously; with a solve pending the offload is skipped
        final Route fresh = new Route(TARGET_B);
        assertFalse(nav.recompute(() -> fail("must not offload while one is pending"), owner, () -> fresh));
        assertSame(fresh, nav.path);

        gate.release.countDown();
        assertTrue(owner.awaitSubmission());
        owner.tick();

        assertSame(fresh, nav.path, "the stale result does not overwrite the newer path");
        assertEquals(TARGET_B, nav.targetPos);
        assertEquals(0, nav.applied.get());
        assertFalse(nav.pending.get(), "released, so the next periodic recompute may offload again");
        assertEquals(admittedBefore + 1, AsyncPathProcessor.stats().admitted(),
            "a stale result is dropped, not recomputed: nothing was resubmitted");
        assertOutstandingReturnsToBaseline("stale result");
    }

    @Test
    void aRetargetThatReplacesThePathMakesTheResultStale() throws Exception {
        final var nav = navigation();
        final var owner = new ManualOwner(nav.mob);
        final Gate gate = gate();
        assertTrue(nav.kick(gate.solve(new Route("async-result"), false), owner));
        nav.path = new Route(TARGET_B); // createPath/moveTo to a new target assigns a new path object
        nav.targetPos = TARGET_B;
        final Route retargeted = nav.path;
        gate.release.countDown();
        assertTrue(owner.awaitSubmission());
        owner.tick();
        assertSame(retargeted, nav.path);
        assertEquals(TARGET_B, nav.targetPos);
        assertEquals(0, nav.applied.get());
    }

    @Test
    void stoppingTheNavigationMidSolveIsNotResurrectedByTheResult() throws Exception {
        final var nav = navigation();
        final var owner = new ManualOwner(nav.mob);
        final Gate gate = gate();
        assertTrue(nav.kick(gate.solve(new Route("async-result"), false), owner));
        nav.path = null; // stop()
        gate.release.countDown();
        assertTrue(owner.awaitSubmission());
        owner.tick();
        assertNull(nav.path, "a stopped navigation stays stopped");
        assertEquals(0, nav.applied.get());
        assertFalse(nav.pending.get());
    }

    @Test
    void aPathStillBeingFollowedTakesTheResultAndItsTarget() throws Exception {
        final var nav = navigation();
        final var owner = new ManualOwner(nav.mob);
        final Gate gate = gate();
        final Route result = new Route(TARGET_B);
        assertTrue(nav.kick(gate.solve(result, false), owner));
        gate.release.countDown();
        assertTrue(owner.awaitSubmission());
        owner.tick();
        assertSame(result, nav.path);
        assertEquals(TARGET_B, nav.targetPos, "the target comes from the applied path");
        assertEquals(1, nav.applied.get());
        assertFalse(nav.pending.get());
        assertOutstandingReturnsToBaseline("applied result");
    }

    @Test
    void thePendingFlagStopsASecondOffloadButNotTheSynchronousFallback() throws Exception {
        final var nav = navigation();
        final var owner = new ManualOwner(nav.mob);
        final Gate gate = gate();
        assertTrue(nav.recompute(gate.solve(new Route("first"), false), owner, () -> new Route("sync-1")));
        gate.awaitStarted();
        final long admitted = AsyncPathProcessor.stats().admitted();

        assertFalse(nav.recompute(() -> fail("a second offload while one is pending"), owner, () -> new Route("sync-2")));

        assertEquals(admitted, AsyncPathProcessor.stats().admitted());
        assertEquals("sync-2", nav.targetPos, "the synchronous recompute ran instead");
    }

    /** {@link RegionOwnerHandoff} over a real scheduler, as production wires it (minus the Entity lookup). */
    private static final class SchedulerOwner implements dev.iyanz.sourbycraft.execution.OwnerHandoff<Object> {
        private final RegionOwnerHandoff delegate;
        final CountDownLatch admitted = new CountDownLatch(1);
        volatile dev.iyanz.sourbycraft.execution.Admission admission;

        SchedulerOwner(final EntityScheduler scheduler) {
            this.delegate = new RegionOwnerHandoff(scheduler);
        }

        @Override
        public dev.iyanz.sourbycraft.execution.Admission submit(final java.util.function.Consumer<? super Object> delivery,
                                                               final Runnable retirement) {
            try {
                this.admission = this.delegate.submit(delivery::accept, retirement);
                return this.admission;
            } finally {
                this.admitted.countDown();
            }
        }
    }
}
