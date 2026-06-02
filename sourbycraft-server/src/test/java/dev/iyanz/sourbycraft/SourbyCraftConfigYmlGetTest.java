package dev.iyanz.sourbycraft;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourbyCraftConfigYmlGetTest {

    @Test
    void returnsDefaultWhenKeyMissing() {
        Object r = SourbyCraftConfig.ymlGet("totally.nonexistent.key", "fallback");
        assertEquals("fallback", r);
    }

    @Test
    void readsBaselineBooleanWithDottedPath() {
        // pvp.enabled is false in baseline sourbycraft.yml
        Boolean v = SourbyCraftConfig.ymlGet("pvp.enabled", Boolean.TRUE);
        assertEquals(Boolean.FALSE, v);
    }

    @Test
    void readsBaselineIntWithDottedPath() {
        // pvp.view-distance-cap = 10 in baseline
        Integer v = SourbyCraftConfig.ymlGet("pvp.view-distance-cap", 99);
        assertEquals(10, v);
    }

    @Test
    void readsBaselineDouble() {
        // pvp.knockback.friction-divisor = 2.0 in baseline
        Double v = SourbyCraftConfig.ymlGet("pvp.knockback.friction-divisor", 99.0);
        assertEquals(2.0, v);
    }
}
