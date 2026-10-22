package dev.iyanz.aurora.engine.spark;

import dev.iyanz.sourbycraft.brand.BuildInfo;
import me.lucko.spark.paper.common.platform.PlatformInfo;
import org.bukkit.Server;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OBS-14 engine spark: {@link FoliaPlatformInfo}, the identity a spark report prints about the server.
 * The version must come from the one canonical build identity, never a literal (the comment in the
 * class records a literal that read "Build44" while build 46 shipped).
 */
class FoliaPlatformInfoTest {

    private static Server server(final String brand, final String minecraft) {
        final Server server = mock(Server.class);
        when(server.getName()).thenReturn(brand);
        when(server.getMinecraftVersion()).thenReturn(minecraft);
        return server;
    }

    @Test
    void identifiesASourbyCraftServerAndTakesBrandAndMinecraftVersionFromTheServer() {
        final FoliaPlatformInfo info = new FoliaPlatformInfo(server("SourbyCraft", "26.2"));

        assertEquals(PlatformInfo.Type.SERVER, info.getType());
        assertEquals("SourbyCraft", info.getName(), "the product name is fixed, not the server's brand");
        assertEquals("SourbyCraft", info.getBrand());
        assertEquals("26.2", info.getMinecraftVersion());
    }

    @Test
    void theBrandFollowsTheServerWhileTheNameDoesNot() {
        final FoliaPlatformInfo info = new FoliaPlatformInfo(server("Some Fork", "26.2"));
        assertEquals("Some Fork", info.getBrand());
        assertEquals("SourbyCraft", info.getName());
    }

    @Test
    void versionIsTheCanonicalDisplayVersionPlusTheMinecraftVersion() {
        final FoliaPlatformInfo info = new FoliaPlatformInfo(server("SourbyCraft", "26.2"));

        final String version = info.getVersion();

        assertEquals(BuildInfo.load().displayVersion() + " (MC:26.2)", version);
        assertTrue(version.endsWith(" (MC:26.2)"), version);
        assertFalse(version.startsWith(" "), version);
        assertFalse(version.toLowerCase().contains("canvas") || version.toLowerCase().contains("folia") || version.toLowerCase().contains("paper"),
            "public build identity carries no upstream platform suffix: " + version);
    }

    @Test
    void theInfoIsReadAtCallTimeNotCached() {
        final Server server = server("SourbyCraft", "26.2");
        final FoliaPlatformInfo info = new FoliaPlatformInfo(server);
        when(server.getMinecraftVersion()).thenReturn("26.3");
        assertEquals("26.3", info.getMinecraftVersion());
        assertTrue(info.getVersion().endsWith("(MC:26.3)"));
    }

    @Test
    void sparkDataExportCarriesTheSameFields() {
        final PlatformInfo.Data data = new FoliaPlatformInfo(server("SourbyCraft", "26.2")).toData();
        assertEquals("SourbyCraft", data.getName());
        assertEquals("26.2", data.getMinecraftVersion());
        assertEquals(PlatformInfo.Type.SERVER, data.getType());
    }
}
