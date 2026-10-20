package dev.iyanz.aurora.engine.spark;

import dev.iyanz.aurora.engine.threadedregions.SchedulerUtil;
import dev.iyanz.aurora.engine.threadedregions.profiler.RegionProfiler;
import java.lang.reflect.Field;

/**
 * Process-wide state the spark classes read, swapped for a test and put back afterwards:
 * {@code SchedulerUtil.HANDLER} (private, null until a server picks a scheduler) and
 * {@code RegionProfiler.STATE}. Every swap is undone by {@link Swap#close()}.
 */
final class SparkTestSupport {

    private SparkTestSupport() {}

    static final class Swap implements AutoCloseable {
        private final Object previousHandler;
        private final RegionProfiler.ProfilingState previousState;

        private Swap(final Object previousHandler, final RegionProfiler.ProfilingState previousState) {
            this.previousHandler = previousHandler;
            this.previousState = previousState;
        }

        @Override
        public void close() {
            setHandler(this.previousHandler);
            RegionProfiler.STATE.set(this.previousState);
        }
    }

    /** Installs {@code handler} (may be null) and clears the profiling state; close() restores both. */
    static Swap install(final SchedulerUtil.SchedulerHandler handler) {
        final Swap swap = new Swap(getHandler(), RegionProfiler.STATE.get());
        setHandler(handler);
        RegionProfiler.STATE.set(null);
        return swap;
    }

    private static Field handlerField() {
        try {
            final Field field = SchedulerUtil.class.getDeclaredField("HANDLER");
            field.setAccessible(true);
            return field;
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object getHandler() {
        try {
            return handlerField().get(null);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void setHandler(final Object handler) {
        try {
            handlerField().set(null, handler);
        } catch (final ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
