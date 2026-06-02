package dev.iyanz.sourbycraft.brand;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourbyCraftBannerTest {

    @Test
    void pvpBannerContainsVariantLine() {
        var info = new BuildInfo("pvp", "v12.0-REL", "1.21.11",
            "Lightning Fast Performance · Feature Rich", "2026-06-02T00:00:00Z");
        String b = SourbyCraftBanner.render(info);
        assertTrue(b.contains("PVP"), "banner should mention PVP variant");
        assertTrue(b.contains("v12.0-REL"));
        assertTrue(b.contains("Lightning Fast Performance"));
        assertTrue(b.contains("Feature Rich"));
        assertTrue(b.contains("1.21.11"));
    }

    @Test
    void normalBannerOmitsPvpSummary() {
        var info = new BuildInfo("normal", "v12.0-REL", "1.21.11",
            "Lightning Fast Performance · Feature Rich", "");
        String b = SourbyCraftBanner.render(info);
        assertTrue(b.contains("NORMAL"));
        assertFalse(b.contains("PvP-tuned defaults active"), "normal banner should not show pvp-tuned line");
    }

    @Test
    void bannerHasBoxFraming() {
        var info = new BuildInfo("normal", "v12.0-REL", "1.21.11", "tag", "");
        String b = SourbyCraftBanner.render(info);
        assertTrue(b.contains("╔"));
        assertTrue(b.contains("╚"));
    }
}
