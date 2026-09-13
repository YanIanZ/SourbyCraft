package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.util.ContainerMemory;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/** Called only by the lifecycle-owned collector, once per second. Never scans game objects. */
final class RuntimeSampler {
    private RuntimeSampler() {}

    static ImmutableRuntimeMetrics sample() {
        final var memory = ManagementFactory.getMemoryMXBean();
        final var heap = memory.getHeapMemoryUsage();
        final var os = ManagementFactory.getOperatingSystemMXBean();
        double processCpu = Double.NaN;
        double systemCpu = Double.NaN;
        if (os instanceof com.sun.management.OperatingSystemMXBean extended) {
            processCpu = percent(extended.getProcessCpuLoad());
            systemCpu = percent(extended.getCpuLoad());
        }
        long collections = 0L;
        long collectionTime = 0L;
        boolean countKnown = false;
        boolean timeKnown = false;
        for (final var bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            final long count = bean.getCollectionCount();
            final long time = bean.getCollectionTime();
            if (count >= 0) { collections += count; countKnown = true; }
            if (time >= 0) { collectionTime += time; timeKnown = true; }
        }
        final var gc = GcTracker.snapshot();
        return new ImmutableRuntimeMetrics(heap.getUsed(), heap.getMax(), ContainerMemory.usagePercentOrNaN(),
            gc.hasData() ? gc.gcTimePercent() : Double.NaN,
            gc.hasData() ? gc.collectionsPerMin() : Double.NaN,
            gc.hasData() ? gc.avgPauseMs() : Double.NaN,
            heap.getCommitted(), memory.getNonHeapMemoryUsage().getUsed(), readRss(), processCpu, systemCpu,
            os.getAvailableProcessors(), ManagementFactory.getThreadMXBean().getThreadCount(),
            ManagementFactory.getRuntimeMXBean().getUptime(),
            timeKnown ? collectionTime : -1L, countKnown ? collections : -1L);
    }

    static double percent(final double fraction) {
        return Double.isFinite(fraction) && fraction >= 0.0 && fraction <= 1.0
            ? fraction * 100.0 : Double.NaN;
    }

    private static long readRss() {
        try {
            return parseRss(Files.readString(Path.of("/proc/self/status")));
        } catch (IOException | SecurityException unavailable) {
            return -1L; // macOS/Windows: no shell processes or guesses on the collection path.
        }
    }

    static long parseRss(final String status) {
        for (final String line : status.split("\n")) {
            if (!line.startsWith("VmRSS:")) continue;
            final String[] parts = line.substring(6).trim().split("\\s+");
            if (parts.length != 2 || !parts[1].equals("kB")) return -1L;
            try {
                final long kib = Long.parseLong(parts[0]);
                return kib < 0L ? -1L : Math.multiplyExact(kib, 1024L);
            } catch (NumberFormatException | ArithmeticException invalid) {
                return -1L;
            }
        }
        return -1L;
    }
}
