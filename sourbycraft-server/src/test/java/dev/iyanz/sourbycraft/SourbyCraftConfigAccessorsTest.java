package dev.iyanz.sourbycraft;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SourbyCraftConfigAccessorsTest {

    @Test
    void ymlBool_readsBaselineValue() {
        // pvp.enabled = false in baseline sourbycraft.yml
        assertEquals(false, SourbyCraftConfig.ymlBool("pvp.enabled", true));
    }

    @Test
    void ymlBool_returnsDefaultWhenMissing() {
        assertEquals(true, SourbyCraftConfig.ymlBool("nonexistent.key.path", true));
    }

    @Test
    void ymlBool_returnsDefaultOnTypeMismatch() {
        // pvp.knockback.friction-divisor is a Double in baseline, not a Boolean
        assertEquals(false, SourbyCraftConfig.ymlBool("pvp.knockback.friction-divisor", false));
    }
}
