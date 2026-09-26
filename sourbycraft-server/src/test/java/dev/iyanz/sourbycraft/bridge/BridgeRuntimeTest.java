package dev.iyanz.sourbycraft.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.config.AuroraConfig.BridgeMode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Admission, routing, cancellation and quarantine, against a fake executor. */
class BridgeRuntimeTest {

    /** Records scheduled work; {@link #fire} runs it as the scheduler would. */
    static final class FakeExecutor implements BridgeRuntime.Executor {
        record Job(String lane, Runnable body, long delay, long period, AtomicBoolean cancelled) {}
        final List<Job> jobs = new ArrayList<>();

        @Override
        public BridgeRuntime.Handle global(final Object owner, final Runnable body, final long delay, final long period) {
            return add("global", body, delay, period);
        }

        @Override
        public BridgeRuntime.Handle async(final Object owner, final Runnable body, final long delay, final long period) {
            return add("async", body, delay, period);
        }

        private BridgeRuntime.Handle add(final String lane, final Runnable body, final long delay, final long period) {
            final Job job = new Job(lane, body, delay, period, new AtomicBoolean());
            this.jobs.add(job);
            return () -> job.cancelled().set(true);
        }

        void fire(final int index) {
            final Job job = this.jobs.get(index);
            if (!job.cancelled().get()) job.body().run();
        }
    }

    static final class FakeTask implements BridgeRuntime.Task {
        final int id;
        final boolean sync;
        final long period;
        final Runnable body;
        boolean cancelled;
        final AtomicInteger runs = new AtomicInteger();

        FakeTask(final int id, final boolean sync, final long period, final Runnable body) {
            this.id = id; this.sync = sync; this.period = period; this.body = body;
        }

        @Override public int id() { return this.id; }
        @Override public boolean sync() { return this.sync; }
        @Override public long period() { return this.period; }
        @Override public boolean cancelled() { return this.cancelled; }
        @Override public void markCancelled() { this.cancelled = true; }
        @Override public void run() { this.runs.incrementAndGet(); this.body.run(); }
    }

    private final FakeExecutor executor = new FakeExecutor();
    private final List<String> warnings = new ArrayList<>();

    private BridgeRuntime runtime(final BridgeMode mode, final int quarantineAfter) {
        return new BridgeRuntime(mode, () -> quarantineAfter, this.executor, new BridgeTelemetry(),
            (message, thrown) -> this.warnings.add(message));
    }

    @Test
    void offAdmitsNothing() {
        final BridgeRuntime bridge = runtime(BridgeMode.OFF, 3);
        assertFalse(bridge.admit("Legacy"));
        assertFalse(bridge.isBridged("Legacy"));
    }

    @Test
    void syncGoesToTheGlobalRegionAndAsyncToTheAsyncScheduler() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 3);
        assertTrue(bridge.admit("Legacy"));
        assertTrue(bridge.submit("Legacy", null, new FakeTask(1, true, -1, () -> {}), 5));
        assertTrue(bridge.submit("Legacy", null, new FakeTask(2, false, 20, () -> {}), 0));
        assertEquals("global", this.executor.jobs.get(0).lane());
        assertEquals(5, this.executor.jobs.get(0).delay());
        assertEquals(0, this.executor.jobs.get(0).period(), "a Bukkit period of -1 runs once");
        assertEquals("async", this.executor.jobs.get(1).lane());
        assertEquals(20, this.executor.jobs.get(1).period());
        assertEquals(2, bridge.telemetry().stats("Legacy").schedulerRedirects());
    }

    @Test
    void aPluginTheBridgeDidNotAdmitIsRejected() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 3);
        final FakeTask task = new FakeTask(1, true, -1, () -> {});
        assertFalse(bridge.submit("Stranger", null, task, 0));
        assertTrue(task.cancelled);
        assertTrue(this.executor.jobs.isEmpty());
    }

    @Test
    void aOneShotTaskIsForgottenAfterItRuns() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 3);
        bridge.admit("Legacy");
        final FakeTask task = new FakeTask(1, true, -1, () -> {});
        bridge.submit("Legacy", null, task, 0);
        assertEquals(1, bridge.pending("Legacy"));
        this.executor.fire(0);
        assertEquals(1, task.runs.get());
        assertEquals(0, bridge.pending("Legacy"));
    }

    @Test
    void cancellingStopsARepeatingTask() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 3);
        bridge.admit("Legacy");
        final FakeTask task = new FakeTask(7, true, 1, () -> {});
        bridge.submit("Legacy", null, task, 0);
        this.executor.fire(0);
        assertTrue(bridge.cancel(7));
        assertTrue(task.cancelled);
        assertTrue(this.executor.jobs.get(0).cancelled().get());
        this.executor.fire(0);
        assertEquals(1, task.runs.get());
        assertFalse(bridge.cancel(7), "a second cancel finds nothing");
    }

    @Test
    void aTaskCancelledByItsOwnFlagStopsAtItsNextRun() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 3);
        bridge.admit("Legacy");
        final FakeTask task = new FakeTask(3, false, 1, () -> {});
        bridge.submit("Legacy", null, task, 0);
        task.cancelled = true;
        this.executor.fire(0);
        assertEquals(0, task.runs.get());
        assertTrue(this.executor.jobs.get(0).cancelled().get());
    }

    @Test
    void anOrdinaryExceptionIsRecordedButNeverQuarantines() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 1);
        bridge.admit("Legacy");
        bridge.submit("Legacy", null, new FakeTask(1, true, 1, () -> { throw new NullPointerException("bug"); }), 0);
        this.executor.fire(0);
        this.executor.fire(0);
        final BridgeTelemetry.PluginStats stats = bridge.telemetry().stats("Legacy");
        assertEquals(0, stats.fatalViolations());
        assertFalse(stats.quarantined());
        assertNotNull(stats.lastFailure());
        assertEquals(2, this.warnings.size());
    }

    @Test
    void repeatedViolationsQuarantineThePluginAndCancelItsTasks() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 2);
        bridge.admit("Legacy");
        final Runnable violate = () -> { throw new UnsupportedOperationException("Unsupported in region threading"); };
        bridge.submit("Legacy", null, new FakeTask(1, true, 1, violate), 0);
        final FakeTask innocent = new FakeTask(2, false, 1, () -> {});
        bridge.submit("Legacy", null, innocent, 0);

        this.executor.fire(0);
        assertFalse(bridge.quarantined("Legacy"));
        this.executor.fire(0);
        assertTrue(bridge.quarantined("Legacy"));
        assertTrue(innocent.cancelled, "quarantine cancels every bridged task");
        assertEquals(0, bridge.pending("Legacy"));

        final FakeTask late = new FakeTask(3, true, -1, () -> {});
        assertFalse(bridge.submit("Legacy", null, late, 0));
        assertEquals(1, bridge.telemetry().stats("Legacy").rejectedOperations());
        assertTrue(this.warnings.get(this.warnings.size() - 1).contains("quarantined"));
    }

    @Test
    void cancelAllTouchesOnlyThatPlugin() {
        final BridgeRuntime bridge = runtime(BridgeMode.SAFE, 3);
        bridge.admit("A");
        bridge.admit("B");
        bridge.submit("A", null, new FakeTask(1, true, 1, () -> {}), 0);
        bridge.submit("B", null, new FakeTask(2, true, 1, () -> {}), 0);
        assertEquals(1, bridge.cancelAll("A"));
        assertEquals(0, bridge.pending("A"));
        assertEquals(1, bridge.pending("B"));
    }

    @Test
    void aCancelThatRacesTheExecutorStillCancelsTheRealTask() {
        final BridgeRuntime[] holder = new BridgeRuntime[1];
        final List<AtomicBoolean> real = new ArrayList<>();
        final BridgeRuntime.Executor cancelling = new BridgeRuntime.Executor() {
            @Override public BridgeRuntime.Handle global(Object o, Runnable b, long d, long p) {
                holder[0].cancel(9); // cancelled before the executor returns its handle
                final AtomicBoolean flag = new AtomicBoolean();
                real.add(flag);
                return () -> flag.set(true);
            }
            @Override public BridgeRuntime.Handle async(Object o, Runnable b, long d, long p) { return global(o, b, d, p); }
        };
        holder[0] = new BridgeRuntime(BridgeMode.SAFE, () -> 3, cancelling, new BridgeTelemetry(), (m, t) -> {});
        holder[0].admit("Legacy");
        holder[0].submit("Legacy", null, new FakeTask(9, true, 1, () -> {}), 0);
        assertTrue(real.get(0).get());
    }
}
