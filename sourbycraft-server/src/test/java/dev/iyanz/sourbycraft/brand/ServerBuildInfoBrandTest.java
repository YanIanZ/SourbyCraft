package dev.iyanz.sourbycraft.brand;

import io.papermc.paper.ServerBuildInfo;
import io.papermc.paper.ServerBuildInfoImpl;
import io.papermc.paper.util.JarManifests;
import java.util.jar.Manifest;
import net.kyori.adventure.key.Key;
import net.minecraft.SharedConstants;
import org.bukkit.craftbukkit.CraftServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Without a {@code Brand-Id} in the jar manifest (a dev run, these tests) the server's brand falls back to
 * SourbyCraft, not Canvas (Paper feature 0020; {@code BRAND_CANVAS_ID} was removed from the API).
 */
class ServerBuildInfoBrandTest {

    @BeforeAll
    static void version() {
        SharedConstants.tryDetectVersion();
    }

    @Test
    void fallbackBrandIsSourbyCraft() {
        final Manifest manifest = JarManifests.manifest(CraftServer.class);
        assumeTrue(manifest == null || manifest.getMainAttributes().getValue("Brand-Id") == null,
            "running from a jar with a Brand-Id; the fallback is not reached");

        final ServerBuildInfoImpl info = new ServerBuildInfoImpl();

        assertEquals(Key.key("sourbycraft", "sourbycraft"), info.brandId());
        assertEquals(ServerBuildInfo.BRAND_SOURBYCRAFT_ID, info.brandId());
        assertEquals("SourbyCraft", info.brandName());
    }

    @Test
    void sourbyCraftIsCompatibleWithItselfPaperAndFoliaButNotCanvas() {
        final ServerBuildInfoImpl info = new ServerBuildInfoImpl();
        assumeTrue(info.brandId().equals(ServerBuildInfo.BRAND_SOURBYCRAFT_ID), "built with a different Brand-Id");

        assertTrue(info.isBrandCompatible(ServerBuildInfo.BRAND_SOURBYCRAFT_ID));
        assertTrue(info.isBrandCompatible(ServerBuildInfo.BRAND_PAPER_ID));
        assertTrue(info.isBrandCompatible(ServerBuildInfo.BRAND_FOLIA_ID));
        assertFalse(info.isBrandCompatible(Key.key("canvasmc", "canvas")));
    }
}
