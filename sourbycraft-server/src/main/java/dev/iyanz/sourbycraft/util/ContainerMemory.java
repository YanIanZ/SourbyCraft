package dev.iyanz.sourbycraft.util;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Container/host memory-limit detection (cgroup v2, cgroup v1, then physical RAM).
 *
 * <p>Panels like Pterodactyl allocate a container memory limit, but a JVM started without
 * {@code -Xmx} defaults to {@code MaxRAMPercentage=25} — a 10 GB allocation yields a ~2.5 GB
 * heap and the operator wonders where their RAM went. This util exposes the limit so the boot
 * advisor and {@code /rambar} can surface the mismatch.
 *
 * <p>The process's own cgroup is resolved through {@code /proc/self/cgroup}: a systemd service or
 * scope (host cgroup view) has no {@code memory.max} at {@code /sys/fs/cgroup}, only under
 * {@code /sys/fs/cgroup/<path>/}. The fixed root paths stay as the fallback for the container view
 * (private cgroup namespace, where the process's cgroup is the mount root).
 *
 * <p>The limit is read once and cached: cgroup limits do not change for the lifetime of the container.
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

    /**
     * Current container memory usage in bytes (cgroup {@code memory.current} / v1 {@code usage_in_bytes});
     * -1 unknown. This is cgroup accounting and includes page cache and kernel memory — it is NOT the
     * process RSS (use {@code /proc/self/status} VmRSS for that).
     */
    public static long currentBytes() {
        return currentBytes(Cgroup.SYSTEM);
    }

    /**
     * Cgroup memory usage as a percentage of the memory limit, or {@link Double#NaN} when the cgroup
     * files are unreadable. Includes page cache and kernel memory, so it is not RSS. Not on any hot
     * path — reads the cgroup file per call.
     */
    public static double usagePercentOrNaN() {
        final long limit = limitBytes();
        final long current = currentBytes();
        if (limit <= 0 || current <= 0) return Double.NaN;
        return 100.0 * current / limit;
    }

    /**
     * Raw text of one cgroup memory file of this process ({@code memory.events}, {@code memory.stat},
     * {@code memory.pressure}, v1 {@code memory.oom_control}, ...), for diagnostics. Read from the cgroup
     * that enforces the limit, else from the process's own; v2 first, then v1. At most 8 KiB, trimmed;
     * {@code null} when the file does not exist or cannot be read. Not on any hot path.
     */
    public static String readCgroupFile(final String fileName) {
        return readCgroupFile(Cgroup.SYSTEM, fileName);
    }

    static String readCgroupFile(final Cgroup cg, final String fileName) {
        final Limited v2 = findLimit(chainV2(cg), "memory.max", 0L);
        final Path v2Dir = v2 != null ? v2.dir() : chainV2(cg).get(0);
        String text = readSmall(v2Dir.resolve(fileName));
        if (text != null) return text;
        final Limited v1 = findLimit(chainV1(cg), "memory.limit_in_bytes", V1_UNLIMITED_FROM);
        final Path v1Dir = v1 != null ? v1.dir() : chainV1(cg).get(0);
        return readSmall(v1Dir.resolve(fileName));
    }

    private static String readSmall(final Path file) {
        try {
            if (!Files.isReadable(file) || Files.isDirectory(file)) return null;
            try (var in = Files.newInputStream(file)) {
                final String text = new String(in.readNBytes(8192), java.nio.charset.StandardCharsets.UTF_8).trim();
                return text.isEmpty() ? null : text;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Where the cgroup filesystem and {@code /proc/self/cgroup} live; injectable for tests. */
    record Cgroup(Path procSelfCgroup, Path fsRoot) {
        static final Cgroup SYSTEM = new Cgroup(Path.of("/proc/self/cgroup"), Path.of("/sys/fs/cgroup"));
    }

    private static final long V1_UNLIMITED_FROM = 1L << 50;

    private static long detect() {
        return detect(Cgroup.SYSTEM);
    }

    static long detect(final Cgroup cg) {
        final Limited v2 = findLimit(chainV2(cg), "memory.max", 0L);
        if (v2 != null) return v2.limit();
        // cgroup v1: an absent/absurd (>1 PiB) value means unlimited.
        final Limited v1 = findLimit(chainV1(cg), "memory.limit_in_bytes", V1_UNLIMITED_FROM);
        if (v1 != null) return v1.limit();
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

    static long currentBytes(final Cgroup cg) {
        long v = usageIn(chainV2(cg), "memory.max", "memory.current", 0L);                              // cgroup v2
        if (v > 0) return v;
        v = usageIn(chainV1(cg), "memory.limit_in_bytes", "memory.usage_in_bytes", V1_UNLIMITED_FROM);  // cgroup v1
        return v > 0 ? v : UNKNOWN;
    }

    /** A cgroup directory that carries a finite limit, and that limit. */
    private record Limited(Path dir, long limit) {}

    /**
     * Smallest finite limit along {@code chain} (leaf first; the nearest one wins ties), or null when
     * every level is unlimited or has no such file. Walking up matters because a leaf scope can say
     * {@code max} while a parent slice enforces the real limit.
     */
    private static Limited findLimit(final List<Path> chain, final String limitFile, final long unlimitedFrom) {
        Limited best = null;
        for (final Path dir : chain) {
            final long v = parseLimit(dir.resolve(limitFile));
            if (v <= 0 || (unlimitedFrom > 0 && v >= unlimitedFrom)) continue;
            if (best == null || v < best.limit()) best = new Limited(dir, v);
        }
        return best;
    }

    /**
     * Usage of the cgroup that enforces the limit (so a limit inherited from a parent is compared with
     * that parent's usage); without any finite limit, the usage of the process's own (leaf) cgroup.
     */
    private static long usageIn(final List<Path> chain, final String limitFile, final String usageFile,
                                final long unlimitedFrom) {
        if (chain.isEmpty()) return UNKNOWN;
        final Limited limited = findLimit(chain, limitFile, unlimitedFrom);
        if (limited != null) {
            final long v = parseLimit(limited.dir().resolve(usageFile));
            if (v > 0) return v;
        }
        return parseLimit(chain.get(0).resolve(usageFile));
    }

    /**
     * cgroup v2 directories from the process's own cgroup up to the mount root. The resolved leaf comes
     * first when it exists; the mount root alone is the container-view fallback (private cgroup namespace,
     * where {@code /proc/self/cgroup} says {@code 0::/}).
     */
    private static List<Path> chainV2(final Cgroup cg) {
        for (final String line : procLines(cg)) {
            if (line.startsWith("0::")) return chain(cg.fsRoot(), line.substring(3).trim());
        }
        return List.of(cg.fsRoot());
    }

    /** cgroup v1 directories of the {@code memory} controller hierarchy, leaf first. */
    private static List<Path> chainV1(final Cgroup cg) {
        final Path base = cg.fsRoot().resolve("memory");
        for (final String line : procLines(cg)) {
            final String[] parts = line.split(":", 3);
            if (parts.length != 3 || parts[0].equals("0")) continue;
            if (List.of(parts[1].split(",")).contains("memory")) return chain(base, parts[2].trim());
        }
        return List.of(base);
    }

    private static List<Path> chain(final Path base, final String cgroupPath) {
        final Path normalizedBase = base.normalize();
        final String relative = cgroupPath.startsWith("/") ? cgroupPath.substring(1) : cgroupPath;
        final Path leaf = normalizedBase.resolve(relative).normalize();
        if (relative.isEmpty() || !leaf.startsWith(normalizedBase) || !Files.isDirectory(leaf)) {
            return List.of(normalizedBase); // root view, escape attempt or a path this mount does not show
        }
        final List<Path> out = new ArrayList<>();
        for (Path d = leaf; d != null && d.startsWith(normalizedBase); d = d.getParent()) {
            out.add(d);
            if (d.equals(normalizedBase)) break;
        }
        return List.copyOf(out);
    }

    private static List<String> procLines(final Cgroup cg) {
        try {
            return Files.readAllLines(cg.procSelfCgroup());
        } catch (Throwable unreadable) {
            return List.of();
        }
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

    /**
     * Host/container swap TOTAL bytes. Prefers the cgroup v2 swap cap ({@code memory.swap.max};
     * {@code "max"} = unlimited, falls through) since that is the figure that actually bounds this
     * container's swap; an explicit {@code 0} at any level is a real cap and returns 0 (no swap, which
     * callers hide). Otherwise the platform bean's total. -1 unknown. Reads files: not for a tick thread
     * (the HUD reads {@link #lastSwapSample()}).
     */
    public static long swapTotalBytes() {
        return swapTotalBytes(Cgroup.SYSTEM);
    }

    static long swapTotalBytes(final Cgroup cg) {
        final List<Path> chain = chainV2(cg);
        // Unlike memory.max, where findLimit skips 0 as "no data", memory.swap.max = 0 is a finite cap:
        // the container may not swap at all, whatever the host's swap total says.
        for (final Path dir : chain) {
            if (parseLimit(dir.resolve("memory.swap.max")) == 0L) return 0L;
        }
        final Limited capped = findLimit(chain, "memory.swap.max", 0L);
        if (capped != null) return capped.limit();
        try {
            var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                long t = sun.getTotalSwapSpaceSize();
                if (t > 0) return t;
            }
        } catch (Throwable ignored) {
        }
        return UNKNOWN;
    }

    /**
     * Swap USED bytes for the given {@code totalBytes} (as returned by {@link #swapTotalBytes()}).
     * Robust against the common container bug where {@code getFreeSwapSpaceSize()} returns a bogus
     * value (negative, or larger than the total) — cgroups often report the total correctly but not
     * the free figure. Prefers the cgroup v2 swap-current counter (exact, and consistent with the
     * total this was paired with); otherwise trusts the platform bean's free-swap figure ONLY when it
     * passes a sanity check (0 &le; free &le; total). Returns -1 ("n/a") when nothing here is
     * trustworthy — callers must show a graceful placeholder, never a raw negative/oversized value.
     */
    public static long swapUsedBytes(long totalBytes) {
        return swapUsedBytes(Cgroup.SYSTEM, totalBytes);
    }

    static long swapUsedBytes(final Cgroup cg, final long totalBytes) {
        if (totalBytes <= 0) return UNKNOWN;
        final List<Path> chain = chainV2(cg);
        final Limited capped = findLimit(chain, "memory.swap.max", 0L);
        final Path dir = capped != null ? capped.dir() : chain.get(0);
        long v = parseLimit(dir.resolve("memory.swap.current"));
        if (v >= 0) return Math.max(0, Math.min(v, totalBytes));
        try {
            var os = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                long free = sun.getFreeSwapSpaceSize();
                if (free >= 0 && free <= totalBytes) return totalBytes - free;
            }
        } catch (Throwable ignored) {
        }
        return UNKNOWN;
    }

    /** One swap reading: {@link #swapTotalBytes()} and the matching {@link #swapUsedBytes(long)}; -1 = unknown. */
    public record SwapSample(long totalBytes, long usedBytes) {}

    private static volatile SwapSample lastSwap;

    /**
     * Reads swap total and used now (cgroup files, platform bean) and keeps the result for
     * {@link #lastSwapSample()}. File I/O: the metrics collector calls it once per second on its own
     * thread; a tick thread must not.
     */
    public static SwapSample sampleSwap() {
        final long total = swapTotalBytes();
        final SwapSample sample = new SwapSample(total, swapUsedBytes(total));
        lastSwap = sample;
        return sample;
    }

    /**
     * The newest {@link #sampleSwap()} result, or {@code null} before the first (metrics collection not
     * running). One volatile read and no I/O, so the HUD may call it on the global region tick.
     */
    public static SwapSample lastSwapSample() {
        return lastSwap;
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

    /**
     * Human-readable size: {@code "1.50G"} (two decimals, about 10 MiB resolution) from 1 GiB up,
     * otherwise whole megabytes; {@code "?"} for {@code <= 0}.
     */
    public static String fmt(long bytes) {
        if (bytes <= 0) return "?";
        double g = bytes / (1024.0 * 1024 * 1024);
        return g >= 1.0 ? String.format(java.util.Locale.ROOT, "%.2fG", g)
            : (bytes / (1024 * 1024)) + "M";
    }
}
