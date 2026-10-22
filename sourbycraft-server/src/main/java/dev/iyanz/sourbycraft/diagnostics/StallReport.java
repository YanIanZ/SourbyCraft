package dev.iyanz.sourbycraft.diagnostics;

import dev.iyanz.sourbycraft.util.ContainerMemory;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The text of one stall dump. Each section is built independently and a failing one is replaced by a
 * single line, so a broken probe never costs the thread dump.
 *
 * <p>The thread section is written here rather than taken from {@link ThreadInfo#toString()}, which
 * stops after eight frames: a blocked global tick is usually deeper than that.</p>
 */
final class StallReport {

    /** One dump section is cut here, so a runaway thread count cannot write an unbounded file. */
    static final int MAX_CHARS = 4 * 1024 * 1024;

    private StallReport() {}

    /** Inputs that are not read from the JVM. */
    record Context(StallRecorder.DumpRequest request, long epochMillis, long afterSeconds, long repeatSeconds,
                   String globalFrameMarker, String regionFrameMarker, List<String> population) {}

    static String render(final Context context) {
        final StringBuilder out = new StringBuilder(64 * 1024);
        section(out, () -> header(out, context), "header");
        final ThreadInfo[] threads = dumpThreads(out);
        section(out, () -> stalledThreads(out, context, threads), "stalled threads");
        section(out, () -> memory(out), "memory");
        section(out, () -> collectors(out), "garbage collection");
        section(out, () -> load(out), "load");
        section(out, () -> population(out, context.population()), "loaded chunks, entities, players");
        section(out, () -> deadlocks(out), "deadlocks");
        section(out, () -> allThreads(out, threads), "all threads");
        out.append("=== end of dump ").append(context.request().index()).append(" ===\n\n");
        if (out.length() > MAX_CHARS) {
            out.setLength(MAX_CHARS);
            out.append("\n... dump cut at ").append(MAX_CHARS).append(" characters ...\n");
        }
        return out.toString();
    }

    private static void section(final StringBuilder out, final Runnable body, final String name) {
        final int mark = out.length();
        try {
            body.run();
        } catch (final Throwable failure) {
            out.setLength(mark);
            out.append("--- ").append(name).append(" ---\n(unavailable: ").append(failure).append(")\n\n");
        }
    }

    // ---- header ----

    private static void header(final StringBuilder out, final Context c) {
        final StallRecorder.DumpRequest r = c.request();
        out.append("=== SourbyCraft stall recorder: stall #").append(r.stallNumber()).append(", dump ")
            .append(r.index());
        if (r.kind() == StallRecorder.Kind.FINAL) {
            out.append(" (FINAL: the JVM is shutting down while the stall is still open)");
        } else {
            out.append(" of at most ").append(r.maxDumps());
        }
        out.append(" ===\n");
        out.append("time (UTC): ").append(Instant.ofEpochMilli(c.epochMillis())).append('\n');
        out.append("stall age: ").append(seconds(r.stallAgeNanos())).append(" since the first source crossed the ")
            .append(c.afterSeconds()).append(" s threshold; next dump after ").append(c.repeatSeconds())
            .append(" s if it lasts\n");
        out.append("jvm uptime: ").append(seconds(
            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(ManagementFactory.getRuntimeMXBean().getUptime())))
            .append("; pid ").append(ProcessHandle.current().pid()).append("; ")
            .append(System.getProperty("java.vm.name")).append(' ').append(System.getProperty("java.runtime.version"))
            .append('\n');
        out.append("stalled sources (").append(r.stalled().size()).append("):\n");
        for (final StallRecorder.Stalled s : r.stalled()) {
            out.append("  - ").append(StallRecorder.describe(s)).append('\n');
        }
        out.append('\n');
    }

    // ---- threads ----

    /** Takes the dump (a safepoint operation) and writes how long it took. */
    private static ThreadInfo[] dumpThreads(final StringBuilder out) {
        final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        final long begun = System.nanoTime();
        try {
            final ThreadInfo[] infos = bean.dumpAllThreads(true, true);
            out.append("thread dump taken in ").append(String.format(Locale.ROOT, "%.1f ms",
                (System.nanoTime() - begun) / 1e6)).append(": ").append(infos.length).append(" threads\n\n");
            return infos;
        } catch (final Throwable failure) {
            out.append("--- threads ---\n(unavailable: ").append(failure).append(")\n\n");
            return new ThreadInfo[0];
        }
    }

    private static void stalledThreads(final StringBuilder out, final Context c, final ThreadInfo[] threads) {
        final boolean globalStalled = c.request().stalled().stream().anyMatch(StallRecorder.Stalled::global);
        final List<ThreadInfo> global = new ArrayList<>();
        final List<ThreadInfo> region = new ArrayList<>();
        for (final ThreadInfo info : threads) {
            if (info == null) continue;
            if (stackContains(info, c.globalFrameMarker())) {
                global.add(info);
            } else if (stackContains(info, c.regionFrameMarker())) {
                region.add(info);
            }
        }
        out.append("--- the stalled thread(s) ---\n");
        if (globalStalled) {
            if (global.isEmpty()) {
                out.append("The global tick is stalled but no thread has its tick code on the stack: it is not inside a tick "
                    + "(waiting for the scheduler, or the thread is gone). Compare the Folia Region Scheduler threads below.\n");
            } else {
                out.append("Thread(s) running the global tick (stack contains ").append(c.globalFrameMarker()).append("):\n\n");
                for (final ThreadInfo info : global) {
                    out.append(formatThread(info, cpuNanos(info))).append('\n');
                }
            }
        }
        out.append("Other threads inside region tick or task code (").append(region.size())
            .append("; full stacks in \"all threads\"):\n");
        for (final ThreadInfo info : region) {
            final StackTraceElement[] st = info.getStackTrace();
            out.append("  \"").append(info.getThreadName()).append("\" ").append(info.getThreadState());
            if (info.getLockName() != null) {
                out.append(" on ").append(info.getLockName());
                if (info.getLockOwnerName() != null) {
                    out.append(" owned by \"").append(info.getLockOwnerName()).append('"');
                }
            }
            out.append(st.length == 0 ? "" : " at " + st[0]).append('\n');
        }
        out.append('\n');
    }

    private static void allThreads(final StringBuilder out, final ThreadInfo[] threads) {
        out.append("--- all threads (").append(threads.length).append(") ---\n");
        for (final ThreadInfo info : threads) {
            if (info != null) {
                out.append(formatThread(info, cpuNanos(info))).append('\n');
            }
        }
    }

    private static long cpuNanos(final ThreadInfo info) {
        try {
            return ManagementFactory.getThreadMXBean().getThreadCpuTime(info.getThreadId());
        } catch (final Throwable unsupported) {
            return -1L;
        }
    }

    static boolean stackContains(final ThreadInfo info, final String marker) {
        if (marker == null || marker.isEmpty()) return false;
        for (final StackTraceElement frame : info.getStackTrace()) {
            if (frame.getClassName().contains(marker)) return true;
        }
        return false;
    }

    /** A thread as {@code jstack} shows it: state, the lock it waits on and who owns it, every frame, the monitors it holds. */
    static String formatThread(final ThreadInfo info, final long cpuNanos) {
        final StringBuilder out = new StringBuilder(512);
        out.append('"').append(info.getThreadName()).append("\" #").append(info.getThreadId());
        if (info.isDaemon()) out.append(" daemon");
        out.append(" prio=").append(info.getPriority());
        if (cpuNanos >= 0L) out.append(String.format(Locale.ROOT, " cpu=%.1fms", cpuNanos / 1e6));
        out.append(" state=").append(info.getThreadState()).append('\n');
        if (info.getLockInfo() != null) {
            out.append("   waiting on <").append(info.getLockName()).append('>');
            if (info.getLockOwnerName() != null) {
                out.append(" held by \"").append(info.getLockOwnerName()).append("\" #").append(info.getLockOwnerId());
            } else {
                out.append(" (no owner: a condition, park or monitor wait)");
            }
            out.append('\n');
        }
        final StackTraceElement[] stack = info.getStackTrace();
        final MonitorInfo[] monitors = info.getLockedMonitors();
        for (int i = 0; i < stack.length; ++i) {
            out.append("   at ").append(stack[i]).append('\n');
            for (final MonitorInfo monitor : monitors) {
                if (monitor.getLockedStackDepth() == i) {
                    out.append("   - locked <").append(monitor).append(">\n");
                }
            }
        }
        for (final MonitorInfo monitor : monitors) {
            if (monitor.getLockedStackDepth() < 0 || monitor.getLockedStackDepth() >= stack.length) {
                out.append("   - locked <").append(monitor).append(">\n");
            }
        }
        if (info.getLockedSynchronizers().length > 0) {
            out.append("   holds ownable synchronizers:");
            for (final var lock : info.getLockedSynchronizers()) {
                out.append(" <").append(lock).append('>');
            }
            out.append('\n');
        }
        return out.toString();
    }

    private static void deadlocks(final StringBuilder out) {
        final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        final long[] ids = bean.findDeadlockedThreads();
        out.append("--- deadlocks ---\n");
        if (ids == null || ids.length == 0) {
            out.append("none found by findDeadlockedThreads (monitors and ownable synchronizers)\n\n");
            return;
        }
        out.append(ids.length).append(" deadlocked thread(s):\n");
        for (final ThreadInfo info : bean.getThreadInfo(ids, true, true)) {
            if (info != null) out.append(formatThread(info, -1L)).append('\n');
        }
    }

    // ---- memory ----

    private static void memory(final StringBuilder out) {
        out.append("--- memory ---\n");
        final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        out.append("heap:      ").append(usage(memory.getHeapMemoryUsage())).append('\n');
        out.append("non-heap:  ").append(usage(memory.getNonHeapMemoryUsage())).append('\n');
        for (final BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            out.append("buffers ").append(pool.getName()).append(": ").append(pool.getCount()).append(" buffers, used ")
                .append(mib(pool.getMemoryUsed())).append(", capacity ").append(mib(pool.getTotalCapacity())).append('\n');
        }
        out.append("max memory (Runtime): ").append(mib(Runtime.getRuntime().maxMemory()))
            .append(", explicit -Xmx/MaxRAMPercentage: ").append(ContainerMemory.explicitXmx()).append('\n');
        final String status = readFile("/proc/self/status");
        if (status == null) {
            out.append("process RSS: not available (no /proc/self/status on this OS)\n");
        } else {
            for (final String line : status.split("\n")) {
                if (line.startsWith("VmRSS:") || line.startsWith("VmHWM:") || line.startsWith("VmSize:")
                    || line.startsWith("VmSwap:") || line.startsWith("Threads:")) {
                    out.append("proc ").append(line.replaceAll("\\s+", " ")).append('\n');
                }
            }
        }
        final long limit = ContainerMemory.limitBytes();
        final long current = ContainerMemory.currentBytes();
        out.append("container/host memory limit: ").append(limit > 0 ? mib(limit) : "unknown")
            .append("; cgroup memory.current (includes page cache): ").append(current > 0 ? mib(current) : "unknown");
        if (limit > 0 && current > 0) {
            out.append(String.format(Locale.ROOT, " = %.1f%% of the limit", 100.0 * current / limit));
        }
        out.append('\n');
        final long swapTotal = ContainerMemory.swapTotalBytes();
        if (swapTotal > 0) {
            final long swapUsed = ContainerMemory.swapUsedBytes(swapTotal);
            out.append("swap: total ").append(mib(swapTotal)).append(", used ").append(swapUsed >= 0 ? mib(swapUsed) : "unknown")
                .append('\n');
        }
        for (final String file : new String[] {"memory.max", "memory.current", "memory.high", "memory.swap.current",
            "memory.events", "memory.pressure", "memory.stat", "memory.limit_in_bytes", "memory.usage_in_bytes",
            "memory.max_usage_in_bytes", "memory.failcnt", "memory.oom_control"}) {
            final String text = ContainerMemory.readCgroupFile(file);
            if (text != null) {
                out.append("cgroup ").append(file).append(":\n");
                for (final String line : text.split("\n")) {
                    out.append("    ").append(line).append('\n');
                }
            }
        }
        out.append("(cgroup memory.events: oom_kill > 0 means the kernel killed a process of this container for memory)\n\n");
    }

    private static void collectors(final StringBuilder out) {
        out.append("--- garbage collection (cumulative since JVM start) ---\n");
        for (final GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            out.append(gc.getName()).append(": ").append(gc.getCollectionCount()).append(" collections, ")
                .append(gc.getCollectionTime()).append(" ms\n");
        }
        out.append('\n');
    }

    private static void load(final StringBuilder out) {
        out.append("--- load ---\n");
        final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        out.append("threads: ").append(threads.getThreadCount()).append(" live (peak ").append(threads.getPeakThreadCount())
            .append(", started ").append(threads.getTotalStartedThreadCount()).append(")\n");
        final var os = ManagementFactory.getOperatingSystemMXBean();
        out.append("available processors: ").append(os.getAvailableProcessors()).append(", system load average: ")
            .append(os.getSystemLoadAverage()).append('\n');
        if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
            out.append(String.format(Locale.ROOT, "process cpu load: %.1f%%, system cpu load: %.1f%%%n",
                sun.getProcessCpuLoad() * 100.0, sun.getCpuLoad() * 100.0));
        }
        out.append('\n');
    }

    private static void population(final StringBuilder out, final List<String> lines) {
        out.append("--- loaded chunks, entities, players (region counters, read without locks) ---\n");
        if (lines == null || lines.isEmpty()) {
            out.append("(not available)\n");
        } else {
            for (final String line : lines) {
                out.append(line).append('\n');
            }
        }
        out.append('\n');
    }

    // ---- formatting ----

    private static String usage(final MemoryUsage usage) {
        return "used " + mib(usage.getUsed()) + ", committed " + mib(usage.getCommitted())
            + ", max " + (usage.getMax() < 0 ? "unbounded" : mib(usage.getMax()));
    }

    static String mib(final long bytes) {
        return bytes < 0 ? "?" : String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0);
    }

    static String seconds(final long nanos) {
        return String.format(Locale.ROOT, "%.1f s", nanos / 1e9);
    }

    private static String readFile(final String path) {
        try {
            return Files.readString(Path.of(path));
        } catch (final Throwable unavailable) {
            return null;
        }
    }
}
