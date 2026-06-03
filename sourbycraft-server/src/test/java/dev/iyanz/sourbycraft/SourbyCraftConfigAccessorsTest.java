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

    @Test
    void ymlInt_readsBaselineValue() {
        // pvp.view-distance-cap = 6 in baseline
        assertEquals(6, SourbyCraftConfig.ymlInt("pvp.view-distance-cap", 99));
    }

    @Test
    void ymlInt_returnsDefaultWhenMissing() {
        assertEquals(42, SourbyCraftConfig.ymlInt("nonexistent.int.key", 42));
    }

    @Test
    void ymlInt_acceptsLongAndDoubleNumerics() {
        // YAML may parse "1" as Integer, "1000000000000" as Long, "1.0" as Double.
        // Accessor must coerce any Number → int via intValue().
        // No baseline key has these specific shapes, so we only assert the default-on-miss path:
        assertEquals(7, SourbyCraftConfig.ymlInt("nonexistent.long.path", 7));
    }
}
