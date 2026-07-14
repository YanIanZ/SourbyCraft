package dev.iyanz.sourbycraft.brand;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import net.kyori.adventure.text.format.TextColor;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

/**
 * SourbyCraft JVM GC + flag advisor.
 *
 * <p>Inspects the running JVM at startup and warns operators about GC choices and
 * flag combinations known to cause unpredictable server latency.
 *
 * <p>Gated by baked key {@code branding.gc-advisor.enabled} (default {@code true}).
 * When disabled the advisor short-circuits and returns an "acceptable" result with no
 * warnings so callers need no changes.
 *
 * <p>Ported from the Paper tag {@code paper-26.2-pre-folia}; the warning banner is now
 * colored with ANSI 24-bit truecolor derived from {@link SourbyCraftColors#WARNING}.
 */
public final class GcAdvisor {

    public record Result(boolean acceptable, List<String> warnings) {}

    /**
     * Read from the unified TOML key {@code branding.gc-advisor.enabled} (default {@code true}).
     */
    // Read lazily, NOT in a static final: class-init can precede unified-config load, which
    // would latch the operator's setting to the default for the whole boot.
    private static boolean enabled() {
        return dev.iyanz.sourbycraft.SourbyCraftConfig.cfgBool("branding.gc-advisor.enabled", true);
    }

    private static final String ESC = "\u001B";
    private static final String RESET = ESC + "[0m";

    private static String fg(TextColor c) {
        return ESC + "[38;2;" + c.red() + ";" + c.green() + ";" + c.blue() + "m";
    }

    private GcAdvisor() {}

    public static Result run() {
        // gate — if operator disabled gc-advisor in baked yml, skip.
        if (!enabled()) {
            dev.iyanz.sourbycraft.util.SourbyLogger.info(
                "gc-advisor disabled via branding.gc-advisor.enabled=false");
            return new Result(true, List.of());
        }
        List<String> gcNames = ManagementFactory.getGarbageCollectorMXBeans().stream()
            .map(b -> b.getName()).toList();
        List<String> jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments();
        long xms = parseMemArg(jvmArgs, "-Xms");
        long xmx = parseMemArg(jvmArgs, "-Xmx");
        return evaluate(gcNames, jvmArgs, xms, xmx);
    }

    /**
     * Modern (Java 25 / ZGC-era) evaluation. Only flags that ACTIVELY hurt are warned about —
     * the old advice (AlwaysPreTouch, Xms=Xmx, ZGenerational, UseLargePages) is retired: it
     * pins the whole heap resident from tick 0 (panels chart that as "RAM usage"), blocks ZGC
     * uncommit, and ZGenerational was removed in JDK 24 (ZGC is generational by default).
     */
    public static Result evaluate(List<String> gcNames, List<String> jvmArgs, long xms, long xmx) {
        List<String> warns = new ArrayList<>();
        boolean isZgc = gcNames.stream().anyMatch(n -> n.contains("ZGC"));
        boolean isG1 = gcNames.stream().anyMatch(n -> n.contains("G1"));
        if (!isZgc && !isG1) {
            warns.add("GC is " + gcNames + " — use -XX:+UseZGC (low-pause, uncommits idle heap).");
        }
        if (jvmArgs.stream().anyMatch(a -> a.startsWith("-XX:+ZGenerational"))) {
            warns.add("-XX:+ZGenerational was REMOVED in JDK 24 — drop the flag (ZGC is generational by default).");
        }
        if (isZgc && jvmArgs.stream().anyMatch(a -> a.startsWith("-XX:+AlwaysPreTouch"))) {
            warns.add("-XX:+AlwaysPreTouch pins the ENTIRE heap resident from boot — drop it so idle heap returns to the OS.");
        }
        if (isZgc && jvmArgs.stream().noneMatch(a -> a.startsWith("-XX:ZUncommitDelay"))) {
            warns.add("Add -XX:ZUncommitDelay=60 so ZGC returns idle heap pages to the OS quickly.");
        }
        if (xms > 0 && xmx > 0 && xms == xmx && xmx >= 4096) {
            warns.add("-Xms equal to -Xmx commits the full heap up-front — use a small -Xms (e.g. 2G) and let it grow.");
        }
        return new Result(warns.isEmpty(), warns);
    }

    private static long parseMemArg(List<String> args, String prefix) {
        for (String a : args) {
            if (a.startsWith(prefix)) {
                String v = a.substring(prefix.length()).toLowerCase(java.util.Locale.ROOT);
                try {
                    if (v.endsWith("g")) return Long.parseLong(v.substring(0, v.length() - 1)) * 1024;
                    if (v.endsWith("m")) return Long.parseLong(v.substring(0, v.length() - 1));
                    if (v.endsWith("k")) return Long.parseLong(v.substring(0, v.length() - 1)) / 1024;
                    return Long.parseLong(v) / (1024 * 1024);
                } catch (NumberFormatException ignored) {}
            }
        }
        return 0;
    }

    /** Compact WARN lines instead of the old box banner (whose advice was outdated anyway). */
    public static String renderWarningBanner(Result r) {
        if (r.acceptable()) return "";
        final String w = fg(SourbyCraftColors.WARNING);
        StringBuilder sb = new StringBuilder();
        sb.append(w).append("[SourbyCraft] JVM flag advisor:").append(RESET).append('\n');
        for (String warn : r.warnings()) {
            sb.append(w).append("[SourbyCraft]   - ").append(warn).append(RESET).append('\n');
        }
        sb.append(w).append("[SourbyCraft]   Recommended (Java 25): -Xms2G -Xmx<75-85% of allocation> "
            + "-XX:+UseZGC -XX:ZUncommitDelay=60 --add-modules=jdk.incubator.vector").append(RESET).append('\n');
        return sb.toString();
    }
}
