package dev.iyanz.sourbycraft.awf.multiprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * The Build 56 keys {@code coop.cross-node} and {@code coop.world-host}: defaults (cross-node off = OD-1, every node a
 * world host), the Build 52 {@code Coop} constructor keeping them, parsing, per-key fallback with the key reported, the
 * AuroraKeys rows matching {@link ProfileSettings#defaultsAsMap()}, the derived {@code crossNodeCoop()} /
 * {@code satellite()} accessors, and {@link ProfileRuntime#validateCrossNode}: cross-node needs the Redis repository in
 * distributed mode (the FILE repository is refused), a satellite needs switching off, and world-host false without
 * cross-node is refused. RESTART_REQUIRED. Written by an agent and not run by it (owner rule).
 */
class ProfileSettingsBuild56Test {

    private static ProfileSettings with(final ProfileSettings base, final ProfileSettings.Mode mode, final ProfileSettings.Backend backend,
                                        final boolean switching, final boolean crossNode, final boolean worldHost) {
        final ProfileSettings.Coop coop = base.coop();
        return new ProfileSettings(base.enabled(), mode, base.defaultSlots(), base.maxSlots(),
            new ProfileSettings.Switching(switching, base.switching().timeoutMillis(), base.switching().requireDurableSave()),
            base.isolation(), base.worlds(), new ProfileSettings.Coordination(ProfileSettings.Provider.REDIS, true, true),
            base.directory(), new ProfileSettings.Repository(backend, true, false), base.types(), base.names(), base.migration(),
            new ProfileSettings.Coop(coop.enabled(), coop.maxMembers(), coop.inviteExpiryMinutes(), coop.kickVoteExpiryMinutes(),
                coop.kickVoteInactiveDays(), coop.allowedTypes(), crossNode, worldHost),
            base.shutdown(), base.login(), base.adapters());
    }

    @Test
    void defaults() {
        assertEquals("aurora.awf.multiprofile.coop.cross-node", ProfileSettings.COOP_CROSS_NODE_KEY);
        assertEquals("aurora.awf.multiprofile.coop.world-host", ProfileSettings.COOP_WORLD_HOST_KEY);
        assertTrue(ProfileSettings.KEYS.contains(ProfileSettings.COOP_CROSS_NODE_KEY));
        assertTrue(ProfileSettings.KEYS.contains(ProfileSettings.COOP_WORLD_HOST_KEY));
        assertFalse(ProfileSettings.DEFAULT.coop().crossNode(), "OD-1 by default: one node per co-op");
        assertTrue(ProfileSettings.DEFAULT.coop().worldHost(), "every node is a world host by default");
        assertFalse(ProfileSettings.DEFAULT.coop().satellite());
        assertFalse(ProfileSettings.DEFAULT.crossNodeCoop());
        assertFalse(ProfileSettings.DEFAULT.satellite());
        // The Build 52 constructor (every existing fixture and caller) keeps the Build 55.1 shape.
        final ProfileSettings.Coop legacy = new ProfileSettings.Coop(true, 5, 5, 10, 35, ProfileSettings.DEFAULT_COOP.allowedTypes());
        assertFalse(legacy.crossNode());
        assertTrue(legacy.worldHost());
        assertEquals(new ProfileSettings.Coop(true, 5, 5, 10, 35, ProfileSettings.DEFAULT_COOP.allowedTypes(), false, true), legacy);
        assertFalse(CoopMembershipFixtures.coopSettings().crossNodeCoop(), "the co-op fixtures keep OD-1");
        assertFalse(MemberPlayFixtures.playSettings().satellite());
    }

    @Test
    void derivedAccessorsNeedTheFeatureOn() {
        final ProfileSettings on = with(CoopMembershipFixtures.coopSettings(), ProfileSettings.Mode.DISTRIBUTED,
            ProfileSettings.Backend.REDIS, false, true, false);
        assertTrue(on.crossNodeCoop());
        assertTrue(on.satellite());
        assertTrue(on.coop().satellite());
        final ProfileSettings host = with(CoopMembershipFixtures.coopSettings(), ProfileSettings.Mode.DISTRIBUTED,
            ProfileSettings.Backend.REDIS, true, true, true);
        assertTrue(host.crossNodeCoop());
        assertFalse(host.satellite(), "a world host is not a satellite");
        // The master switch off: no cross-node co-op and no satellite, whatever the co-op keys say.
        final ProfileSettings off = new ProfileSettings(false, on.mode(), on.defaultSlots(), on.maxSlots(), on.switching(),
            on.isolation(), on.worlds(), on.coordination(), on.directory(), on.repository(), on.types(), on.names(), on.migration(),
            on.coop(), on.shutdown(), on.login(), on.adapters());
        assertFalse(off.crossNodeCoop());
        assertFalse(off.satellite());
        assertTrue(off.coop().satellite(), "the record itself still reports what was configured");
    }

    @Test
    void parsesAndFallsBackPerKey() {
        final Map<String, Object> values = new HashMap<>();
        values.put(ProfileSettings.ENABLED_KEY, true);
        values.put(ProfileSettings.COOP_CROSS_NODE_KEY, true);
        values.put(ProfileSettings.COOP_WORLD_HOST_KEY, false);
        final ProfileSettings.Parsed parsed = ProfileSettings.parse(values);
        assertTrue(parsed.invalidKeys().isEmpty(), String.valueOf(parsed.invalidKeys()));
        assertTrue(parsed.settings().coop().crossNode());
        assertFalse(parsed.settings().coop().worldHost());
        assertTrue(parsed.settings().satellite());
        assertEquals(ProfileSettings.DEFAULT_COOP.maxMembers(), parsed.settings().coop().maxMembers(), "the other co-op keys keep their defaults");
        assertFalse(parsed.settings().coop().enabled(), "cross-node does not enable co-op operations by itself");

        for (final Object bad : new Object[] {"yes", 1, 2.5, "true"}) {
            final Map<String, Object> invalid = new HashMap<>();
            invalid.put(ProfileSettings.COOP_CROSS_NODE_KEY, bad);
            invalid.put(ProfileSettings.COOP_WORLD_HOST_KEY, bad);
            final ProfileSettings.Parsed fallen = ProfileSettings.parse(invalid);
            assertTrue(fallen.invalidKeys().contains(ProfileSettings.COOP_CROSS_NODE_KEY), bad + ": " + fallen.invalidKeys());
            assertTrue(fallen.invalidKeys().contains(ProfileSettings.COOP_WORLD_HOST_KEY), bad + ": " + fallen.invalidKeys());
            assertFalse(fallen.settings().coop().crossNode(), String.valueOf(bad));
            assertTrue(fallen.settings().coop().worldHost(), String.valueOf(bad));
        }
    }

    @Test
    void auroraKeyRowsMatchTheDefaults() {
        final Map<String, Object> defaults = ProfileSettings.defaultsAsMap();
        for (final String path : new String[] {ProfileSettings.COOP_CROSS_NODE_KEY, ProfileSettings.COOP_WORLD_HOST_KEY}) {
            final ConfigKey key = AuroraKeys.byPath(path);
            assertNotNull(key, path + " has no row in AuroraKeys");
            assertEquals(AuroraConfig.Lifecycle.RESTART_REQUIRED, key.lifecycle(), path);
            assertEquals(defaults.get(path), key.defaultValue(), path);
            assertTrue(AuroraKeys.AWF_MULTIPROFILE_KEYS.contains(key), path + " is not in the MultiProfile table");
        }
        assertEquals(false, defaults.get(ProfileSettings.COOP_CROSS_NODE_KEY));
        assertEquals(true, defaults.get(ProfileSettings.COOP_WORLD_HOST_KEY));
        final ProfileSettings fromDefaults = ProfileSettings.parse(defaults).settings();
        assertFalse(fromDefaults.coop().crossNode());
        assertTrue(fromDefaults.coop().worldHost());
    }

    // ------------------------------------------------------------------ ProfileRuntime.validateCrossNode

    @Test
    void crossNodeOffIsNeverRefused() {
        final ProfileSettings base = CoopMembershipFixtures.coopSettings();
        ProfileRuntime.validateCrossNode(with(base, ProfileSettings.Mode.STANDALONE, ProfileSettings.Backend.FILE, true, false, true));
        ProfileRuntime.validateCrossNode(with(base, ProfileSettings.Mode.DISTRIBUTED, ProfileSettings.Backend.REDIS, true, false, true));
        ProfileRuntime.validateCrossNode(with(base, ProfileSettings.Mode.STANDALONE, ProfileSettings.Backend.REDIS, false, false, true));
        // The whole feature off: nothing is validated, whatever the co-op keys say.
        final ProfileSettings on = with(base, ProfileSettings.Mode.STANDALONE, ProfileSettings.Backend.FILE, true, true, false);
        ProfileRuntime.validateCrossNode(new ProfileSettings(false, on.mode(), on.defaultSlots(), on.maxSlots(), on.switching(),
            on.isolation(), on.worlds(), on.coordination(), on.directory(), on.repository(), on.types(), on.names(), on.migration(),
            on.coop(), on.shutdown(), on.login(), on.adapters()));
    }

    @Test
    void fileRepositoryRefusesCrossNode() {
        final ProfileSettings base = CoopMembershipFixtures.coopSettings();
        final IllegalStateException file = assertThrows(IllegalStateException.class, () -> ProfileRuntime.validateCrossNode(
            with(base, ProfileSettings.Mode.DISTRIBUTED, ProfileSettings.Backend.FILE, true, true, true)));
        assertTrue(file.getMessage().contains(ProfileSettings.COOP_CROSS_NODE_KEY), file.getMessage());
        assertTrue(file.getMessage().contains("repository: redis"), file.getMessage());
        final IllegalStateException standalone = assertThrows(IllegalStateException.class, () -> ProfileRuntime.validateCrossNode(
            with(base, ProfileSettings.Mode.STANDALONE, ProfileSettings.Backend.REDIS, true, true, true)));
        assertTrue(standalone.getMessage().contains("mode: distributed"), standalone.getMessage());
        final IllegalStateException both = assertThrows(IllegalStateException.class, () -> ProfileRuntime.validateCrossNode(
            with(base, ProfileSettings.Mode.STANDALONE, ProfileSettings.Backend.FILE, false, true, false)));
        assertTrue(both.getMessage().contains(ProfileSettings.COOP_CROSS_NODE_KEY), both.getMessage());
        // The same refusal reaches the full validation, before the fabric and provider checks.
        assertThrows(IllegalStateException.class, () -> ProfileRuntime.validate(
            with(base, ProfileSettings.Mode.STANDALONE, ProfileSettings.Backend.FILE, true, true, true), false));
    }

    @Test
    void satelliteNeedsSwitchingOffAndCrossNodeOn() {
        final ProfileSettings base = CoopMembershipFixtures.coopSettings();
        // The WuretaMC shape: the island (world host, switching on) and the spawn node (satellite, switching off).
        ProfileRuntime.validateCrossNode(with(base, ProfileSettings.Mode.DISTRIBUTED, ProfileSettings.Backend.REDIS, true, true, true));
        ProfileRuntime.validateCrossNode(with(base, ProfileSettings.Mode.DISTRIBUTED, ProfileSettings.Backend.REDIS, false, true, false));
        final IllegalStateException switching = assertThrows(IllegalStateException.class, () -> ProfileRuntime.validateCrossNode(
            with(base, ProfileSettings.Mode.DISTRIBUTED, ProfileSettings.Backend.REDIS, true, true, false)));
        assertTrue(switching.getMessage().contains(ProfileSettings.SWITCHING_ENABLED_KEY), switching.getMessage());
        assertTrue(switching.getMessage().contains(ProfileSettings.COOP_WORLD_HOST_KEY), switching.getMessage());
        final IllegalStateException orphan = assertThrows(IllegalStateException.class, () -> ProfileRuntime.validateCrossNode(
            with(base, ProfileSettings.Mode.DISTRIBUTED, ProfileSettings.Backend.REDIS, false, false, false)));
        assertTrue(orphan.getMessage().contains(ProfileSettings.COOP_CROSS_NODE_KEY), orphan.getMessage());
        assertTrue(orphan.getMessage().contains("OD-1"), orphan.getMessage());
    }
}
