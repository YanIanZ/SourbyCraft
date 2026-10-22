package dev.iyanz.sourbycraft.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.entity.teleport.OwnedTeleportPatchShapeTestAccess;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** MC patch 0076: a stop the server thread asks for during startup runs once startup has ended. */
class StartupStopGateTest {

    @Test
    void normalStartupHoldsNothing() {
        final StartupStopGate gate = new StartupStopGate();
        assertFalse(gate.stopHeld());
        assertFalse(gate.started());
        assertFalse(gate.finishStartup(), "no stop was asked for");
        assertTrue(gate.started());
        assertFalse(gate.holdIfStarting(true), "after startup a server-thread stop goes on at once");
        assertFalse(gate.stopHeld());
    }

    @Test
    void serverThreadStopDuringStartupIsHeldUntilStartupEnds() {
        final StartupStopGate gate = new StartupStopGate();
        assertTrue(gate.holdIfStarting(true));
        assertTrue(gate.stopHeld());
        assertTrue(gate.holdIfStarting(true), "a second request during startup is held too");
        assertTrue(gate.finishStartup(), "the held stop must now be run");
        assertFalse(gate.stopHeld());
        assertFalse(gate.finishStartup(), "the held stop is handed out once");
        assertFalse(gate.holdIfStarting(true), "the stop run after startup is not held again");
    }

    @Test
    void otherThreadStopsAreNeverHeld() {
        final StartupStopGate gate = new StartupStopGate();
        assertFalse(gate.holdIfStarting(false), "console, signal or async stop keeps the old path");
        assertFalse(gate.stopHeld());
        assertTrue(gate.holdIfStarting(true));
        assertFalse(gate.holdIfStarting(false), "even with a held server-thread stop");
        assertTrue(gate.finishStartup());
    }

    @Test
    void concurrentRequestsAndFinishHandOutTheHeldStopAtMostOnce() throws InterruptedException {
        for (int round = 0; round < 500; round++) {
            final StartupStopGate gate = new StartupStopGate();
            final CountDownLatch go = new CountDownLatch(1);
            final AtomicInteger held = new AtomicInteger();
            final AtomicInteger handedOut = new AtomicInteger();
            final Thread requester = Thread.ofPlatform().start(() -> {
                await(go);
                if (gate.holdIfStarting(true)) held.incrementAndGet();
            });
            final Thread finisher = Thread.ofPlatform().start(() -> {
                await(go);
                if (gate.finishStartup()) handedOut.incrementAndGet();
            });
            go.countDown();
            requester.join(TimeUnit.SECONDS.toMillis(5));
            finisher.join(TimeUnit.SECONDS.toMillis(5));
            // either the request came first (held, then handed out) or startup ended first (not held, runs now)
            assertEquals(held.get(), handedOut.get(), "round " + round);
            assertFalse(gate.holdIfStarting(true));
        }
    }

    // --- the hooks in MC patch 0076 sit where the gate's contract needs them ---

    private static String patch() throws IOException {
        return OwnedTeleportPatchShapeTestAccess.patch("minecraft-patches/features", "-SourbyCraft-hold-a-stop-requested-during-startup");
    }

    @Test
    void stopServerAsksTheGateBeforeHaltingAnything() throws IOException {
        final String server = OwnedTeleportPatchShapeTestAccess.file(patch(), "net/minecraft/server/MinecraftServer.java");
        final int hold = OwnedTeleportPatchShapeTestAccess.at(server,
            "+        if (this.startupStopGate.holdIfStarting(Thread.currentThread() == this.serverThread)) return;");
        final int signature = OwnedTeleportPatchShapeTestAccess.at(server, " public void stopServer() {");
        final int halt = OwnedTeleportPatchShapeTestAccess.at(server, " // halt scheduler");
        assertTrue(signature < hold && hold < halt, "the gate is the first statement of stopServer");
        assertEquals(0, OwnedTeleportPatchShapeTestAccess.removed(server), "only additions in MinecraftServer");
    }

    @Test
    void runServerRunsTheHeldStopRightAfterInitServerAndOnAStartupCrash() throws IOException {
        final String server = OwnedTeleportPatchShapeTestAccess.file(patch(), "net/minecraft/server/MinecraftServer.java");
        final int init = OwnedTeleportPatchShapeTestAccess.at(server, " throw new IllegalStateException(\"Failed to initialize server\");");
        final int finish = OwnedTeleportPatchShapeTestAccess.at(server, "+            if (this.startupStopGate.finishStartup()) {");
        final int log = OwnedTeleportPatchShapeTestAccess.at(server, "+                LOGGER.info(dev.iyanz.sourbycraft.startup.StartupStopGate.ABANDONED_MESSAGE);");
        final int stop = OwnedTeleportPatchShapeTestAccess.at(server, "+                this.stopServer();\n+                return;");
        assertTrue(init < finish && finish < log && log < stop, "held stop handled before the tick schedule, RegionizedServer init and Done (");
        final int crash = OwnedTeleportPatchShapeTestAccess.at(server, " this.onServerCrash(report);");
        final int crashStop = OwnedTeleportPatchShapeTestAccess.at(server,
            "+            if (this.startupStopGate.finishStartup()) this.stopServer();");
        assertTrue(crash < crashStop, "a startup crash after a held request still runs the stop");
    }

    @Test
    void initServerSkipsListenersAndTheStartupSaveWhenAStopIsHeld() throws IOException {
        final String dedicated = OwnedTeleportPatchShapeTestAccess.file(patch(), "net/minecraft/server/dedicated/DedicatedServer.java");
        final int skip = OwnedTeleportPatchShapeTestAccess.at(dedicated, "+        if (this.startupStopGate.stopHeld()) return true;");
        final int query = OwnedTeleportPatchShapeTestAccess.at(dedicated, " if (properties.enableQuery) {");
        assertTrue(skip < query, "checked after the worlds and POSTWORLD plugins, before listeners and saveEverything");
        assertEquals(0, OwnedTeleportPatchShapeTestAccess.removed(dedicated), "only additions in DedicatedServer");
    }

    @Test
    void abandonedMessageIsNotAReadyMarker() {
        assertFalse(StartupStopGate.ABANDONED_MESSAGE.contains("Done ("), "harnesses read Done ( as ready");
        assertTrue(StartupStopGate.ABANDONED_MESSAGE.startsWith("Startup stopped:"));
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
