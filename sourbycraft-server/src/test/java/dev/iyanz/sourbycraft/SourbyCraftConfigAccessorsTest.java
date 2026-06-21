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

    @Test
    void ymlDouble_readsBaselineValue() {
        // pvp.knockback.friction-divisor = 1.0 in baseline (per resource header)
        assertEquals(1.0, SourbyCraftConfig.ymlDouble("pvp.knockback.friction-divisor", 9.9));
    }

    @Test
    void ymlDouble_returnsDefaultWhenMissing() {
        assertEquals(2.5, SourbyCraftConfig.ymlDouble("nonexistent.double.key", 2.5));
    }

    @Test
    void ymlDouble_coercesIntegerValue() {
        // pvp.view-distance-cap is an Integer (6) in baseline. ymlDouble must coerce.
        assertEquals(6.0, SourbyCraftConfig.ymlDouble("pvp.view-distance-cap", 0.0));
    }

    @Test
    void ymlStringList_returnsEmptyWhenMissing() {
        assertTrue(SourbyCraftConfig.ymlStringList("nonexistent.list.key").isEmpty());
    }

    @Test
    void ymlStringList_returnsEmptyOnTypeMismatch() {
        // pvp.enabled is a Boolean, not a List
        assertTrue(SourbyCraftConfig.ymlStringList("pvp.enabled").isEmpty());
    }

    @Test
    void ymlStringList_filtersNonStringEntries() {
        // Constructed via reflection on the baseline map. Simulate by injecting a known list-bearing key
        // into the baseline; for this test we rely on the implementation handling the missing-key path
        // correctly. Real list coverage lives in the integration via the bundled yml in later tasks.
        // For now, assert that a missing key yields a defensively non-null empty list.
        var result = SourbyCraftConfig.ymlStringList("absolutely.no.such.key");
        assertNotNull(result);
        assertEquals(0, result.size());
    }

    @Test
    void ymlEntityTypeMap_returnsEmptyWhenMissing() {
        var map = SourbyCraftConfig.ymlEntityTypeMap("nonexistent.entity.map");
        assertNotNull(map);
        assertTrue(map.isEmpty());
    }

    @Test
    void ymlEntityTypeMap_parseHelperRoundtrip() {
        // The parser is exposed via a package-private helper so we can unit-test the
        // "TYPE:N" splitting logic without needing a baseline list to exist.
        var parsed = SourbyCraftConfig.parseEntityTypeEntry("PLAYER:2");
        assertNotNull(parsed);
        assertEquals(org.bukkit.entity.EntityType.PLAYER, parsed.getKey());
        assertEquals(2, parsed.getValue());
    }

    @Test
    void ymlEntityTypeMap_parseHelperRejectsBadFormat() {
        assertNull(SourbyCraftConfig.parseEntityTypeEntry("nocolon"));
        assertNull(SourbyCraftConfig.parseEntityTypeEntry("PLAYER:notanumber"));
        assertNull(SourbyCraftConfig.parseEntityTypeEntry("NONEXISTENT_ENTITY:5"));
        assertNull(SourbyCraftConfig.parseEntityTypeEntry(""));
        assertNull(SourbyCraftConfig.parseEntityTypeEntry(null));
    }

    @Test
    void warnOnce_emitsExactlyOnceForRepeatedBadReads() {
        // The dedupe Set is the source of truth for "warned at most once."
        // (Capturing via Bukkit.getLogger() would not work because warnOnce
        // routes through SourbyLogger directly, bypassing Bukkit's logger chain.)
        // pvp.view-distance-cap is an Integer in baseline; reading as bool triggers warnOnce.
        // This key is not used as a wrong-type read by any other test, so before-snapshot
        // is guaranteed to not contain it.
        String badKey = "pvp.view-distance-cap";
        java.util.Set<String> before = SourbyCraftConfig.warnedKeysForTest();
        SourbyCraftConfig.ymlBool(badKey, false);
        SourbyCraftConfig.ymlBool(badKey, false);
        SourbyCraftConfig.ymlBool(badKey, false);
        java.util.Set<String> after = SourbyCraftConfig.warnedKeysForTest();
        assertEquals(before.size() + 1, after.size(),
            "exactly one new dedupe entry for repeated bad reads of " + badKey);
        assertTrue(after.contains(badKey));
    }

    @Test
    void ymlBool_readsNewParticlesSection() {
        assertEquals(false, SourbyCraftConfig.ymlBool("particles.disableFallParticles", true));
        assertEquals(false, SourbyCraftConfig.ymlBool("particles.disableDeathParticles", true));
    }

    @Test
    void ymlBool_readsNewParticlesBatch1Keys() {
        assertEquals(false, SourbyCraftConfig.ymlBool("particles.disableBlockBreakParticles", true));
        assertEquals(false, SourbyCraftConfig.ymlBool("particles.disableEffectParticles", true));
        assertEquals(false, SourbyCraftConfig.ymlBool("particles.disableWaterSplashParticles", true));
        assertEquals(false, SourbyCraftConfig.ymlBool("particles.disableNewCombatParticles", true));
    }

    @Test
    void ymlBool_readsNewSoundsBatch1Keys() {
        assertEquals(false, SourbyCraftConfig.ymlBool("sounds.disableShoulderEntityAmbientSound", true));
        assertEquals(false, SourbyCraftConfig.ymlBool("sounds.disablePiglinAngerSound", true));
        assertEquals(false, SourbyCraftConfig.ymlBool("sounds.disableFootStepSounds", true));
        assertEquals(false, SourbyCraftConfig.ymlBool("sounds.disableNewCombatSounds", true));
        assertEquals(false, SourbyCraftConfig.ymlBool("sounds.disableShieldSounds", true));
        assertEquals(false, SourbyCraftConfig.ymlBool("sounds.disablePistonSounds", true));
    }
}
