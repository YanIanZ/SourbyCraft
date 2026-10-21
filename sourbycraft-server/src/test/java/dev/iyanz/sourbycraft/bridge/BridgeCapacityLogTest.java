package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.config.AuroraConfig.BridgeMode;
import dev.iyanz.sourbycraft.config.AuroraConfig.SyncRoute;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Per-plugin limit refusals (2026-10-22 local run of the LightingLuminol follow-up cases): every refused task
 * logged a WARN with a RejectedExecutionException stack trace, about 35 lines each, without any rate limit; a
 * plugin scheduling past its limit flooded the log. Now: every refusal is still counted, the line names the
 * key, carries no stack trace, and is logged at most once per (plugin, limit) per window.
 */
class BridgeCapacityLogTest {

    private final BridgeRuntimeTest.FakeExecutor executor = new BridgeRuntimeTest.FakeExecutor();
    private final List<String> warnings = new ArrayList<>();
    private final List<Throwable> thrown = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(1_000L);

    private BridgeRuntime runtime(final int pendingLimit, final int asyncLimit) {
        final BridgeRuntime bridge = new BridgeRuntime(BridgeMode.SAFE, () -> 3, this.executor, new BridgeTelemetry(),
            (message, failure) -> {
                this.warnings.add(message);
                this.thrown.add(failure);
            }, () -> null, () -> SyncRoute.GLOBAL, () -> pendingLimit, () -> asyncLimit);
        bridge.nanoClock = this.now::get;
        return bridge;
    }

    @Test
    void pendingLimitRefusalsAreAllCountedButLoggedOncePerWindowWithoutAStackTrace() {
        final BridgeRuntime bridge = runtime(1, 0);
        bridge.admit("A");
        bridge.admit("B");
        assertTrue(bridge.submit("A", null, new BridgeRuntimeTest.FakeTask(1, true, -1, () -> {}), 5));
        for (int id = 2; id <= 101; id++) {
            assertFalse(bridge.submit("A", null, new BridgeRuntimeTest.FakeTask(id, true, -1, () -> {}), 5));
        }
        assertEquals(100, bridge.telemetry().stats("A").rejectedOperations(), "every refusal counted");
        assertEquals("pending task limit 1", bridge.telemetry().stats("A").lastFailure());
        assertEquals(1, this.warnings.size(), "one line, not one per task: " + this.warnings.size());
        assertNull(this.thrown.getFirst(), "a configured limit is not a fault: no stack trace");
        assertTrue(this.warnings.getFirst().contains("pending task limit 1"), this.warnings.getFirst());
        assertTrue(this.warnings.getFirst().contains("aurora.bridge.max-pending-tasks-per-plugin"), this.warnings.getFirst());

        assertTrue(bridge.submit("B", null, new BridgeRuntimeTest.FakeTask(200, true, -1, () -> {}), 5));
        assertFalse(bridge.submit("B", null, new BridgeRuntimeTest.FakeTask(201, true, -1, () -> {}), 5));
        assertEquals(2, this.warnings.size(), "the window is per plugin");

        this.now.addAndGet(BridgeRuntime.LOG_WINDOW_NANOS);
        assertFalse(bridge.submit("A", null, new BridgeRuntimeTest.FakeTask(300, true, -1, () -> {}), 5));
        assertEquals(3, this.warnings.size());
        assertTrue(this.warnings.getLast().contains("99 more suppressed"), this.warnings.getLast());
        assertEquals(0, bridge.telemetry().stats("A").fatalViolations());
        assertFalse(bridge.quarantined("A"));
    }

    @Test
    void runningAsyncRefusalsUseTheirOwnWindow() {
        final BridgeRuntime bridge = runtime(1, 1);
        bridge.admit("A");
        // A pending refusal first: the async limit's line must still be logged.
        assertTrue(bridge.submit("A", null, new BridgeRuntimeTest.FakeTask(1, false, 1, () -> {
            this.executor.fire(0); // an overlapping invocation of the same timer, past the running limit
        }), 0));
        assertFalse(bridge.submit("A", null, new BridgeRuntimeTest.FakeTask(2, true, -1, () -> {}), 0));
        this.executor.fire(0);
        assertEquals(2, this.warnings.size(), String.valueOf(this.warnings));
        assertTrue(this.warnings.get(1).contains("running async callback limit 1"), this.warnings.get(1));
        assertTrue(this.warnings.get(1).contains("aurora.bridge.max-running-async-tasks-per-plugin"), this.warnings.get(1));
        assertNull(this.thrown.get(1));
    }
}
