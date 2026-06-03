package dev.iyanz.sourbycraft.brand;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourbyCraftBannerTest {

    @Test
    void bannerContainsVersionAndTagline() {
        var info = new BuildInfo("v12.0-REL", "1.21.11",
            "Lightning Fast Performance · Feature Rich", "2026-06-02T00:00:00Z");
        String b = SourbyCraftBanner.render(info);
        assertTrue(b.contains("v12.0-REL"));
        assertTrue(b.contains("Lightning Fast Performance"));
        assertTrue(b.contains("Feature Rich"));
        assertTrue(b.contains("1.21.11"));
    }

    @Test
    void bannerOmitsVariantLabel() {
        var info = new BuildInfo("v12.0-REL", "1.21.11",
            "Lightning Fast Performance · Feature Rich", "");
        String b = SourbyCraftBanner.render(info);
        assertFalse(b.contains("PVP"), "banner must not show variant label");
        assertFalse(b.contains("NORMAL"), "banner must not show variant label");
        assertFalse(b.contains("PvP-tuned defaults active"), "banner must not show pvp-tuned line");
    }

    @Test
    void bannerHasBoxFraming() {
        var info = new BuildInfo("v12.0-REL", "1.21.11", "tag", "");
        String b = SourbyCraftBanner.render(info);
        assertTrue(b.contains("╔"));
        assertTrue(b.contains("╚"));
    }
}
