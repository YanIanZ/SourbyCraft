package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusedWith;
import static dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.bytes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileAdapterDescriptor;
import dev.iyanz.sourbycraft.api.profile.ProfileDataCriticality;
import dev.iyanz.sourbycraft.api.profile.ProfileDataScope;
import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.TestAdapter;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/**
 * Build 55.1, {@code adapters.preserve-unregistered} (split-plan B3): a node that by design runs without the plugin
 * owning a REQUIRED adapter (the lobby without the island plugin's {@code economy} purse) carries that stored data
 * through unchanged instead of refusing every restore with {@code ADAPTER_MISSING}. Written by an agent and not run
 * by it (owner rule); CI runs it on push.
 */
class AdapterPreservationTest {

    private final Plugin island = MemberPlayFixtures.plugin("IslandPlugin");
    private final Player player = MemberPlayFixtures.player(UUID.randomUUID());

    /** A snapshot the island wrote: a REQUIRED purse plus a best-effort entry, with the stored required record. */
    private Map<String, byte[]> islandSnapshot() {
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        registry.register(this.island, TestAdapter.required("economy", this.island).returning(bytes(16, 7)));
        registry.register(this.island, TestAdapter.bestEffort("cosmetics", this.island).returning(bytes(8, 2)));
        final ProfileStateAdapters.Capture capture = registry.capture(this.player);
        final Map<String, byte[]> stored = registry.carryForward(capture.data(), Map.of(), capture);
        assertEquals(Set.of("economy:1", "cosmetics:1", ProfileStateAdapters.REQUIRED_META_KEY), stored.keySet());
        assertEquals(Set.of("economy"), ProfileStateAdapters.requiredIds(stored));
        return stored;
    }

    @Test
    void aListedUnregisteredRequiredIdPassesThroughTheLobbyUnchanged() {
        final Map<String, byte[]> stored = islandSnapshot();
        // The lobby: no island plugin, the purse id listed.
        final ProfileStateAdapters lobby = new ProfileStateAdapters(Set.of(), Set.of("economy"));
        assertTrue(lobby.preservedHere("economy"));
        assertFalse(lobby.required("economy"), "nothing registers it here");
        assertDoesNotThrow(() -> lobby.validateApply(stored));
        assertDoesNotThrow(() -> lobby.apply(this.player, stored));

        // The lobby's own quit capture (it registers nothing): the purse and the record are carried byte-identical.
        final ProfileStateAdapters.Capture capture = lobby.capture(this.player);
        final Map<String, byte[]> carried = lobby.carryForward(capture.data(), stored, capture);
        assertArrayEquals(stored.get("economy:1"), carried.get("economy:1"));
        assertArrayEquals(stored.get("cosmetics:1"), carried.get("cosmetics:1"));
        assertEquals(Set.of("economy"), ProfileStateAdapters.requiredIds(carried), "the stored criticality survives the lobby");

        // Back on the island the purse is applied again from the carried bytes.
        final ProfileStateAdapters islandAgain = new ProfileStateAdapters();
        final TestAdapter purse = TestAdapter.required("economy", this.island);
        islandAgain.register(this.island, purse);
        assertDoesNotThrow(() -> islandAgain.validateApply(carried));
        islandAgain.apply(this.player, carried);
        assertEquals(1, purse.applied.size());
        assertArrayEquals(bytes(16, 7), purse.applied.get(0));
    }

    @Test
    void anUnlistedIdKeepsTheBuild53Refusal() {
        final Map<String, byte[]> stored = islandSnapshot();
        final ProfileStateAdapters lobby = new ProfileStateAdapters(Set.of(), Set.of("something-else"));
        assertFalse(lobby.preservedHere("economy"));
        refusedWith(ProfileErrorCode.ADAPTER_MISSING, () -> lobby.validateApply(stored));
        refusedWith(ProfileErrorCode.ADAPTER_MISSING, () -> new ProfileStateAdapters().validateApply(stored));
    }

    @Test
    void aListedIdThatIsRegisteredHereIsNotRelaxed() {
        final Map<String, byte[]> stored = islandSnapshot();
        // Registered with scope UNSUPPORTED: the plugin says the data is not per profile; the list does not override it.
        final ProfileStateAdapters unsupported = new ProfileStateAdapters(Set.of(), Set.of("economy"));
        unsupported.register(this.island, new TestAdapter("economy", 1, this.island, new ProfileAdapterDescriptor("economy", 1,
            ProfileDataScope.UNSUPPORTED, ProfileDataCriticality.REQUIRED, ProfileAdapterDescriptor.MAX_BYTES_LIMIT)));
        assertFalse(unsupported.preservedHere("economy"));
        refusedWith(ProfileErrorCode.ADAPTER_MISSING, () -> unsupported.validateApply(stored));

        // Registered at an older version than the data: still the plugin's problem, still refused.
        final Map<String, byte[]> newer = Map.of("economy:2", bytes(4, 1), ProfileStateAdapters.REQUIRED_META_KEY,
            stored.get(ProfileStateAdapters.REQUIRED_META_KEY));
        final ProfileStateAdapters older = new ProfileStateAdapters(Set.of(), Set.of("economy"));
        older.register(this.island, TestAdapter.required("economy", this.island));
        refusedWith(ProfileErrorCode.ADAPTER_MISSING, () -> older.validateApply(newer));
    }

    @Test
    void aLateRegistrationDoesNotCaptureOverPassedThroughData() {
        final Map<String, byte[]> stored = islandSnapshot();
        final ProfileStateAdapters lobby = new ProfileStateAdapters(Set.of(), Set.of("economy"));
        lobby.validateApply(stored);
        lobby.apply(this.player, stored);
        assertEquals(Set.of("economy"), lobby.passedThrough(this.player.getUniqueId()), "the apply remembers what it passed through");

        // The purse plugin registers mid-session on this node: the player's purse was never applied here, so a capture
        // does not ask it and the stored bytes are carried forward unchanged.
        final TestAdapter late = TestAdapter.required("economy", this.island).returning(bytes(16, 9));
        lobby.register(this.island, late);
        assertFalse(lobby.preservedHere("economy"), "registered now");
        final ProfileStateAdapters.Capture capture = lobby.capture(this.player);
        assertFalse(capture.data().containsKey("economy:1"), "not captured for this player");
        assertTrue(capture.preserved().contains("economy:1"));
        final Map<String, byte[]> carried = lobby.carryForward(capture.data(), stored, capture);
        assertArrayEquals(bytes(16, 7), carried.get("economy:1"), "the island's bytes, not the late adapter's");
        assertEquals(Set.of("economy"), ProfileStateAdapters.requiredIds(carried));

        // The next apply covers the id for real; from then on the adapter is captured as usual.
        lobby.apply(this.player, carried);
        assertEquals(1, late.applied.size());
        assertTrue(lobby.passedThrough(this.player.getUniqueId()).isEmpty());
        assertArrayEquals(bytes(16, 9), lobby.capture(this.player).data().get("economy:1"));

        // Another player who never had the data passed through is captured at once; quit forgets the memory.
        final Player other = MemberPlayFixtures.player(UUID.randomUUID());
        assertArrayEquals(bytes(16, 9), lobby.capture(other).data().get("economy:1"));
        lobby.apply(this.player, stored);
        lobby.forgetPassedThrough(this.player.getUniqueId());
        assertTrue(lobby.passedThrough(this.player.getUniqueId()).isEmpty());
    }

    @Test
    void theListOnlyMattersForRequiredData() {
        // Best-effort or unknown data was never refused; listing it changes nothing and does not make it required.
        final ProfileStateAdapters lobby = new ProfileStateAdapters(Set.of(), Set.of("cosmetics"));
        final Map<String, byte[]> soft = Map.of("cosmetics:1", bytes(8, 2));
        assertDoesNotThrow(() -> lobby.validateApply(soft));
        assertFalse(lobby.required("cosmetics"));
        assertEquals(Set.of("cosmetics:1"), lobby.carryForward(Map.of(), soft).keySet());
    }
}
