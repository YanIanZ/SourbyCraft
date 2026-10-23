package dev.iyanz.sourbycraft.brand;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The two lines that open the console, logged by Paper's {@code bootstrap} logger from
 * {@code PaperBootstrap} (paper feature patch 0002) in place of Paper's "Running Java ..." and
 * "Loading <brand> <version>" pair:
 * <pre>
 * SourbyCraft Build 48 - Aurora Nexus (DEV, 26.2@432d141) for Minecraft 26.2, built 2026-10-22 03:36 Asia/Jakarta
 * Java 25 (OpenJDK 64-Bit Server VM 25.0.2+10-69, Temurin-25.0.2+10) on Mac OS X 27.0.1 aarch64 | 8 processors | 2048 MiB max heap | ZGC
 * </pre>
 * Build id, codename, channel, source and build time come from SourbyCraft's own
 * {@link BuildInfo} ({@code sourbycraft-build.properties}); the build time is shown in
 * Asia/Jakarta like {@code /ver} and the release titles. Every field degrades to a plain
 * fallback: the boot path never throws over a version string.
 */
public final class LaunchLines {

    static final ZoneId BUILD_ZONE = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter BUILT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ENGLISH);

    private LaunchLines() {}

    /**
     * Called from the patched {@code PaperBootstrap.getStartupVersionMessages()} with the
     * system properties Paper itself prints. Never throws.
     */
    public static List<String> bootstrap(final String brand, final String minecraftVersion,
                                         final String javaSpecVersion, final String vmName, final String vmVersion,
                                         final String vendor, final String vendorVersion,
                                         final String osName, final String osVersion, final String osArch) {
        BuildInfo info = null;
        try {
            info = BuildInfo.load();
        } catch (final Throwable ignored) {
            // the fallback below names the brand without a build id
        }
        ConsoleStyle style;
        try {
            style = ConsoleStyle.current();
        } catch (final Throwable ignored) {
            style = ConsoleStyle.resolve("plain", false, true, true);
        }
        final Runtime runtime = Runtime.getRuntime();
        return render(brand, info, minecraftVersion, javaSpecVersion, vmName, vmVersion, vendor, vendorVersion,
            osName, osVersion, osArch, runtime.availableProcessors(), runtime.maxMemory() / (1024L * 1024L),
            collectorName(), style);
    }

    /** Pure renderer; see the class comment for the shape. */
    public static List<String> render(final String brand, final BuildInfo info, final String minecraftVersion,
                                      final String javaSpecVersion, final String vmName, final String vmVersion,
                                      final String vendor, final String vendorVersion,
                                      final String osName, final String osVersion, final String osArch,
                                      final int processors, final long maxHeapMiB, final String collector,
                                      final ConsoleStyle style) {
        final List<String> lines = new ArrayList<>(2);
        final StringBuilder first = new StringBuilder(160).append(orUnknown(brand, "SourbyCraft")).append(' ');
        String identity = "Build ?";
        String built = "";
        if (info != null) {
            try {
                identity = info.releaseIdentity();
                built = builtAt(info.buildTimestamp());
            } catch (final RuntimeException ignored) {
                // keep the fallbacks
            }
        }
        final String identityLine = style.unicode() ? identity : identity.replace('—', '-');
        final String paintedIdentity = info != null && style.color()
            ? style.paint(identityLine, ReadySummary.stageColor(identityLine)) : identityLine;
        first.append(paintedIdentity)
            .append(" for Minecraft ").append(orUnknown(minecraftVersion, "?"));
        if (!built.isEmpty()) {
            first.append(", built ").append(built);
        }
        lines.add(first.toString());

        final String vendorShown = isBlank(vendorVersion) || "null".equals(vendorVersion) ? vendor : vendorVersion;
        final StringBuilder second = new StringBuilder(160)
            .append("Java ").append(orUnknown(javaSpecVersion, "?"))
            .append(" (").append(orUnknown(vmName, "?")).append(' ').append(orUnknown(vmVersion, "?"));
        if (!isBlank(vendorShown) && !"null".equals(vendorShown)) {
            second.append(", ").append(vendorShown);
        }
        second.append(") on ").append(orUnknown(osName, "?")).append(' ').append(orUnknown(osVersion, "?"))
            .append(' ').append(orUnknown(osArch, "?"))
            .append(" | ").append(Math.max(0, processors)).append(processors == 1 ? " processor" : " processors")
            .append(" | ").append(Math.max(0L, maxHeapMiB)).append(" MiB max heap");
        if (!isBlank(collector)) {
            second.append(" | ").append(collector);
        }
        lines.add(second.toString());
        return List.copyOf(lines);
    }

    /** {@code 2026-10-22 03:36 Asia/Jakarta} for an ISO instant, or empty when absent or unparseable. */
    static String builtAt(final String isoInstant) {
        if (isBlank(isoInstant)) {
            return "";
        }
        try {
            return BUILT.format(ZonedDateTime.ofInstant(Instant.parse(isoInstant.trim()), BUILD_ZONE)) + " " + BUILD_ZONE.getId();
        } catch (final RuntimeException unparseable) {
            return "";
        }
    }

    /** The collector family from the first GC MXBean name: ZGC, G1, Shenandoah, Parallel, Serial, or its raw name. */
    static String collectorName() {
        try {
            final List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
            return beans.isEmpty() ? "" : collectorFamily(beans.get(0).getName());
        } catch (final Throwable unavailable) {
            return "";
        }
    }

    static String collectorFamily(final String beanName) {
        if (beanName == null) return "";
        if (beanName.startsWith("ZGC")) return "ZGC";
        if (beanName.startsWith("G1")) return "G1";
        if (beanName.startsWith("Shenandoah")) return "Shenandoah";
        if (beanName.startsWith("PS ")) return "Parallel";
        if (beanName.startsWith("Copy") || beanName.startsWith("MarkSweepCompact")) return "Serial";
        return beanName;
    }

    private static String orUnknown(final String value, final String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }
}
