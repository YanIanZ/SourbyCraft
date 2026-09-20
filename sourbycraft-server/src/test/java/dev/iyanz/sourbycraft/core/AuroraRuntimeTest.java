package dev.iyanz.sourbycraft.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.core.AuroraRuntime.State;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The runtime lifecycle, and the one question it exists to answer. */
public class AuroraRuntimeTest {

    @AfterEach
    void reset() {
        AuroraRuntime.resetForTest();
    }

    @Test
    void aFreshRuntimeHasNotStarted() {
        assertEquals(State.NEW, AuroraRuntime.state());
        assertFalse(AuroraRuntime.stopping());
    }

    @Test
    void workIsAdmittedWhileServicesAreStillComingUp() {
        // Services start in order, and one that is up has to serve the ones behind it.
        AuroraRuntime.transition(State.BOOTSTRAPPING);
        assertTrue(AuroraRuntime.acceptingWork());
        AuroraRuntime.transition(State.STARTING);
        assertTrue(AuroraRuntime.acceptingWork());
        AuroraRuntime.transition(State.RUNNING);
        assertTrue(AuroraRuntime.acceptingWork());
    }

    @Test
    void workStopsBeingAdmittedTheMomentShutdownBegins() {
        // The window this exists for: shutdown is in flight, services are going down in order,
        // and work is still arriving at the ones that have not been reached yet.
        AuroraRuntime.transition(State.BOOTSTRAPPING);
        AuroraRuntime.transition(State.RUNNING);
        AuroraRuntime.transition(State.STOPPING);

        assertFalse(AuroraRuntime.acceptingWork());
        assertTrue(AuroraRuntime.stopping());
    }

    @Test
    void anIllegalTransitionIsReportedButNotRefused() {
        // Refusing would leave the runtime claiming to run while its services go down, which is
        // worse than the inconsistency it guards against. Shutdown must always be able to finish.
        AuroraRuntime.transition(State.BOOTSTRAPPING);
        AuroraRuntime.transition(State.RUNNING);

        assertFalse(AuroraRuntime.transition(State.STARTING), "reported as illegal");
        assertEquals(State.STARTING, AuroraRuntime.state(), "but still made");
    }

    @Test
    void reachingTheSameStateTwiceIsFine() {
        // close() can be reached more than once; that is not a fault worth logging.
        AuroraRuntime.transition(State.BOOTSTRAPPING);
        AuroraRuntime.transition(State.STOPPING);
        assertTrue(AuroraRuntime.transition(State.STOPPING));
    }

    @Test
    void aFailedStartCanStillShutDown() {
        AuroraRuntime.transition(State.BOOTSTRAPPING);
        assertTrue(AuroraRuntime.transition(State.FAILED));
        assertFalse(AuroraRuntime.acceptingWork(), "a failed runtime admits nothing");
        assertTrue(AuroraRuntime.stopping(), "failure must close shutdown-sensitive admission");
        assertTrue(AuroraRuntime.transition(State.STOPPING), "and can still be torn down");
    }

    @Test
    void nullTransitionCannotCorruptTheCurrentState() {
        AuroraRuntime.transition(State.BOOTSTRAPPING);
        assertThrows(NullPointerException.class, () -> AuroraRuntime.transition(null));
        assertEquals(State.BOOTSTRAPPING, AuroraRuntime.state());
        assertTrue(AuroraRuntime.transition(State.STOPPING));
    }

    @Test
    void failedRuntimeRefusesPathWorkBeforeThePoolIsStopped() throws Exception {
        final var ran = new java.util.concurrent.atomic.AtomicBoolean();
        dev.iyanz.sourbycraft.perf.AsyncPathProcessor.setEnabled(true);
        try {
            AuroraRuntime.transition(State.FAILED);
            final var result = dev.iyanz.sourbycraft.perf.AsyncPathProcessor.submit(() -> {
                ran.set(true);
                return "unexpected solve";
            });
            assertNull(result.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(ran.get(), "failure must refuse work even while the pool is alive");
        } finally {
            dev.iyanz.sourbycraft.perf.AsyncPathProcessor.shutdown();
        }
    }

    @Test
    void aServerThatDiedBeforeBootstrapCanStillShutDown() {
        // A port already bound, or a config that will not parse, kills the JVM before bootstrap
        // runs at all. Shutdown still happens, and it must not report itself as a bug: an error
        // line here lands in the middle of the startup failure the operator is trying to read.
        assertTrue(AuroraRuntime.transition(State.STOPPING), "never-started is legal to stop");
        assertFalse(AuroraRuntime.acceptingWork());
        assertTrue(AuroraRuntime.transition(State.STOPPED));
    }

    @Test
    void stoppedIsTerminal() {
        AuroraRuntime.transition(State.BOOTSTRAPPING);
        AuroraRuntime.transition(State.STOPPING);
        AuroraRuntime.transition(State.STOPPED);
        assertFalse(AuroraRuntime.acceptingWork());
        assertFalse(AuroraRuntime.transition(State.RUNNING), "nothing legally follows STOPPED");
        assertEquals(State.STOPPED, AuroraRuntime.state());
        assertFalse(AuroraRuntime.acceptingWork());
    }

    @Test
    void shutdownAndFailureCannotReopenAdmission() {
        for (final State closed : new State[] {State.FAILED, State.STOPPING, State.STOPPED}) {
            for (final State reopen : new State[] {
                State.NEW, State.BOOTSTRAPPING, State.STARTING, State.RUNNING
            }) {
                AuroraRuntime.resetForTest();
                AuroraRuntime.transition(closed);
                assertFalse(AuroraRuntime.transition(reopen));
                assertEquals(closed, AuroraRuntime.state());
                assertTrue(AuroraRuntime.stopping());
                assertFalse(AuroraRuntime.acceptingWork());
            }
        }
    }
}
