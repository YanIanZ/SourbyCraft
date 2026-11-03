package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusal;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusedWith;
import static dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.bytes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileAdapterDescriptor;
import dev.iyanz.sourbycraft.api.profile.ProfileDataCriticality;
import dev.iyanz.sourbycraft.api.profile.ProfileDataScope;
import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.TestAdapter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/**
 * The RFC E03 adapter contract of {@link ProfileStateAdapters} without a server (Build 53): descriptors read and checked
 * at registration, REQUIRED versus BEST_EFFORT capture and apply, {@code adapters.required} forcing, opaque bytes kept
 * (unknown adapters, failed best-effort captures, UNSUPPORTED scope), cleared bytes dropped, the aggregate budget and
 * the 31-adapter cap refusing instead of dropping, the stored criticality record, {@code migrate}, and the two-phase
 * shutdown merge. The player handed to the adapters is a reflective
 * stand-in; the test adapters ignore it.
 */
class ProfileStateAdaptersTest {

    private final Plugin plugin = MemberPlayFixtures.plugin("AdapterPlugin");
    private final Player player = MemberPlayFixtures.player(UUID.randomUUID());

    private static ProfileAdapterDescriptor descriptor(final String id, final int version, final ProfileDataScope scope,
                                                       final ProfileDataCriticality criticality, final long maxBytes) {
        return new ProfileAdapterDescriptor(id, version, scope, criticality, maxBytes);
    }

    // ------------------------------------------------------------------ descriptors

    @Test
    void descriptorDefaultsKeepTheBuild52Behaviour() {
        final TestAdapter plain = TestAdapter.bestEffort("plain", this.plugin);
        final ProfileAdapterDescriptor declared = plain.descriptor();
        assertEquals("plain", declared.id());
        assertEquals(1, declared.version());
        assertEquals(ProfileDataScope.PROFILE_SCOPED, declared.scope());
        assertEquals(ProfileDataCriticality.BEST_EFFORT, declared.criticality());
        assertEquals(ProfileAdapterDescriptor.MAX_BYTES_LIMIT, declared.maxBytes());
        assertEquals(2L * 1024 * 1024, ProfileAdapterDescriptor.MAX_BYTES_LIMIT);
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        registry.register(this.plugin, plain);
        assertEquals(declared, registry.descriptor("plain"));
        assertFalse(registry.required("plain"));
        assertNull(registry.descriptor("absent"));
    }

    @Test
    void descriptorItselfIsValidated() {
        assertThrows(IllegalArgumentException.class, () -> descriptor("Bad Id", 1, ProfileDataScope.PROFILE_SCOPED,
            ProfileDataCriticality.REQUIRED, 1));
        assertThrows(IllegalArgumentException.class, () -> descriptor("ok", 0, ProfileDataScope.PROFILE_SCOPED,
            ProfileDataCriticality.REQUIRED, 1));
        assertThrows(IllegalArgumentException.class, () -> descriptor("ok", 1, ProfileDataScope.PROFILE_SCOPED,
            ProfileDataCriticality.REQUIRED, 0));
        assertThrows(IllegalArgumentException.class, () -> descriptor("ok", 1, ProfileDataScope.PROFILE_SCOPED,
            ProfileDataCriticality.REQUIRED, ProfileAdapterDescriptor.MAX_BYTES_LIMIT + 1));
        assertThrows(NullPointerException.class, () -> descriptor("ok", 1, null, ProfileDataCriticality.REQUIRED, 1));
        assertThrows(NullPointerException.class, () -> descriptor("ok", 1, ProfileDataScope.PROFILE_SCOPED, null, 1));
    }

    /** A descriptor whose id or version differs from the adapter's, one that throws, and a null one are refused at register. */
    @Test
    void descriptorMismatchRefusedAtRegister() {
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        assertThrows(IllegalArgumentException.class, () -> registry.register(this.plugin, new TestAdapter("a", 1, this.plugin,
            descriptor("b", 1, ProfileDataScope.PROFILE_SCOPED, ProfileDataCriticality.REQUIRED, 8))));
        assertThrows(IllegalArgumentException.class, () -> registry.register(this.plugin, new TestAdapter("a", 1, this.plugin,
            descriptor("a", 2, ProfileDataScope.PROFILE_SCOPED, ProfileDataCriticality.REQUIRED, 8))));
        assertThrows(IllegalArgumentException.class, () -> registry.register(this.plugin, new TestAdapter("a", 1, this.plugin, null) {
            @Override
            public ProfileAdapterDescriptor descriptor() {
                throw new IllegalStateException("scripted descriptor failure");
            }
        }));
        assertThrows(IllegalArgumentException.class, () -> registry.register(this.plugin, new TestAdapter("a", 1, this.plugin, null) {
            @Override
            public ProfileAdapterDescriptor descriptor() {
                return null;
            }
        }));
        assertEquals(0, registry.size(), "a refused adapter was registered");
        registry.register(this.plugin, new TestAdapter("a", 1, this.plugin,
            descriptor("a", 1, ProfileDataScope.PROFILE_SCOPED, ProfileDataCriticality.REQUIRED, 8)));
        assertEquals(1, registry.size());
        assertTrue(registry.required("a"));
    }

    // ------------------------------------------------------------------ REQUIRED capture

    /** A REQUIRED adapter that throws or exceeds its bound fails the whole capture: nothing partial is returned. */
    @Test
    void requiredCaptureFailureFailsTheWholeCapture() {
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        registry.register(this.plugin, TestAdapter.bestEffort("soft", this.plugin).returning(bytes(4, 1)));
        final TestAdapter required = TestAdapter.required("hard", this.plugin).throwing();
        registry.register(this.plugin, required);
        final ProfileOperationException thrown = refusal(() -> registry.capture(this.player));
        assertEquals(ProfileErrorCode.ADAPTER_REQUIRED_FAILED, thrown.code(), thrown.getMessage());

        final ProfileStateAdapters bounded = new ProfileStateAdapters();
        bounded.register(this.plugin, new TestAdapter("hard", 1, this.plugin,
            descriptor("hard", 1, ProfileDataScope.PROFILE_SCOPED, ProfileDataCriticality.REQUIRED, 4)).returning(bytes(5, 1)));
        refusedWith(ProfileErrorCode.ADAPTER_REQUIRED_FAILED, () -> bounded.capture(this.player));

        // Within its bound the required adapter is captured like any other.
        required.returning(bytes(4, 2));
        final ProfileStateAdapters.Capture capture = registry.capture(this.player);
        assertArrayEquals(bytes(4, 2), capture.data().get("hard:1"));
        assertArrayEquals(bytes(4, 1), capture.data().get("soft:1"));
        assertTrue(capture.preserved().isEmpty());
        assertTrue(capture.cleared().isEmpty());
    }

    /** {@code adapters.required} raises a BEST_EFFORT adapter to REQUIRED (only that way); ids it lists count as required unregistered too. */
    @Test
    void adaptersRequiredForcesStrictness() {
        final ProfileStateAdapters registry = new ProfileStateAdapters(Set.of("soft", "ghost"));
        final TestAdapter soft = TestAdapter.bestEffort("soft", this.plugin).throwing();
        registry.register(this.plugin, soft);
        registry.register(this.plugin, TestAdapter.bestEffort("other", this.plugin));
        assertTrue(registry.required("soft"));
        assertTrue(registry.required("ghost"), "a listed id without a registration is not required");
        assertFalse(registry.required("other"));
        assertEquals(ProfileDataCriticality.BEST_EFFORT, registry.descriptor("soft").criticality(), "the descriptor is reported as declared");
        refusedWith(ProfileErrorCode.ADAPTER_REQUIRED_FAILED, () -> registry.capture(this.player));
        refusedWith(ProfileErrorCode.ADAPTER_MISSING, () -> registry.validateApply(Map.of("ghost:1", bytes(2, 1))));
        assertDoesNotThrow(() -> registry.validateApply(Map.of("other:1", bytes(2, 1), "unknown:7", bytes(2, 2))));
        // An adapter declared REQUIRED stays REQUIRED without being listed.
        final ProfileStateAdapters unlisted = new ProfileStateAdapters(Set.of());
        unlisted.register(this.plugin, TestAdapter.required("hard", this.plugin));
        assertTrue(unlisted.required("hard"));
    }

    // ------------------------------------------------------------------ BEST_EFFORT capture and the merge

    /** A BEST_EFFORT adapter that throws or exceeds its bound is skipped and named preserved; the merge keeps its stored bytes. */
    @Test
    void bestEffortFailurePreservesPriorBytes() {
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        final TestAdapter soft = TestAdapter.bestEffort("soft", this.plugin).throwing();
        registry.register(this.plugin, soft);
        registry.register(this.plugin, new TestAdapter("small", 1, this.plugin,
            descriptor("small", 1, ProfileDataScope.PROFILE_SCOPED, ProfileDataCriticality.BEST_EFFORT, 4)).returning(bytes(5, 9)));
        final ProfileStateAdapters.Capture capture = assertDoesNotThrow(() -> registry.capture(this.player));
        assertTrue(capture.data().isEmpty());
        assertEquals(Set.of("soft:1", "small:1"), capture.preserved());
        assertTrue(capture.cleared().isEmpty());

        final Map<String, byte[]> previous = new LinkedHashMap<>();
        previous.put("soft:1", bytes(3, 1));
        previous.put("small:1", bytes(3, 2));
        final Map<String, byte[]> merged = registry.carryForward(capture.data(), previous, capture);
        assertEquals(Set.of("soft:1", "small:1"), merged.keySet());
        assertArrayEquals(bytes(3, 1), merged.get("soft:1"));
        assertArrayEquals(bytes(3, 2), merged.get("small:1"));

        // Without the preserved decision (the Build 52 two-argument merge) a registered adapter's old bytes are not kept.
        assertTrue(registry.carryForward(Map.of(), previous).isEmpty());
    }

    /** A {@code null} or empty capture means "nothing to store": named cleared, the merge drops the stored bytes. */
    @Test
    void nullOrEmptyCaptureClears() {
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        registry.register(this.plugin, TestAdapter.bestEffort("none", this.plugin).returning(null));
        registry.register(this.plugin, TestAdapter.bestEffort("empty", this.plugin).returning(new byte[0]));
        registry.register(this.plugin, TestAdapter.required("hard", this.plugin).returning(null));
        final ProfileStateAdapters.Capture capture = registry.capture(this.player);
        assertTrue(capture.data().isEmpty());
        assertEquals(Set.of("none:1", "empty:1", "hard:1"), capture.cleared(), "a null from a REQUIRED adapter is not a failure");
        assertTrue(capture.preserved().isEmpty());
        final Map<String, byte[]> previous = Map.of("none:1", bytes(2, 1), "empty:1", bytes(2, 2), "hard:1", bytes(2, 3));
        assertTrue(registry.carryForward(capture.data(), previous, capture).isEmpty());
    }

    /** Bytes no registration accounts for (an absent plugin, another version) are carried forward unchanged. */
    @Test
    void unknownAdapterBytesCarriedForward() {
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        registry.register(this.plugin, TestAdapter.bestEffort("soft", this.plugin).returning(bytes(2, 5)));
        final ProfileStateAdapters.Capture capture = registry.capture(this.player);
        final Map<String, byte[]> previous = new LinkedHashMap<>();
        previous.put("gone.plugin:3", bytes(6, 1));
        previous.put("soft:1", bytes(2, 4));
        previous.put("soft:2", bytes(2, 6));
        final Map<String, byte[]> merged = registry.carryForward(capture.data(), previous, capture);
        assertEquals(Set.of("soft:1", "gone.plugin:3"), merged.keySet(),
            "the same id at another version is accounted for by the fresh capture of that id");
        assertArrayEquals(bytes(2, 5), merged.get("soft:1"));
        assertArrayEquals(bytes(6, 1), merged.get("gone.plugin:3"));
        // With nothing captured for the id, the other version is kept.
        final ProfileStateAdapters empty = new ProfileStateAdapters();
        final Map<String, byte[]> kept = empty.carryForward(Map.of(), Map.of("soft:2", bytes(2, 6)));
        assertArrayEquals(bytes(2, 6), kept.get("soft:2"));
    }

    // ------------------------------------------------------------------ the aggregate budget

    /** Entries that do not fit the 6 MiB / 32-entry budget together refuse the snapshot; nothing is silently dropped. */
    @Test
    void aggregateBudgetRefusesRatherThanDrops() {
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        final int mib = 1024 * 1024;
        final Map<String, byte[]> previous = new LinkedHashMap<>();
        previous.put("gone.a:1", bytes(2 * mib, 1));
        previous.put("gone.b:1", bytes(2 * mib, 2));
        previous.put("gone.c:1", bytes(mib, 3));
        final Map<String, byte[]> captured = Map.of("soft:1", bytes(3 * mib / 2, 4));
        final ProfileOperationException refused = refusal(() -> registry.carryForward(captured, previous,
            new ProfileStateAdapters.Capture(captured, Set.of(), Set.of())));
        assertEquals(ProfileErrorCode.STATE_TOO_LARGE, refused.code());
        assertTrue(refused.getMessage().contains("gone.c:1"), refused.getMessage());
        // Exactly at the budget it fits.
        final Map<String, byte[]> fits = Map.of("soft:1", bytes(mib, 4));
        assertEquals(4, registry.carryForward(fits, previous, new ProfileStateAdapters.Capture(fits, Set.of(), Set.of())).size());

        // At most MAX_PLUGIN_ADAPTERS (31) plugin entries: the 32nd snapshot entry is the reserved criticality record.
        assertEquals(ProfileState.MAX_ADAPTERS - 1, ProfileStateAdapters.MAX_PLUGIN_ADAPTERS);
        final Map<String, byte[]> one = Map.of("new:1", bytes(1, 1));
        final Map<String, byte[]> thirty = new LinkedHashMap<>();
        for (int i = 0; i < ProfileStateAdapters.MAX_PLUGIN_ADAPTERS - 1; i++) thirty.put("old" + i + ":1", bytes(1, i));
        assertEquals(ProfileStateAdapters.MAX_PLUGIN_ADAPTERS, registry.carryForward(one, thirty,
            new ProfileStateAdapters.Capture(one, Set.of(), Set.of())).size());
        final Map<String, byte[]> many = new LinkedHashMap<>(thirty);
        many.put("old" + ProfileStateAdapters.MAX_PLUGIN_ADAPTERS + ":1", bytes(1, 99));
        refusedWith(ProfileErrorCode.STATE_TOO_LARGE, () -> registry.carryForward(one, many,
            new ProfileStateAdapters.Capture(one, Set.of(), Set.of())));
    }

    /** The registry holds at most 31 plugin adapters, and the reserved record's id cannot be registered. */
    @Test
    void registrationCapAndReservedId() {
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        for (int i = 0; i < ProfileStateAdapters.MAX_PLUGIN_ADAPTERS; i++) {
            registry.register(this.plugin, TestAdapter.bestEffort("a" + i, this.plugin));
        }
        assertEquals(ProfileStateAdapters.MAX_PLUGIN_ADAPTERS, registry.size());
        assertThrows(IllegalArgumentException.class, () -> registry.register(this.plugin, TestAdapter.bestEffort("one.more", this.plugin)));
        // Replacing an id the same plugin registered is not a new one.
        registry.register(this.plugin, TestAdapter.bestEffort("a0", this.plugin));
        assertEquals(ProfileStateAdapters.MAX_PLUGIN_ADAPTERS, registry.size());
        assertEquals("sourbycraft.required-adapters:1", ProfileStateAdapters.REQUIRED_META_KEY);
        assertThrows(IllegalArgumentException.class, () -> new ProfileStateAdapters().register(this.plugin,
            TestAdapter.bestEffort("sourbycraft.required-adapters", this.plugin)));
    }

    // ------------------------------------------------------------------ stored criticality and migration (P2-7)

    /**
     * The snapshot records which stored entries were REQUIRED (the reserved entry), so the data of a REQUIRED plugin that
     * is absent later refuses the apply ({@code ADAPTER_MISSING}) instead of being skipped; the record survives later
     * merges on a node without the plugin.
     */
    @Test
    void storedCriticalityKeepsAbsentRequiredDataMissing() {
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        registry.register(this.plugin, TestAdapter.required("hard", this.plugin).returning(bytes(2, 1)));
        registry.register(this.plugin, TestAdapter.bestEffort("soft", this.plugin).returning(bytes(2, 2)));
        final ProfileStateAdapters.Capture capture = registry.capture(this.player);
        assertFalse(capture.data().containsKey(ProfileStateAdapters.REQUIRED_META_KEY), "the capture itself carries the record");
        final Map<String, byte[]> stored = registry.carryForward(capture.data(), Map.of(), capture);
        assertEquals(Set.of("hard:1", "soft:1", ProfileStateAdapters.REQUIRED_META_KEY), stored.keySet());
        assertEquals(Set.of("hard"), ProfileStateAdapters.requiredIds(stored));

        // A node where the plugin is absent: the record still marks the data required.
        final ProfileStateAdapters absent = new ProfileStateAdapters();
        assertFalse(absent.required("hard"));
        refusedWith(ProfileErrorCode.ADAPTER_MISSING, () -> absent.validateApply(stored));
        final Map<String, byte[]> carried = absent.carryForward(Map.of(), stored);
        assertArrayEquals(bytes(2, 1), carried.get("hard:1"));
        assertEquals(Set.of("hard"), ProfileStateAdapters.requiredIds(carried), "a merge without the plugin lost the record");
        refusedWith(ProfileErrorCode.ADAPTER_MISSING, () -> absent.validateApply(carried));
        // A stale record in a fresh capture is ignored; only the merge writes it.
        final Map<String, byte[]> forged = Map.of(ProfileStateAdapters.REQUIRED_META_KEY, bytes(3, 0), "soft:1", bytes(2, 2));
        assertFalse(registry.carryForward(forged, Map.of()).containsKey(ProfileStateAdapters.REQUIRED_META_KEY));
        // Without any required entry no record is written.
        final ProfileStateAdapters soft = new ProfileStateAdapters();
        soft.register(this.plugin, TestAdapter.bestEffort("soft", this.plugin));
        assertEquals(Set.of("soft:1"), soft.carryForward(Map.of("soft:1", bytes(2, 2)), Map.of()).keySet());
    }

    /**
     * {@link dev.iyanz.sourbycraft.api.profile.ProfileStateAdapter#migrate}: older stored data of a REQUIRED adapter is
     * migrated and the migrated bytes applied; a migration that is not supported refuses ({@code ADAPTER_REQUIRED_FAILED});
     * data newer than the registration is {@code ADAPTER_MISSING}; a BEST_EFFORT adapter without a migration is skipped.
     */
    @Test
    void migrateOlderStoredVersions() {
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        final TestAdapter hard = new TestAdapter("hard", 2, this.plugin,
            descriptor("hard", 2, ProfileDataScope.PROFILE_SCOPED, ProfileDataCriticality.REQUIRED, 16));
        final TestAdapter soft = new TestAdapter("soft", 2, this.plugin, null);
        registry.register(this.plugin, hard);
        registry.register(this.plugin, soft);
        final Map<String, byte[]> old = new LinkedHashMap<>();
        old.put("hard:1", bytes(2, 1));
        old.put("soft:1", bytes(2, 2));

        refusedWith(ProfileErrorCode.ADAPTER_REQUIRED_FAILED, () -> registry.validateApply(old));
        hard.onMigrate.set((from, data) -> from == 1 ? bytes(3, 7) : null);
        assertDoesNotThrow(() -> registry.validateApply(old));
        registry.apply(this.player, old);
        assertEquals(1, hard.applied.size());
        assertArrayEquals(bytes(3, 7), hard.applied.getFirst(), "the stored bytes were applied instead of the migrated ones");
        assertTrue(soft.applied.isEmpty(), "a best-effort adapter without a migration got data of another version");

        refusedWith(ProfileErrorCode.ADAPTER_MISSING, () -> registry.validateApply(Map.of("hard:3", bytes(2, 1))));
        hard.onMigrate.set((from, data) -> bytes(17, 1));
        refusedWith(ProfileErrorCode.ADAPTER_REQUIRED_FAILED, () -> registry.validateApply(old));
        refusedWith(ProfileErrorCode.ADAPTER_REQUIRED_FAILED, () -> registry.apply(this.player, old));
    }

    // ------------------------------------------------------------------ validateApply and apply

    @Test
    void requiredDataWithoutItsRegistrationIsMissing() {
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        registry.register(this.plugin, TestAdapter.required("hard", this.plugin));
        assertDoesNotThrow(() -> registry.validateApply(Map.of("hard:1", bytes(2, 1))));
        refusedWith(ProfileErrorCode.ADAPTER_MISSING, () -> registry.validateApply(Map.of("hard:2", bytes(2, 1))));
        assertEquals(1, registry.unregisterAll(this.plugin));
        // Unregistered, a declared-REQUIRED id is required only through the state's own record (see
        // storedCriticalityKeepsAbsentRequiredDataMissing); bytes stored without that record are opaque.
        assertFalse(registry.required("hard"));
        assertDoesNotThrow(() -> registry.validateApply(Map.of("hard:1", bytes(2, 1))));
    }

    /** A REQUIRED apply failure fails the apply after the other adapters ran; a BEST_EFFORT failure is skipped. */
    @Test
    void requiredApplyFailureFailsTheApply() {
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        final TestAdapter soft = TestAdapter.bestEffort("soft", this.plugin);
        final TestAdapter hard = TestAdapter.required("hard", this.plugin);
        final TestAdapter flaky = TestAdapter.bestEffort("flaky", this.plugin);
        registry.register(this.plugin, soft);
        registry.register(this.plugin, hard);
        registry.register(this.plugin, flaky);
        flaky.failApply.set(true);
        final Map<String, byte[]> state = new LinkedHashMap<>();
        state.put("hard:1", bytes(2, 1));
        state.put("soft:1", bytes(2, 2));
        state.put("flaky:1", bytes(2, 3));
        assertDoesNotThrow(() -> registry.apply(this.player, state));
        assertEquals(1, soft.applied.size());
        assertEquals(1, hard.applied.size());

        hard.failApply.set(true);
        refusedWith(ProfileErrorCode.ADAPTER_REQUIRED_FAILED, () -> registry.apply(this.player, state));
        assertEquals(2, soft.applied.size(), "the other adapters did not run before the failure was reported");
    }

    /** UNSUPPORTED scope: registered, never asked to capture, its data never applied, and REQUIRED data of it is missing. */
    @Test
    void unsupportedScopeIsNeitherCapturedNorApplied() {
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        final TestAdapter unsupported = new TestAdapter("shared", 1, this.plugin,
            descriptor("shared", 1, ProfileDataScope.UNSUPPORTED, ProfileDataCriticality.BEST_EFFORT, 8)).throwing();
        registry.register(this.plugin, unsupported);
        final ProfileStateAdapters.Capture capture = assertDoesNotThrow(() -> registry.capture(this.player));
        assertTrue(capture.data().isEmpty());
        assertTrue(capture.preserved().isEmpty());
        assertTrue(capture.cleared().isEmpty());
        registry.apply(this.player, Map.of("shared:1", bytes(2, 1)));
        assertTrue(unsupported.applied.isEmpty());

        // Its stored bytes are carried by a merge (review P3-14), never dropped as "asked, stored nothing".
        assertArrayEquals(bytes(2, 1), registry.carryForward(Map.of(), Map.of("shared:1", bytes(2, 1))).get("shared:1"));

        final ProfileStateAdapters strict = new ProfileStateAdapters(Set.of("shared"));
        strict.register(this.plugin, unsupported);
        refusedWith(ProfileErrorCode.ADAPTER_MISSING, () -> strict.validateApply(Map.of("shared:1", bytes(2, 1))));
    }

    // ------------------------------------------------------------------ the two-phase shutdown merge

    /**
     * PM-076: the adapters are captured while the plugins are enabled (phase one) and merged after they were disabled
     * (phase two) exactly as a live capture: fresh bytes, preserved bytes of a failed best-effort adapter, cleared bytes
     * dropped, unknown bytes carried. A live capture after the disable would only carry the old bytes.
     */
    @Test
    void stagedShutdownCaptureMergesLikeALiveOne() {
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        registry.register(this.plugin, TestAdapter.bestEffort("fresh", this.plugin).returning(bytes(3, 9)));
        registry.register(this.plugin, TestAdapter.bestEffort("failing", this.plugin).throwing());
        registry.register(this.plugin, TestAdapter.bestEffort("empty", this.plugin).returning(null));
        final Map<String, byte[]> previous = new LinkedHashMap<>();
        previous.put("fresh:1", bytes(3, 1));
        previous.put("failing:1", bytes(3, 2));
        previous.put("empty:1", bytes(3, 3));
        previous.put("gone:2", bytes(3, 4));

        final ProfileStateAdapters.Capture staged = registry.capture(this.player);
        assertEquals(3, registry.unregisterAll(this.plugin));
        final Map<String, byte[]> merged = registry.carryForward(staged.data(), previous, staged);
        assertEquals(Set.of("fresh:1", "failing:1", "gone:2"), merged.keySet());
        assertArrayEquals(bytes(3, 9), merged.get("fresh:1"));
        assertArrayEquals(bytes(3, 2), merged.get("failing:1"));
        assertArrayEquals(bytes(3, 4), merged.get("gone:2"));

        // What a capture after the disable would store instead: no fresh bytes, the cleared entry kept.
        final ProfileStateAdapters.Capture late = registry.capture(this.player);
        assertTrue(late.data().isEmpty());
        final Map<String, byte[]> carried = registry.carryForward(late.data(), previous, late);
        assertArrayEquals(bytes(3, 1), carried.get("fresh:1"));
        assertNotNull(carried.get("empty:1"));
    }
}
