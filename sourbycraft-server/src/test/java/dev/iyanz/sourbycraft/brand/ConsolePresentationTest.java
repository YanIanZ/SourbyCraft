package dev.iyanz.sourbycraft.brand;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.util.BarUtil;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Build 49 console: launch lines, section rules and the ready summary, plain off a terminal. */
public class ConsolePresentationTest {

    private static final ConsoleStyle PLAIN = ConsoleStyle.resolve("plain", false, false, false);
    private static final ConsoleStyle RICH = ConsoleStyle.resolve("rich", true, false, false);
    private static final BuildInfo DEV = new BuildInfo("26.2-DEV", "48", "48", "26.2", "aurora-nexus", "unused",
        "2026-10-21T20:36:00Z", "dev/build48", "432d1417abcdef");
    private static final BuildInfo REL = new BuildInfo("26.2-REL", "49", "49", "26.2", "aurora-nexus", "unused",
        "2026-10-21T20:36:00Z", "26.2", "0123456789ab");

    @Test void consoleStyleRedirectedIsPlainAndNoColorIsRespected() {
        assertFalse(ConsoleStyle.resolve("auto", false, false, false).unicode());
        assertFalse(ConsoleStyle.resolve("rich", true, true, false).color());
        assertTrue(ConsoleStyle.resolve("rich", true, true, false).unicode());
        assertFalse(ConsoleStyle.resolve("auto", true, false, true).color());
    }

    @Test void launchLinesNameBuildMinecraftBuildTimeAndRuntimeOnTwoLines() {
        final List<String> lines = LaunchLines.render("SourbyCraft", DEV, "26.2", "25", "OpenJDK 64-Bit Server VM",
            "25.0.2+10-69", "Eclipse Adoptium", "Temurin-25.0.2+10", "Mac OS X", "27.0.1", "aarch64", 8, 2048, "ZGC", PLAIN);
        assertEquals(List.of(
            "SourbyCraft Build 48 - Aurora Nexus (DEV, dev/build48@432d141) for Minecraft 26.2, built 2026-10-22 03:36 Asia/Jakarta",
            "Java 25 (OpenJDK 64-Bit Server VM 25.0.2+10-69, Temurin-25.0.2+10) on Mac OS X 27.0.1 aarch64 | 8 processors | 2048 MiB max heap | ZGC"),
            lines);
    }

    @Test void launchLinesKeepTheDashOnlyWhenUnicodeIsOnAndDegradeEveryMissingField() {
        assertTrue(LaunchLines.render("SourbyCraft", DEV, "26.2", "25", "vm", "1", "v", "vv", "os", "1", "x", 1, 1, "G1", RICH)
            .get(0).startsWith("SourbyCraft Build 48 — Aurora Nexus"));
        final List<String> bare = LaunchLines.render(null, null, null, null, null, null, null, "null", null, null, null, 0, -5, "", PLAIN);
        assertEquals("SourbyCraft Build ? for Minecraft ?", bare.get(0));
        assertEquals("Java ? (? ?) on ? ? ? | 0 processors | 0 MiB max heap", bare.get(1));
        // A release has no channel suffix; a vendor.version of "null" (seen on a test boot) falls back to the vendor.
        final List<String> rel = LaunchLines.render("SourbyCraft", REL, "26.2", "25", "vm", "1", "Oracle Corporation", "null",
            "Linux", "6.1", "amd64", 1, 512, "Parallel", PLAIN);
        assertEquals("SourbyCraft Build 49 - Aurora Nexus for Minecraft 26.2, built 2026-10-22 03:36 Asia/Jakarta", rel.get(0));
        assertEquals("Java 25 (vm 1, Oracle Corporation) on Linux 6.1 amd64 | 1 processor | 512 MiB max heap | Parallel", rel.get(1));
        assertEquals("", LaunchLines.builtAt("not a time"));
        assertEquals("", LaunchLines.builtAt(" "));
    }

    @Test void collectorFamilyFromTheMxBeanName() {
        assertEquals("ZGC", LaunchLines.collectorFamily("ZGC Major Cycles"));
        assertEquals("G1", LaunchLines.collectorFamily("G1 Young Generation"));
        assertEquals("Shenandoah", LaunchLines.collectorFamily("Shenandoah Pauses"));
        assertEquals("Parallel", LaunchLines.collectorFamily("PS Scavenge"));
        assertEquals("Serial", LaunchLines.collectorFamily("Copy"));
        assertEquals("Other", LaunchLines.collectorFamily("Other"));
        assertEquals("", LaunchLines.collectorFamily(null));
    }

    @Test void sectionRulesAreFixedWidthAsciiUnlessUnicodeAndNeverCutATitle() {
        final String plain = ConsoleSections.rule("Worlds", PLAIN);
        assertEquals("-- Worlds ----------------------------------------------", plain);
        assertEquals(ConsoleSections.WIDTH, plain.length());
        final String rich = ConsoleSections.rule("Plugins (post-world)", RICH);
        assertTrue(rich.startsWith("── Plugins (post-world) ─"), rich);
        assertEquals(ConsoleSections.WIDTH, rich.length());
        assertFalse(plain.contains("\u001b"));
        final String longTitle = ConsoleSections.rule("x".repeat(70), PLAIN);
        assertTrue(longTitle.contains("x".repeat(70)));
    }

    @Test void readySummaryIsOneLineWithBuildMinecraftTimeWorldsPluginsAndThreads() {
        final ReadySummary summary = new ReadySummary(DEV.releaseIdentity(), "26.2", 16480L,
            "cold start; no previous profile", 3, 1, 1, 22, 8);
        assertEquals("SourbyCraft Build 48 - Aurora Nexus (DEV, dev/build48@432d141) | Minecraft 26.2"
            + " | ready 16.5 s after JVM start (cold start; no previous profile) | 3 worlds | 1/1 plugin enabled"
            + " | 22 configured threads on 8 processors", summary.render(PLAIN));
        assertTrue(summary.render(RICH).contains("Build 48 — Aurora Nexus"));
    }

    @Test void readySummaryNeverFabricatesACountItCouldNotRead() {
        final String line = new ReadySummary("Build ?", "?", 0L, "", -1, -1, -1, -1, 1).render(PLAIN);
        assertEquals("SourbyCraft Build ? | Minecraft ? | ready 0.0 s after JVM start | worlds unavailable"
            + " | plugins unavailable | threads unavailable on 1 processor", line);
        assertFalse(line.contains("Done ("), "the summary must not trigger readiness probes");
        final String one = new ReadySummary("Build 1", "26.2", 1000L, null, 1, 0, 2, 4, 2).render(PLAIN);
        assertTrue(one.contains("| 1 world | 0/2 plugins enabled | 4 configured threads on 2 processors"), one);
    }

    @Test void collectingWithoutARealServerNeverThrowsAndNeverInventsACount() {
        // Outside a server every lookup fails (-1, rendered "unavailable"); under the suite's mocked
        // Bukkit server the lists are simply empty (0). Either is honest; a positive count is not.
        final ReadySummary summary = ReadySummary.collect(5L, "x");
        assertTrue(summary.worlds() <= 0, "worlds " + summary.worlds());
        assertTrue(summary.pluginsTotal() <= 0, "plugins " + summary.pluginsTotal());
        assertTrue(summary.pluginsEnabled() <= 0);
        assertTrue(summary.processors() > 0);
        assertEquals(5L, summary.readyMillis());
        assertTrue(summary.render(PLAIN).contains("ready 0.0 s after JVM start (x)"), summary.render(PLAIN));
    }

    @Test void unknownMeasurementsDoNotLookLikeZeroOrFull() {
        assertTrue(BarUtil.barWithPercent(Double.NaN, 18).contains("unavailable"));
        assertTrue(BarUtil.barWithPercent(Double.POSITIVE_INFINITY, 18).contains("unavailable"));
        assertEquals("0 B", BarUtil.formatBytes(0));
        assertEquals("unavailable", BarUtil.formatBytes(-1));
        assertEquals("[····] 0%", BarUtil.barWithPercent(-20, 4));
        assertEquals("[████] 100%", BarUtil.barWithPercent(120, 4));
    }
}
