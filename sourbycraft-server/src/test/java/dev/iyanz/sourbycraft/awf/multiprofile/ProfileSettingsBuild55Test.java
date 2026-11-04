package dev.iyanz.sourbycraft.awf.multiprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileTypeDefinition;
import dev.iyanz.sourbycraft.config.AuroraConfig;
import dev.iyanz.sourbycraft.config.AuroraKeys;
import dev.iyanz.sourbycraft.config.ConfigKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Build 55 MultiProfile keys: {@code switching.cooldown-seconds}, {@code audit.enabled}, {@code audit.retention-days}
 * and {@code io.partitions}: defaults (no cooldown, audit on for 30 days, one partition = the Build 54 queue), parsing,
 * per-key fallback with the key reported, the shorter constructors keeping the defaults, and the AuroraKeys rows matching
 * {@link ProfileSettings#defaultsAsMap()}. Also the seasonal flag's type-store record shape. All RESTART_REQUIRED.
 */
class ProfileSettingsBuild55Test {

    private static final List<String> KEYS = List.of(ProfileSettings.SWITCHING_COOLDOWN_KEY, ProfileSettings.AUDIT_ENABLED_KEY,
        ProfileSettings.AUDIT_RETENTION_KEY, ProfileSettings.IO_PARTITIONS_KEY);

    @Test
    void defaults() {
        assertEquals("aurora.awf.multiprofile.switching.cooldown-seconds", ProfileSettings.SWITCHING_COOLDOWN_KEY);
        assertEquals("aurora.awf.multiprofile.audit.enabled", ProfileSettings.AUDIT_ENABLED_KEY);
        assertEquals("aurora.awf.multiprofile.audit.retention-days", ProfileSettings.AUDIT_RETENTION_KEY);
        assertEquals("aurora.awf.multiprofile.io.partitions", ProfileSettings.IO_PARTITIONS_KEY);
        assertTrue(ProfileSettings.KEYS.containsAll(KEYS));
        assertEquals(0, ProfileSettings.DEFAULT.switching().cooldownSeconds(), "no cooldown by default: the Build 54 behaviour");
        assertEquals(new ProfileSettings.Audit(true, 30), ProfileSettings.DEFAULT.audit());
        assertEquals(new ProfileSettings.Io(1), ProfileSettings.DEFAULT.io());
        // The Build 51-54 constructors keep the Build 55 defaults.
        final ProfileSettings legacy = CoopFixtures.settings(ProfileSettings.DEFAULT_TYPES);
        assertEquals(ProfileSettings.DEFAULT_AUDIT, legacy.audit());
        assertEquals(ProfileSettings.DEFAULT_IO, legacy.io());
        assertEquals(0, new ProfileSettings.Switching(true, 15_000, true).cooldownSeconds());
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Switching(true, 15_000, true, -1));
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Switching(true, 15_000, true, 86_401));
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Audit(true, 0));
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Io(0));
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Io(65));
    }

    @Test
    void parsesAndFallsBackPerKey() {
        final Map<String, Object> values = new HashMap<>();
        values.put(ProfileSettings.ENABLED_KEY, true);
        values.put(ProfileSettings.SWITCHING_COOLDOWN_KEY, 90);
        values.put(ProfileSettings.AUDIT_ENABLED_KEY, false);
        values.put(ProfileSettings.AUDIT_RETENTION_KEY, 7);
        values.put(ProfileSettings.IO_PARTITIONS_KEY, 8);
        final ProfileSettings.Parsed parsed = ProfileSettings.parse(values);
        assertTrue(parsed.invalidKeys().isEmpty(), String.valueOf(parsed.invalidKeys()));
        assertEquals(90, parsed.settings().switching().cooldownSeconds());
        assertEquals(new ProfileSettings.Audit(false, 7), parsed.settings().audit());
        assertEquals(8, parsed.settings().io().partitions());

        final Map<String, Object> bad = new HashMap<>();
        bad.put(ProfileSettings.SWITCHING_COOLDOWN_KEY, "ninety");
        bad.put(ProfileSettings.AUDIT_ENABLED_KEY, "yes please");
        bad.put(ProfileSettings.AUDIT_RETENTION_KEY, 0);
        bad.put(ProfileSettings.IO_PARTITIONS_KEY, 128);
        final ProfileSettings.Parsed fallen = ProfileSettings.parse(bad);
        assertTrue(fallen.invalidKeys().containsAll(KEYS), String.valueOf(fallen.invalidKeys()));
        assertEquals(0, fallen.settings().switching().cooldownSeconds());
        assertEquals(ProfileSettings.DEFAULT_AUDIT, fallen.settings().audit());
        assertEquals(ProfileSettings.DEFAULT_IO, fallen.settings().io());
    }

    @Test
    void auroraKeyRowsMatchTheDefaults() {
        final Map<String, Object> defaults = ProfileSettings.defaultsAsMap();
        for (final String path : KEYS) {
            final ConfigKey key = AuroraKeys.byPath(path);
            assertNotNull(key, path + " has no row in AuroraKeys");
            assertEquals(AuroraConfig.Lifecycle.RESTART_REQUIRED, key.lifecycle(), path);
            assertTrue(defaults.containsKey(path), path);
            assertEquals(defaults.get(path), key.defaultValue(), path);
            assertTrue(AuroraKeys.AWF_MULTIPROFILE_KEYS.contains(key), path + " is not in the MultiProfile table");
        }
        assertEquals(0, defaults.get(ProfileSettings.SWITCHING_COOLDOWN_KEY));
        assertEquals(true, defaults.get(ProfileSettings.AUDIT_ENABLED_KEY));
        assertEquals(30, defaults.get(ProfileSettings.AUDIT_RETENTION_KEY));
        assertEquals(1, defaults.get(ProfileSettings.IO_PARTITIONS_KEY));
        for (final String path : ProfileSettings.KEYS) assertNotNull(AuroraKeys.byPath(path), path + " has no row in AuroraKeys");
        assertEquals(ProfileSettings.DEFAULT_AUDIT, ProfileSettings.parse(defaults).settings().audit());
        assertEquals(ProfileSettings.DEFAULT_IO, ProfileSettings.parse(defaults).settings().io());
    }

    @Test
    void seasonalTypeDefinitionsRoundTripAndKeepTheBuild54RecordShapeWithoutTheFlag() throws Exception {
        final ProfileTypeDefinition plain = ProfileTypeDefinition.builder("plain").displayName("Plain").build();
        final ProfileTypeDefinition seasonal = ProfileTypeDefinition.builder("season").displayName("Season").seasonal(true).deny("trade").build();
        assertFalse(plain.seasonal());
        assertTrue(seasonal.seasonal());
        assertTrue(seasonal.toBuilder().build().seasonal(), "toBuilder keeps the flag");
        assertEquals(seasonal, seasonal.toBuilder().build());
        // The 9-argument constructor is the Build 54 shape: not seasonal.
        assertFalse(new ProfileTypeDefinition(plain.id(), plain.displayName(), plain.description(), plain.coopAllowed(), plain.creatable(),
            plain.maxMembers(), plain.naming(), plain.rules(), plain.defaultRule()).seasonal());

        final byte[] plainRecord = ProfileTypesStore.encode(plain, "TestPlugin");
        final byte[] seasonalRecord = ProfileTypesStore.encode(seasonal, "TestPlugin");
        // Version is the big-endian short after the 4-byte magic: 1 without the flag (readable by Build 54), 2 with it.
        assertEquals(1, ((plainRecord[4] & 0xff) << 8) | (plainRecord[5] & 0xff));
        assertEquals(2, ((seasonalRecord[4] & 0xff) << 8) | (seasonalRecord[5] & 0xff));
        assertEquals(plain, ProfileTypesStore.decode(plainRecord, "plain").definition());
        assertEquals(seasonal, ProfileTypesStore.decode(seasonalRecord, "season").definition());
        assertEquals("TestPlugin", ProfileTypesStore.decode(seasonalRecord, "season").owner());
    }
}
