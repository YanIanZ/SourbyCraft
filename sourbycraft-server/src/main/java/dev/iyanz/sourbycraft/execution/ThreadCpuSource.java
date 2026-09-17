package dev.iyanz.sourbycraft.execution;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;

/** Where per-thread CPU readings come from, so the lane sampler can be exercised without a JVM's threads. */
@FunctionalInterface
public interface ThreadCpuSource {

    /** One thread's cumulative CPU time at the moment of reading. */
    record ThreadCpu(long id, String name, long cpuNanos) {}

    /**
     * Every live thread's cumulative CPU time.
     *
     * @return a reading per thread; empty when the platform cannot measure thread CPU
     */
    List<ThreadCpu> sample();

    /** Reads the running JVM, or reports nothing if it cannot measure per-thread CPU. */
    static ThreadCpuSource platform() {
        final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        if (!threads.isThreadCpuTimeSupported()) {
            return List::of;
        }
        if (!threads.isThreadCpuTimeEnabled()) {
            try {
                threads.setThreadCpuTimeEnabled(true);
            } catch (final UnsupportedOperationException | SecurityException refused) {
                return List::of;
            }
        }
        return () -> {
            final long[] ids = threads.getAllThreadIds();
            // Depth 0: names without stack traces. Walking stacks for every thread once a second
            // would cost far more than the measurement is worth.
            final ThreadInfo[] info = threads.getThreadInfo(ids, 0);
            final List<ThreadCpu> sample = new ArrayList<>(ids.length);
            for (int i = 0; i < ids.length; i++) {
                if (info[i] == null) {
                    continue;                 // Died between listing the ids and reading them.
                }
                final long cpu = threads.getThreadCpuTime(ids[i]);
                if (cpu >= 0L) {
                    sample.add(new ThreadCpu(ids[i], info[i].getThreadName(), cpu));
                }
            }
            return sample;
        };
    }
}
