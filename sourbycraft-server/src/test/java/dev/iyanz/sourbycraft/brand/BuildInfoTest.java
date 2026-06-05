package dev.iyanz.sourbycraft.brand;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;

class BuildInfoTest {

    @Test
    void readsFieldsFromProperties() {
        var stream = new ByteArrayInputStream("""
            version=12-REL
            mcVersion=1.21.11
            tagline=Lightning Fast Performance · Feature Rich
            buildTimestamp=2026-06-02T00:00:00Z
            """.getBytes());
        var info = BuildInfo.loadFrom(stream);
        assertEquals("12-REL", info.version());
        assertEquals("1.21.11", info.mcVersion());
        assertTrue(info.tagline().contains("Lightning Fast"));
        assertEquals("2026-06-02T00:00:00Z", info.buildTimestamp());
    }

    @Test
    void fallsBackOnMissingResource() {
        var info = BuildInfo.loadFrom(null);
        assertEquals("dev", info.version());
        assertEquals("unknown", info.mcVersion());
    }
}
