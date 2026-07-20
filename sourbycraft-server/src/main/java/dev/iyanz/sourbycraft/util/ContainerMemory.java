package dev.iyanz.sourbycraft.util;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Container/host memory-limit detection (cgroup v2, cgroup v1, then physical RAM).
 *
 * <p>Panels like Pterodactyl allocate a container memory limit, but a JVM started without
 * {@code -Xmx} defaults to {@code MaxRAMPercentage=25} — a 10 GB allocation yields a ~2.5 GB
 * heap and the operator wonders where their RAM went. This util exposes the limit so the boot
 * advisor and {@code /rambar} can surface the mismatch.
 *
 * <p>Read once and cached: cgroup limits do not change for the lifetime of the container.
 */
public final class ContainerMemory {

    private static final long UNKNOWN = -1L;
    private static volatile long cached = Long.MIN_VALUE;

    private ContainerMemory() {}

    /** Container memory limit in bytes, or total physical RAM outside a container; -1 unknown. */
    public static long limitBytes() {
        long v = cached;
        if (v != Long.MIN_VALUE) return v;
        cached = v = detect();
        return v;
    }

    private static long detect() {
        // cgroup v2 (unified): "max" means unlimited.
        long v = parseLimit(Path.of("/sys/fs/cgroup/memory.max"));
        if (v > 0) return v;
        // cgroup v1: an absent/absurd (>1 PiB) value means unlimited.
        v = parseLimit(Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes"));
        if (v > 0 && v < (1L << 50)) return v;
        // Bare metal / unlimited container: physical RAM via the platform bean.
        try {
            var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                long total = sun.getTotalMemorySize();
                if (total > 0) return total;
            }
        } catch (Throwable ignored) {
        }
        return UNKNOWN;
    }

    private static long parseLimit(Path p) {
        try {
            if (!Files.isReadable(p)) return UNKNOWN;
            String raw = Files.readString(p).trim();
            if (raw.isEmpty() || "max".equals(raw)) return UNKNOWN;
            return Long.parseLong(raw);
        } catch (Throwable ignored) {
            return UNKNOWN;
        }
    }

    /** True when the JVM was launched with an explicit -Xmx. */
    public static boolean explicitXmx() {
        try {
            for (String a : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (a.startsWith("-Xmx") || a.startsWith("-XX:MaxHeapSize") || a.startsWith("-XX:MaxRAMPercentage")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** Human-readable size: {@code "1.5G"} above 1 GiB, otherwise whole megabytes; {@code "?"} for {@code <= 0}. */
    public static String fmt(long bytes) {
        if (bytes <= 0) return "?";
        double g = bytes / (1024.0 * 1024 * 1024);
        return g >= 1.0 ? String.format(java.util.Locale.ROOT, "%.1fG", g)
            : (bytes / (1024 * 1024)) + "M";
    }
}
