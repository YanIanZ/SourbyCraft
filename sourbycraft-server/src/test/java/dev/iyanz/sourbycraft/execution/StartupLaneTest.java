package dev.iyanz.sourbycraft.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.iyanz.sourbycraft.startup.PluginStartupIndex;
import org.junit.jupiter.api.Test;

/** Startup analysis threads are attributed to their own lane, not to OTHER. */
class StartupLaneTest {

    @Test
    void startupThreadsLandInTheStartupLane() {
        assertEquals(ExecutionLane.STARTUP, ExecutionLane.of(PluginStartupIndex.THREAD_PREFIX + "1"));
    }
}
