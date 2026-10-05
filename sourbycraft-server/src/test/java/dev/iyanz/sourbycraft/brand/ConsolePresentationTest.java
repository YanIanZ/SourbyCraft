package dev.iyanz.sourbycraft.brand;

import org.junit.jupiter.api.Test;
import dev.iyanz.sourbycraft.util.BarUtil;
import static org.junit.jupiter.api.Assertions.*;

public class ConsolePresentationTest {
    @Test void v22RedirectedConsoleIsPlainAndNoColorIsRespected() {
        var redirected = ConsoleStyle.resolve("auto", false, false, false);
        var line = AuroraBoot.render(3, 13, new AuroraBoot.Stage("commands", true, 42), 1, redirected);
        assertFalse(line.contains("\u001b"));
        assertFalse(line.contains("█"));
        assertTrue(line.contains("23%"));
        assertTrue(line.contains("42ms"));
        assertTrue(line.contains("1 failed"));
        assertFalse(ConsoleStyle.resolve("rich", true, true, false).color());
        assertTrue(ConsoleStyle.resolve("rich", true, true, false).unicode());
        assertFalse(ConsoleStyle.resolve("auto", true, false, true).color());
    }

    @Test void v22FullProgressNeverClaimsMinecraftIsReady() {
        var summary = AuroraBoot.summary(13, 2, 100);
        assertTrue(summary.contains("11/13"));
        assertTrue(summary.contains("DEGRADED"));
        assertTrue(summary.contains("server-ready message"));
        assertFalse(summary.contains("Done ("), "startup hints must not trigger readiness probes");
        assertFalse(summary.contains("online"));
        var failed = AuroraBoot.render(13, 13, new AuroraBoot.Stage("gc tracker", false, 8), 2,
            ConsoleStyle.resolve("plain", false, false, false));
        assertTrue(failed.contains("100%"));
        assertTrue(failed.contains("FAIL"));
    }

    @Test void v22UnknownMeasurementsDoNotLookLikeZeroOrFull() {
        assertTrue(BarUtil.barWithPercent(Double.NaN, 18).contains("unavailable"));
        assertTrue(BarUtil.barWithPercent(Double.POSITIVE_INFINITY, 18).contains("unavailable"));
        assertEquals("0 B", BarUtil.formatBytes(0));
        assertEquals("unavailable", BarUtil.formatBytes(-1));
        assertEquals("[····] 0%", BarUtil.barWithPercent(-20, 4));
        assertEquals("[████] 100%", BarUtil.barWithPercent(120, 4));
    }

    @Test void v22LongBuildIdentityIsPreservedInPlainBanner() {
        var info = new BuildInfo("DEV", "47", "47", "26.2", "a-codename-longer-than-the-former-fixed-frame-width", "unused", "unknown");
        var banner = SourbyCraftBanner.render(info, ConsoleStyle.resolve("plain", false, false, false));
        assertTrue(banner.contains(info.releaseIdentity()));
        assertTrue(banner.contains("Minecraft  26.2"));
        assertFalse(banner.contains("\u001b"));
    }
}
