package dev.iyanz.aurora.engine.spark;

import dev.iyanz.aurora.engine.spark.plugin.FoliaTickHook;
import dev.iyanz.aurora.engine.spark.plugin.FoliaTickReporter;
import dev.iyanz.aurora.engine.threadedregions.SchedulerUtil;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OBS-14 engine spark: {@link FoliaTickHook} and {@link FoliaTickReporter}. Spark samples ticks
 * through them, and on a region-threaded server every region thread calls {@code onTick}; they must
 * forward only while a region profile runs and only for the thread that profile pinned, or the
 * profile would count every other region's ticks.
 */
class FoliaTickHookTest {

    private static SchedulerUtil.AffinityHandler handler(final boolean profiling, final boolean onThisThread) {
        final SchedulerUtil.AffinityHandler handler = mock(SchedulerUtil.AffinityHandler.class);
        when(handler.isRunningRegionProfiler()).thenReturn(profiling);
        when(handler.isRunningRegionProfilerOnThread(anyLong(), anyString())).thenReturn(onThisThread);
        return handler;
    }

    @Test
    void hookForwardsNothingWhileNoRegionProfileRuns() {
        try (var ignored = SparkTestSupport.install(handler(false, true))) {
            final FoliaTickHook hook = new FoliaTickHook();
            final List<Integer> ticks = new ArrayList<>();
            hook.addCallback(ticks::add);

            hook.onTick();
            hook.onTick();

            assertEquals(List.of(), ticks);
            assertEquals(0, hook.getCurrentTick(), "the tick counter does not advance either");
        }
    }

    @Test
    void hookIgnoresTicksFromAThreadTheProfileDidNotPin() {
        final SchedulerUtil.AffinityHandler handler = handler(true, false);
        try (var ignored = SparkTestSupport.install(handler)) {
            final FoliaTickHook hook = new FoliaTickHook();
            final List<Integer> ticks = new ArrayList<>();
            hook.addCallback(ticks::add);

            hook.onTick();

            assertEquals(List.of(), ticks);
            verify(handler).isRunningRegionProfilerOnThread(Thread.currentThread().threadId(), Thread.currentThread().getName());
        }
    }

    @Test
    void hookForwardsTicksOfThePinnedThreadAndCountsThem() {
        try (var ignored = SparkTestSupport.install(handler(true, true))) {
            final FoliaTickHook hook = new FoliaTickHook();
            final List<Integer> ticks = new ArrayList<>();
            hook.addCallback(ticks::add);

            hook.onTick();
            hook.onTick();
            hook.onTick();

            assertEquals(List.of(0, 1, 2), ticks);
            assertEquals(3, hook.getCurrentTick());
        }
    }

    @Test
    void hookStartAndCloseAreNoOpsAndARemovedCallbackIsNotCalled() {
        try (var ignored = SparkTestSupport.install(handler(true, true))) {
            final FoliaTickHook hook = new FoliaTickHook();
            final List<Integer> ticks = new ArrayList<>();
            final var callback = (me.lucko.spark.paper.common.tick.TickHook.Callback) ticks::add;
            hook.addCallback(callback);
            hook.start();
            hook.removeCallback(callback);

            hook.onTick();
            hook.close();

            assertEquals(List.of(), ticks);
        }
    }

    @Test
    void hookBeforeTheSchedulerIsChosenFailsLoudly() {
        try (var ignored = SparkTestSupport.install(null)) {
            assertThrows(IllegalStateException.class, () -> new FoliaTickHook().onTick());
            assertThrows(IllegalStateException.class, () -> new FoliaTickReporter().onTick(1.0));
        }
    }

    @Test
    void reporterForwardsTheDurationOnlyForThePinnedThread() {
        try (var ignored = SparkTestSupport.install(handler(true, true))) {
            final FoliaTickReporter reporter = new FoliaTickReporter();
            final List<Double> durations = new ArrayList<>();
            reporter.addCallback(durations::add);

            reporter.onTick(12.5);
            reporter.onTick(3.0);

            assertEquals(List.of(12.5, 3.0), durations);
        }
        try (var ignored = SparkTestSupport.install(handler(true, false))) {
            final FoliaTickReporter reporter = new FoliaTickReporter();
            final List<Double> durations = new ArrayList<>();
            reporter.addCallback(durations::add);
            reporter.onTick(12.5);
            assertEquals(List.of(), durations, "another region's tick");
        }
        try (var ignored = SparkTestSupport.install(handler(false, true))) {
            final FoliaTickReporter reporter = new FoliaTickReporter();
            final List<Double> durations = new ArrayList<>();
            reporter.addCallback(durations::add);
            reporter.onTick(12.5);
            reporter.start();
            reporter.close();
            assertEquals(List.of(), durations, "no region profile running");
        }
    }
}
