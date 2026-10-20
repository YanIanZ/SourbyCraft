package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * The bridge runtime is a lazy process-wide static: the first {@code runtimeMode()} (a rendered
 * {@code /perf plugins}, for one) starts it and it stays started for the rest of the JVM, so a later test
 * that expects "not started" depends on test order. {@code resetRuntimeForTesting} is how a test that
 * starts it puts it back.
 */
class AuroraBridgeRuntimeResetTest {

    @Test
    void resettingPutsTheRuntimeBackToNotStartedAndClosingResetsAgain() throws Exception {
        try (AutoCloseable outer = AuroraBridge.resetRuntimeForTesting()) {
            assertNull(AuroraBridge.modeIfStarted(), "reset leaves nothing started");

            assertNotNull(AuroraBridge.runtimeMode(), "asking for the mode starts the lazy runtime");
            assertNotNull(AuroraBridge.modeIfStarted());

            try (AutoCloseable inner = AuroraBridge.resetRuntimeForTesting()) {
                assertNull(AuroraBridge.modeIfStarted(), "a second reset clears a started runtime");
                AuroraBridge.runtimeMode();
            }
            assertNull(AuroraBridge.modeIfStarted(), "closing the reset clears whatever the test started");
        }
        assertNull(AuroraBridge.modeIfStarted());
    }
}
