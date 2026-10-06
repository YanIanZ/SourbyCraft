package dev.yanianz.intave;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.yanianz.intave.NativeIntave.Controller;
import dev.yanianz.intave.NativeIntave.State;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Server-owned lifecycle controller; no private engine, Bukkit or NMS required. */
final class NativeIntaveControllerTest {
    /** Mirrors NativeEngineProvider's contract: owner tick outside the owning region is an error. */
    static class FakeEngine implements NativeIntave.Engine {
        final CompletableFuture<Void> initialization = new CompletableFuture<>();
        final List<Object> ticked = new ArrayList<>();
        Predicate<Object> ownsRegion = player -> true;
        Runnable onTick = () -> {};
        boolean ready = true, startFailure, closeFailure;
        int starts, closes, admissionStops;

        @Override public void start() {
            this.starts++;
            if (this.startFailure) throw new IllegalStateException("start");
        }
        @Override public boolean ready() { return this.ready; }
        @Override public CompletionStage<Void> initialization() { return this.initialization; }
        @Override public void ownerTick(Object player) {
            if (!this.ownsRegion.test(player)) throw new IllegalStateException("Intave tick is outside player owner region");
            this.onTick.run();
            this.ticked.add(player);
        }
        @Override public void stopAdmission() { this.admissionStops++; }
        @Override public void close() {
            this.closes++;
            if (this.closeFailure) throw new IllegalStateException("close");
        }
    }

    private static Controller active(FakeEngine engine) {
        engine.initialization.complete(null);
        Controller controller = new Controller();
        controller.start(true, () -> engine);
        assertEquals(State.ACTIVE, controller.status().state());
        return controller;
    }

    @Test void publicStaticPathIsDisabledWithoutPropertyAndNeverConstructs() {
        // Default boot: -Dsourbycraft.intave.enabled is absent, so no reflection/provider lookup occurs.
        assertFalse(Boolean.getBoolean("sourbycraft.intave.enabled"));
        NativeIntave.start();
        assertEquals(State.DISABLED, NativeIntave.status().state());
        NativeIntave.ownerTick(new Object()); // no-op
        assertTrue(NativeIntave.packetFiltersEnabled());
        NativeIntave.close();
        assertEquals(State.STOPPED, NativeIntave.status().state());
    }

    @Test void disabledNeverConstructsAndCannotBeReenabledInProcess() {
        Controller controller = new Controller();
        controller.start(false, () -> fail("disabled engine constructed"));
        controller.start(true, () -> fail("in-process reactivation"));
        controller.ownerTick(new Object());
        assertEquals(State.DISABLED, controller.status().state());
    }

    @Test void missingProviderIsUnavailableNotActive() {
        Controller controller = new Controller();
        controller.start(true, () -> null);
        assertEquals(State.UNAVAILABLE, controller.status().state());
        controller.close();
        assertEquals(State.STOPPED, controller.status().state());
    }

    @Test void startingBecomesActiveOnlyAfterDeferredReadyInitialization() {
        FakeEngine engine = new FakeEngine();
        Controller controller = new Controller();
        controller.start(true, () -> engine);
        assertEquals(State.STARTING, controller.status().state());
        controller.ownerTick("p");
        assertTrue(engine.ticked.isEmpty(), "no callbacks while STARTING");
        engine.initialization.complete(null);
        assertEquals(State.ACTIVE, controller.status().state());
        controller.ownerTick("p");
        assertEquals(List.of("p"), engine.ticked);
        controller.close();
        controller.close();
        assertEquals(1, engine.starts);
        assertEquals(1, engine.closes);
        assertEquals(State.STOPPED, controller.status().state());
    }

    @Test void notReadyAfterInitializationFailsWithoutCleanupOnCompletionThread() {
        FakeEngine engine = new FakeEngine();
        engine.ready = false;
        Controller controller = new Controller();
        controller.start(true, () -> engine);
        engine.initialization.complete(null);
        assertEquals(State.FAILED, controller.status().state());
        assertEquals(0, engine.closes);
        controller.close();
        assertEquals(1, engine.closes);
    }

    @Test void startFailureIsFailedAndCleanedOnce() {
        FakeEngine engine = new FakeEngine();
        engine.startFailure = true;
        Controller controller = new Controller();
        controller.start(true, () -> engine);
        assertEquals(State.FAILED, controller.status().state());
        assertTrue(controller.status().detail().contains("start"));
        controller.close();
        assertEquals(1, engine.closes);
    }

    @Test void failedEngineRefusesAdmissionAndStopsEngineAdmission() {
        FakeEngine engine = new FakeEngine();
        Controller controller = active(engine);
        controller.fail("Packet callback", new IllegalStateException("boom"));
        assertEquals(State.FAILED, controller.status().state());
        assertEquals(1, engine.admissionStops);
        controller.ownerTick("p");
        assertTrue(engine.ticked.isEmpty(), "FAILED refuses owner callbacks");
        assertFalse(controller.status().active());
        assertEquals(0, engine.closes, "failure path never performs blocking cleanup");
        controller.close();
        assertEquals(1, engine.closes);
    }

    @Test void ownerTickRunsOnlyForOwningRegionAndForeignTickDisablesProtection() {
        FakeEngine engine = new FakeEngine();
        Object owned = "owned", foreign = "foreign";
        engine.ownsRegion = player -> player == owned;
        Controller controller = active(engine);
        controller.ownerTick(owned);
        assertEquals(List.of(owned), engine.ticked);
        controller.ownerTick(foreign);
        assertEquals(State.FAILED, controller.status().state());
        assertTrue(controller.status().detail().contains("owner region"));
        controller.ownerTick(owned);
        assertEquals(List.of(owned), engine.ticked, "no callbacks after the ownership violation");
        new Controller().ownerTick(null); // inactive fast path returns before touching the player
    }

    @Test void stoppedIsTerminal() {
        FakeEngine engine = new FakeEngine();
        Controller controller = new Controller();
        controller.start(true, () -> engine);
        controller.close();
        engine.initialization.complete(null);
        controller.fail("late", new IllegalStateException());
        controller.start(true, () -> fail("restart after stop"));
        assertEquals(State.STOPPED, controller.status().state());
        assertEquals(1, engine.closes);
    }

    @Test void cleanupFailureIsReportedAndNotRetried() {
        FakeEngine engine = new FakeEngine();
        engine.closeFailure = true;
        Controller controller = active(engine);
        controller.close();
        controller.close();
        assertEquals(State.FAILED, controller.status().state());
        assertEquals(1, engine.closes);
    }

    @Test @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void shutdownWaitsForRunningOwnerCallbackThenCleans() throws Exception {
        FakeEngine engine = new FakeEngine();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        engine.onTick = () -> { entered.countDown(); awaitQuietly(release); };
        Controller controller = active(engine);
        Thread owner = new Thread(() -> controller.ownerTick("p"));
        owner.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        Thread closer = new Thread(controller::close);
        closer.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (controller.status().state() != State.STOPPING && System.nanoTime() < deadline) Thread.onSpinWait();
        assertEquals(State.STOPPING, controller.status().state());
        assertEquals(0, engine.closes, "resources retained while the owner callback runs");
        release.countDown();
        owner.join(3000);
        closer.join(3000);
        assertEquals(1, engine.closes);
        assertEquals(State.STOPPED, controller.status().state());
    }

    @Test @Timeout(value = 20, unit = TimeUnit.SECONDS)
    void shutdownWaitIsBoundedToFiveSecondsAndRetainsResources() throws Exception {
        FakeEngine engine = new FakeEngine();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        engine.onTick = () -> { entered.countDown(); awaitQuietly(release); };
        Controller controller = active(engine);
        Thread owner = new Thread(() -> controller.ownerTick("p"));
        owner.start();
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            long started = System.nanoTime();
            controller.close();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis >= 4_900 && elapsedMillis < 8_000, "close waited " + elapsedMillis + " ms");
            assertEquals(State.FAILED, controller.status().state());
            assertTrue(controller.status().detail().contains("did not drain"));
            assertEquals(0, engine.closes, "a callback still running must keep its resources");
            assertEquals(1, engine.admissionStops);
        } finally {
            release.countDown();
            owner.join(3000);
        }
    }

    @Test void packetFilterContextIsThreadLocalAndRestoredAfterFailure() throws Exception {
        boolean[] other = new boolean[1];
        assertTrue(NativeIntave.packetFiltersEnabled());
        assertThrows(IllegalStateException.class, () -> NativeIntave.withoutPacketFilters(() -> {
            assertFalse(NativeIntave.packetFiltersEnabled());
            NativeIntave.withoutPacketFilters(() -> assertFalse(NativeIntave.packetFiltersEnabled()));
            assertFalse(NativeIntave.packetFiltersEnabled());
            Thread thread = new Thread(() -> other[0] = NativeIntave.packetFiltersEnabled());
            thread.start();
            try { thread.join(3000); } catch (InterruptedException e) { throw new AssertionError(e); }
            throw new IllegalStateException("write failure");
        }));
        assertTrue(other[0]);
        assertTrue(NativeIntave.packetFiltersEnabled());
    }

    @Test void failureReportedDuringReadyCheckCannotPublishActive() {
        Controller[] holder = new Controller[1];
        FakeEngine engine = new FakeEngine() {
            @Override public boolean ready() {
                // A channel-attachment failure reported while readiness is evaluated.
                holder[0].fail("channel attachment", new IllegalStateException("pipeline absent"));
                return true;
            }
        };
        holder[0] = new Controller();
        holder[0].start(true, () -> engine);
        engine.initialization.complete(null);
        assertEquals(State.FAILED, holder[0].status().state());
        holder[0].close();
        assertEquals(1, engine.closes);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try { latch.await(30, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
    }
}
