package dev.iyanz.sourbycraft.brand;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;

class BuildInfoTest {

    @Test
    void readsVariantFromProperties() {
        var stream = new ByteArrayInputStream("""
            variant=pvp
            version=v12-REL
            mcVersion=1.21.11
            tagline=Lightning Fast Performance · Feature Rich
            buildTimestamp=2026-06-02T00:00:00Z
            """.getBytes());
        var info = BuildInfo.loadFrom(stream);
        assertEquals("pvp", info.variant());
        assertEquals("v12-REL", info.version());
        assertEquals("1.21.11", info.mcVersion());
        assertTrue(info.tagline().contains("Lightning Fast"));
    }

    @Test
    void fallsBackOnMissingResource() {
        var info = BuildInfo.loadFrom(null);
        assertEquals("normal", info.variant());
        assertEquals("dev", info.version());
    }

    @Test
    void isPvpReturnsTrueForPvpVariant() {
        var stream = new ByteArrayInputStream("variant=pvp\n".getBytes());
        assertTrue(BuildInfo.loadFrom(stream).isPvp());
    }

    @Test
    void isPvpReturnsFalseForNormalVariant() {
        var stream = new ByteArrayInputStream("variant=normal\n".getBytes());
        assertFalse(BuildInfo.loadFrom(stream).isPvp());
    }
}
