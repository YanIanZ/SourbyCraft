package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.util.SourbyLogger;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

/**
 * Boot-time JVM heap configuration advisor.
 *
 * <p>Servers hosted on Pterodactyl / Pelican / similar control panels
 * read their "RAM usage" indicator from the container's cgroup RSS,
 * which approaches the JVM's <em>committed</em> heap size — not the
 * actual live-set size. When the operator runs with {@code -Xms=-Xmx}
 * (the long-standing Aikar's flags pattern), Java commits the full
 * heap immediately and the panel shows 8 GB / 8 GB the moment the
 * server reaches {@code Done (}.
 *
 * <p>That is not actually a problem — JVM heap commit is not the same
 * as live memory — but operators see the bar peg the limit and
 * conclude the server is OOM-bound. This advisor logs a single
 * informational line at boot pointing at the recommended fix:
 *
 * <pre>
 *   -Xms2G -Xmx8G                       — let the heap grow on demand
 *   -XX:+UseZGC -XX:+ZGenerational      — already on by default
 *   -XX:SoftMaxHeapSize=6G              — soft cap so ZGC uncommits
 *   -XX:ZUncommitDelay=60               — give ZGC permission to give
 *                                          pages back to the kernel
 * </pre>
 *
 * <p>Pure log advisory — no JVM args are mutated, no behavior
 * changes. Operators stay in control.
 */
public final class JvmHeapAdvisor {

    private static volatile boolean adviced = false;

    private JvmHeapAdvisor() {}

    public static void init() {
        if (adviced) return;
        adviced = true;
        try {
            MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
            MemoryUsage heap = bean.getHeapMemoryUsage();
            long committed = heap.getCommitted();
            long max       = heap.getMax();
            long min       = heap.getInit();
            long fourGiB = 4L * 1024 * 1024 * 1024;

            String committedMb = fmt(committed);
            String maxMb       = fmt(max);
            String initMb      = fmt(min);

            SourbyLogger.info("jvm-heap: -Xms " + initMb + " / -Xmx " + maxMb
                    + " / committed " + committedMb);

            // The Pterodactyl scenario: Xms == Xmx >= 4G means the panel
            // sees a fully-committed heap from tick 0 and the bar pegs.
            if (max >= fourGiB && min == max) {
                SourbyLogger.info("jvm-heap: panel will show " + committedMb + " usage from boot — heap is");
                SourbyLogger.info("jvm-heap: committed not consumed; that is normal JVM behaviour with -Xms=-Xmx.");
                SourbyLogger.info("jvm-heap: for a gradual panel display, switch to -Xms2G -Xmx" + maxMb
                        + " and add -XX:SoftMaxHeapSize=" + softCap(max) + " -XX:ZUncommitDelay=60");
            }
        } catch (Throwable t) {
            SourbyLogger.warn("jvm-heap: advisor failed: " + t.getMessage());
        }
    }

    private static String fmt(long bytes) {
        if (bytes <= 0) return "?";
        long mb = bytes / (1024 * 1024);
        if (mb < 1024) return mb + "M";
        return (bytes / (1024L * 1024 * 1024)) + "G";
    }

    private static String softCap(long maxBytes) {
        long mb = maxBytes / (1024 * 1024);
        return (long) (mb * 0.85) + "M";
    }
}
