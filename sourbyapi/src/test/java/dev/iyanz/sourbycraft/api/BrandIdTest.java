package dev.iyanz.sourbycraft.api;

import io.papermc.paper.ServerBuildInfo;
import java.util.Arrays;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ServerBuildInfo.BRAND_CANVAS_ID} is removed without a shim (owner decision 2026-10-21, breaking API change);
 * {@code BRAND_SOURBYCRAFT_ID} matches the server jar's {@code Brand-Id: sourbycraft:sourbycraft}.
 */
class BrandIdTest {

    @Test
    void sourbyCraftBrandIdMatchesTheJarManifest() {
        assertEquals(Key.key("sourbycraft", "sourbycraft"), ServerBuildInfo.BRAND_SOURBYCRAFT_ID);
    }

    @Test
    void canvasBrandIdIsGone() {
        assertTrue(Arrays.stream(ServerBuildInfo.class.getFields()).noneMatch(field -> field.getName().equals("BRAND_CANVAS_ID")),
            "BRAND_CANVAS_ID was removed in Build 48 with no compatibility constant");
        assertTrue(Arrays.stream(ServerBuildInfo.class.getFields()).noneMatch(field -> {
            try {
                return field.get(null) instanceof Key key && key.namespace().equals("canvasmc");
            } catch (final IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }), "no brand key in the canvasmc namespace");
    }
}
