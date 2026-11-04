package dev.iyanz.sourbycraft.awf.multiprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.config.AuroraConfig;
import dev.iyanz.sourbycraft.config.AuroraKeys;
import dev.iyanz.sourbycraft.config.ConfigKey;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The Build 55.1 key {@code coordination.handoff-wait-ms}: default 0 (the single claim attempt of Builds 51–55), the
 * Build 51 {@code Coordination} constructor keeping it, parsing, per-key fallback with the key reported, the range
 * 0..{@value ProfileSettings#HANDOFF_WAIT_MAX_MILLIS}, and the AuroraKeys row matching {@link ProfileSettings#defaultsAsMap()}.
 * RESTART_REQUIRED. Written by an agent and not run by it (owner rule).
 */
class ProfileSettingsBuild551Test {

    @Test
    void defaults() {
        assertEquals("aurora.awf.multiprofile.coordination.handoff-wait-ms", ProfileSettings.COORDINATION_HANDOFF_WAIT_KEY);
        assertTrue(ProfileSettings.KEYS.contains(ProfileSettings.COORDINATION_HANDOFF_WAIT_KEY));
        assertEquals(0, ProfileSettings.DEFAULT.coordination().handoffWaitMillis(), "one attempt by default: the Build 55 behaviour");
        assertEquals(15_000, ProfileSettings.HANDOFF_WAIT_MAX_MILLIS);
        // The Build 51 constructor (every existing fixture and caller) keeps the wait off.
        final ProfileSettings.Coordination legacy = new ProfileSettings.Coordination(ProfileSettings.Provider.LOCAL, true, true);
        assertEquals(0, legacy.handoffWaitMillis());
        assertEquals(new ProfileSettings.Coordination(ProfileSettings.Provider.LOCAL, true, true, 0), legacy);
        assertEquals(0, CoopFixtures.settings(ProfileSettings.DEFAULT_TYPES).coordination().handoffWaitMillis());
        assertEquals(5_000, new ProfileSettings.Coordination(ProfileSettings.Provider.REDIS, true, true, 5_000).handoffWaitMillis());
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Coordination(ProfileSettings.Provider.LOCAL, true, true, -1));
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Coordination(ProfileSettings.Provider.LOCAL, true, true, 15_001));
    }

    @Test
    void parsesAndFallsBackPerKey() {
        final Map<String, Object> values = new HashMap<>();
        values.put(ProfileSettings.ENABLED_KEY, true);
        values.put(ProfileSettings.COORDINATION_HANDOFF_WAIT_KEY, 5000);
        final ProfileSettings.Parsed parsed = ProfileSettings.parse(values);
        assertTrue(parsed.invalidKeys().isEmpty(), String.valueOf(parsed.invalidKeys()));
        assertEquals(5_000, parsed.settings().coordination().handoffWaitMillis());
        assertEquals(ProfileSettings.DEFAULT.coordination().provider(), parsed.settings().coordination().provider());
        assertTrue(parsed.settings().coordination().fencingEnabled(), "the other coordination keys keep their defaults");

        for (final Object bad : new Object[] {-1, 15_001, 2.5, "soon", true}) {
            final Map<String, Object> invalid = new HashMap<>();
            invalid.put(ProfileSettings.COORDINATION_HANDOFF_WAIT_KEY, bad);
            final ProfileSettings.Parsed fallen = ProfileSettings.parse(invalid);
            assertTrue(fallen.invalidKeys().contains(ProfileSettings.COORDINATION_HANDOFF_WAIT_KEY), bad + ": " + fallen.invalidKeys());
            assertEquals(0, fallen.settings().coordination().handoffWaitMillis(), String.valueOf(bad));
        }
        final Map<String, Object> ceiling = new HashMap<>();
        ceiling.put(ProfileSettings.COORDINATION_HANDOFF_WAIT_KEY, 15_000);
        assertEquals(15_000, ProfileSettings.parse(ceiling).settings().coordination().handoffWaitMillis());
    }

    @Test
    void auroraKeyRowMatchesTheDefault() {
        final Map<String, Object> defaults = ProfileSettings.defaultsAsMap();
        final String path = ProfileSettings.COORDINATION_HANDOFF_WAIT_KEY;
        final ConfigKey key = AuroraKeys.byPath(path);
        assertNotNull(key, path + " has no row in AuroraKeys");
        assertEquals(AuroraConfig.Lifecycle.RESTART_REQUIRED, key.lifecycle(), path);
        assertEquals(0, defaults.get(path));
        assertEquals(defaults.get(path), key.defaultValue(), path);
        assertTrue(AuroraKeys.AWF_MULTIPROFILE_KEYS.contains(key), path + " is not in the MultiProfile table");
        assertEquals(0, ProfileSettings.parse(defaults).settings().coordination().handoffWaitMillis());
    }

    // ------------------------------------------------------------------ adapters.preserve-unregistered (B3)

    @Test
    void preserveUnregisteredDefaultsAndShape() {
        assertEquals("aurora.awf.multiprofile.adapters.preserve-unregistered", ProfileSettings.ADAPTERS_PRESERVE_KEY);
        assertTrue(ProfileSettings.KEYS.contains(ProfileSettings.ADAPTERS_PRESERVE_KEY));
        assertTrue(ProfileSettings.DEFAULT.adapters().preserveUnregistered().isEmpty(), "nothing preserved by default");
        assertEquals(ProfileSettings.DEFAULT_ADAPTERS, new ProfileSettings.Adapters(java.util.Set.of()), "the Build 53 constructor");
        final ProfileSettings.Adapters lobby = new ProfileSettings.Adapters(java.util.Set.of(), java.util.Set.of("economy"));
        assertTrue(lobby.preserves("economy"));
        assertTrue(lobby.required().isEmpty(), "preserving never forces REQUIRED");
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Adapters(java.util.Set.of(), java.util.Set.of("Bad Id")));
        final java.util.Set<String> tooMany = new java.util.HashSet<>();
        for (int i = 0; i <= ProfileSettings.Adapters.MAX_REQUIRED; i++) tooMany.add("adapter" + i);
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Adapters(java.util.Set.of(), tooMany));
    }

    @Test
    void preserveUnregisteredParsesLikeRequired() {
        final Map<String, Object> values = new HashMap<>();
        values.put(ProfileSettings.ADAPTERS_PRESERVE_KEY, java.util.List.of("economy", " cosmetics "));
        values.put(ProfileSettings.ADAPTERS_REQUIRED_KEY, "economy");
        final ProfileSettings.Parsed parsed = ProfileSettings.parse(values);
        assertTrue(parsed.invalidKeys().isEmpty(), String.valueOf(parsed.invalidKeys()));
        assertEquals(java.util.Set.of("cosmetics", "economy"), parsed.settings().adapters().preserveUnregistered());
        assertEquals(java.util.Set.of("economy"), parsed.settings().adapters().required(), "the two lists are independent");

        final Map<String, Object> text = new HashMap<>();
        text.put(ProfileSettings.ADAPTERS_PRESERVE_KEY, "economy,bank");
        assertEquals(java.util.Set.of("bank", "economy"), ProfileSettings.parse(text).settings().adapters().preserveUnregistered());

        final Map<String, Object> bad = new HashMap<>();
        bad.put(ProfileSettings.ADAPTERS_PRESERVE_KEY, java.util.List.of("economy", "Not Valid"));
        final ProfileSettings.Parsed fallen = ProfileSettings.parse(bad);
        assertTrue(fallen.invalidKeys().contains(ProfileSettings.ADAPTERS_PRESERVE_KEY));
        assertEquals(java.util.Set.of("economy"), fallen.settings().adapters().preserveUnregistered(), "well-formed entries are kept");
        final Map<String, Object> wrongType = new HashMap<>();
        wrongType.put(ProfileSettings.ADAPTERS_PRESERVE_KEY, 5);
        assertTrue(ProfileSettings.parse(wrongType).invalidKeys().contains(ProfileSettings.ADAPTERS_PRESERVE_KEY));
        assertTrue(ProfileSettings.parse(wrongType).settings().adapters().preserveUnregistered().isEmpty());
    }

    @Test
    void preserveUnregisteredAuroraKeyRowMatchesTheDefault() {
        final Map<String, Object> defaults = ProfileSettings.defaultsAsMap();
        final String path = ProfileSettings.ADAPTERS_PRESERVE_KEY;
        final ConfigKey key = AuroraKeys.byPath(path);
        assertNotNull(key, path + " has no row in AuroraKeys");
        assertEquals(AuroraConfig.Lifecycle.RESTART_REQUIRED, key.lifecycle(), path);
        assertEquals(java.util.List.of(), defaults.get(path));
        assertEquals(defaults.get(path), key.defaultValue(), path);
        assertTrue(AuroraKeys.AWF_MULTIPROFILE_KEYS.contains(key), path + " is not in the MultiProfile table");
        for (final String each : ProfileSettings.KEYS) assertNotNull(AuroraKeys.byPath(each), each + " has no row in AuroraKeys");
    }
}
