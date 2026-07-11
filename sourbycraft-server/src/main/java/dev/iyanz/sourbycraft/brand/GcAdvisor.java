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
    public static final boolean ENABLED =
        dev.iyanz.sourbycraft.SourbyCraftConfig.cfgBool("branding.gc-advisor.enabled", true);

    private static final String ESC = "\u001B";
    private static final String RESET = ESC + "[0m";

    private static String fg(TextColor c) {
        return ESC + "[38;2;" + c.red() + ";" + c.green() + ";" + c.blue() + "m";
    }

    private GcAdvisor() {}

    public static Result run() {
        // gate — if operator disabled gc-advisor in baked yml, skip.
        if (!ENABLED) {
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

    public static Result evaluate(List<String> gcNames, List<String> jvmArgs, long xms, long xmx) {
        List<String> warns = new ArrayList<>();
        boolean isZgc = gcNames.stream().anyMatch(n -> n.contains("ZGC"));
        boolean isG1 = gcNames.stream().anyMatch(n -> n.contains("G1"));
        if (!isZgc && !isG1) {
            warns.add("GC is not ZGC or G1 — detected: " + gcNames + ". Recommended: -XX:+UseZGC -XX:+ZGenerational");
        }
        if (xms > 0 && xmx > 0 && xms != xmx) {
            warns.add("Xms != Xmx (Xms=" + xms + "MB, Xmx=" + xmx + "MB). Set them equal to avoid heap resize pauses.");
        }
        if (jvmArgs.stream().noneMatch(a -> a.contains("AlwaysPreTouch"))) {
            warns.add("Missing -XX:+AlwaysPreTouch — recommended for predictable tick latency.");
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

    public static String renderWarningBanner(Result r) {
        if (r.acceptable()) return "";
        final String w = fg(SourbyCraftColors.WARNING);
        StringBuilder sb = new StringBuilder();
        sb.append(w).append("╔══════════════════════════════════════════════════╗").append(RESET).append('\n');
        sb.append(w).append("║  ⚠  SourbyCraft tuned for ZGC generational       ║").append(RESET).append('\n');
        sb.append(w).append("╠══════════════════════════════════════════════════╣").append(RESET).append('\n');
        for (String warn : r.warnings()) {
            String line = warn.length() > 46 ? warn.substring(0, 43) + "..." : warn;
            sb.append(w).append(String.format("║  %-46s║", line)).append(RESET).append('\n');
        }
        sb.append(w).append("║                                                  ║").append(RESET).append('\n');
        sb.append(w).append("║  Recommended JVM args:                           ║").append(RESET).append('\n');
        sb.append(w).append("║    -XX:+UseZGC -XX:+ZGenerational                ║").append(RESET).append('\n');
        sb.append(w).append("║    -XX:+AlwaysPreTouch                           ║").append(RESET).append('\n');
        sb.append(w).append("║    -XX:+UseLargePages                            ║").append(RESET).append('\n');
        sb.append(w).append("║    -Xms=Xmx (same value)                         ║").append(RESET).append('\n');
        sb.append(w).append("╚══════════════════════════════════════════════════╝").append(RESET).append('\n');
        return sb.toString();
    }
}
