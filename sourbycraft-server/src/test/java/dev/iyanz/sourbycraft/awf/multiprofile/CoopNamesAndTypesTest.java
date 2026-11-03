package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.BINGO;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.CLASSIC;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.IRONMAN;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.SLOTS;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.STRANDED;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.join;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.op;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusedWith;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.tree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileDetails;
import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileRequest;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ProfileClaim;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * G1 names and types (HYPIXEL-PARITY §2, §6, §7) as Build 51 wires them, plus the one-intent create and delete
 * recovery at engine level: names are pool-only, unique per account ignoring case and immutable; legacy names are
 * derived stably from the id; types are allowed, defaulted and refused per settings and persisted; a format-1 store
 * refuses what it cannot store with {@code MIGRATION_REQUIRED}.
 */
class CoopNamesAndTypesTest {

    @TempDir
    Path directory;

    private CoopFixtures.EngineHarness format2(final ProfileSettings.Types types) throws Exception {
        final CoopFixtures.EngineHarness harness = new CoopFixtures.EngineHarness(this.directory, CoopFixtures.settings(types), true);
        assertEquals(StoreFormat.Gate.FORMAT_2, harness.open().gate());
        return harness;
    }

    // ------------------------------------------------------------------ names

    @Test
    void namesAreUniquePerAccountIgnoringCase() throws Exception {
        try (CoopFixtures.EngineHarness harness = format2(ProfileSettings.DEFAULT_TYPES)) {
            final ProfileEngine engine = harness.engine;
            final UUID player = UUID.randomUUID();
            final ProfileDetails apple = join(engine.createDetailed(ProfileRequest.of(player, "skyblock").withName("apple")));
            assertEquals("Apple", apple.name(), "a requested name is stored in its pool spelling");
            final Map<String, String> before = tree(this.directory);
            refusedWith(ProfileErrorCode.NAME_TAKEN, () -> join(engine.createDetailed(
                ProfileRequest.of(player, "skyblock").withName("APPLE"))));
            assertEquals(before, tree(this.directory), "a refused name wrote something");
            assertEquals(1, join(engine.list(player)).size());

            // Another account may use the same name; the rolled names of one account never collide.
            final UUID other = UUID.randomUUID();
            assertEquals("Apple", join(engine.createDetailed(ProfileRequest.of(other, "skyblock").withName("Apple"))).name());
            final Set<String> keys = new HashSet<>();
            keys.add(CuteNames.key(apple.name()));
            for (int i = 0; i < SLOTS - 1; i++) {
                final ProfileDetails rolled = join(engine.createDetailed(ProfileRequest.of(player, "skyblock")));
                assertTrue(CuteNames.valid(rolled.name()), rolled.name());
                assertTrue(CuteNames.NAMES.contains(rolled.name()), "a rolled name comes from the pool: " + rolled.name());
                assertTrue(keys.add(CuteNames.key(rolled.name())), "two profiles of one account share " + rolled.name());
            }
            // The aliases of the account are unique as well, and derived from the names.
            final PlayerAccount account = harness.repository.loadAccount(player, SLOTS);
            assertEquals(SLOTS, account.aliases().size());
            assertEquals(SLOTS, new HashSet<>(account.aliases().values()).size());
            assertEquals("apple", account.alias(apple.info().id()));

            // The store enforces the rule itself: a create that names a taken name is refused at PREPARE.
            final PlayerClaim q = join(harness.players.claim(other, UUID.randomUUID())).claim();
            final CoopFixtures.CreatePlan plan = CoopFixtures.planCreate(harness.repository, other, "Apple", CLASSIC);
            refusedWith(ProfileErrorCode.NAME_TAKEN, () -> CoopFixtures.prepareCreate(harness.repository, q, plan));
            assertTrue(harness.repository.intent(plan.op().operationId()).isEmpty());
            join(harness.players.release(q));
        }
    }

    @Test
    void namesArePoolOnly() throws Exception {
        try (CoopFixtures.EngineHarness harness = format2(ProfileSettings.DEFAULT_TYPES)) {
            final ProfileEngine engine = harness.engine;
            final UUID player = UUID.randomUUID();
            for (final String invalid : List.of("Dragon", "", "Apple 0", "Apple 1000", "apple-2", "Apple  2", "Apple2", "<b>Kiwi</b>",
                "A".repeat(40))) {
                refusedWith(ProfileErrorCode.NAME_INVALID, () -> join(engine.createDetailed(
                    ProfileRequest.of(player, "skyblock").withName(invalid))));
            }
            assertTrue(join(engine.list(player)).isEmpty(), "an invalid name created a profile");
            assertEquals(0L, harness.repository.loadAccount(player, SLOTS).revision(), "an invalid name advanced A");
            assertEquals("Kiwi", join(engine.createDetailed(ProfileRequest.of(player, "skyblock").withName("  kiwi "))).name());
            assertEquals("Apple 12", join(engine.createDetailed(ProfileRequest.of(player, "skyblock").withName("apple 12"))).name());
        }
        assertEquals("Kiwi", CuteNames.normalize("KIWI"));
        assertThrows(IllegalArgumentException.class, () -> CuteNames.normalize("Dragon"));
        assertFalse(CuteNames.valid(null));
        assertTrue(CuteNames.validAlias("apple-2"));
        assertFalse(CuteNames.validAlias("Apple"));
        assertEquals("apple-2", CuteNames.alias("Apple 2"));
        assertEquals("apple-3", CuteNames.allocateAlias("apple", Set.of("apple", "apple-2")));
        assertEquals(CuteNames.MAX_ALIAS_LENGTH, CuteNames.allocateAlias("a".repeat(32), Set.of("a".repeat(32))).length());
        // When the whole pool is taken the smallest free suffix is used; it is still a valid, unique name.
        final Set<String> everything = new HashSet<>();
        for (final String name : CuteNames.NAMES) everything.add(CuteNames.key(name));
        final String overflow = CuteNames.generate(everything);
        assertTrue(CuteNames.valid(overflow));
        assertFalse(everything.contains(CuteNames.key(overflow)));
        assertEquals(CuteNames.NAMES.getFirst() + " 2", overflow);
    }

    @Test
    void namesAreImmutable() throws Exception {
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(this.directory)) {
            final FileProfileRepository repository = store.repository;
            final UUID player = UUID.randomUUID();
            final PlayerClaim q = store.q(player);
            final AuroraProfile profile = CoopFixtures.create(repository, q, "Orange", STRANDED);
            final ProfileClaim p = store.p(profile.id());
            final long a = repository.loadAccount(player, SLOTS).revision();
            final AuroraProfile renamed = new AuroraProfile(profile.id(), profile.accountId(), profile.type(), profile.profileType(),
                "Papaya", profile.metadata(), profile.createdAtMillis(), profile.revision() + 1L, profile.status(), profile.members(),
                profile.bindingRevision(), null);
            final Map<String, String> before = tree(this.directory);
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> repository.commitProfile(renamed,
                CoopWriteGuard.metadata(op(CoopOperation.Kind.RENAME_DISPLAY, player), p, q, a, profile.revision())));
            assertEquals(before, tree(this.directory), "a rename wrote something");
            // A metadata change keeps the name (and the type).
            CoopFixtures.committed(repository.commitProfile(profile.withStatus(ProfileStatus.LOCKED),
                CoopWriteGuard.metadata(op(CoopOperation.Kind.SET_STATUS, player), p, q, a, profile.revision())));
            final AuroraProfile stored = repository.profile(profile.id()).orElseThrow();
            assertEquals("Orange", stored.name());
            assertEquals(STRANDED, stored.profileType());
            // An alias rename changes only the account's alias, never the profile's name.
            CoopFixtures.committed(repository.commitAccount(repository.loadAccount(player, SLOTS).withAlias(profile.id(), "grove"),
                CoopWriteGuard.alias(op(CoopOperation.Kind.RENAME_ALIAS, player), q, a)));
            assertEquals("Orange", repository.profile(profile.id()).orElseThrow().name());
            // A duplicate alias in one account is refused, compared in lower case.
            final AuroraProfile second = CoopFixtures.create(repository, q, "Pear", CLASSIC);
            final PlayerAccount account = repository.loadAccount(player, SLOTS);
            assertThrows(IllegalArgumentException.class, () -> account.withAlias(second.id(), "grove"));
        }
    }

    @Test
    void legacyNameDerivationIsStable() {
        assertEquals(List.of("Apple", "Banana", "Blueberry", "Coconut", "Cucumber", "Grapes", "Kiwi", "Lemon", "Lime", "Mango",
            "Orange", "Papaya", "Pear", "Peach", "Pineapple", "Pomegranate", "Raspberry", "Strawberry", "Tomato", "Watermelon",
            "Zucchini"), CuteNames.LEGACY_NAMES, "the derivation list must never change");
        assertEquals(CuteNames.LEGACY_NAMES, CuteNames.NAMES.subList(0, CuteNames.LEGACY_NAMES.size()),
            "new names are appended after the legacy list");
        // UUID.hashCode is specified (msb ^ lsb folded), so these values hold on every JVM.
        assertEquals("Apple", CuteNames.fromId(new UUID(0L, 0L)));
        assertEquals("Banana", CuteNames.fromId(new UUID(0L, 1L)));
        assertEquals("Zucchini", CuteNames.fromId(new UUID(0L, 20L)));
        assertEquals("Banana", CuteNames.fromId(new UUID(0L, 22L)));
        assertEquals("Mango", CuteNames.fromId(UUID.fromString("123e4567-e89b-12d3-a456-426614174000")));
        final UUID id = UUID.randomUUID();
        assertEquals(CuteNames.fromId(id), CuteNames.fromId(UUID.fromString(id.toString())));
        // A format-1 record reads back with the derived name and the classic type.
        final AuroraProfile legacy = new AuroraProfile(id, UUID.randomUUID(), "skyblock", Map.of(), 1L, 0L, ProfileStatus.ACTIVE);
        assertEquals(CuteNames.fromId(id), legacy.name());
        assertEquals(CLASSIC, legacy.profileType());
        assertTrue(legacy.legacy());
    }

    // ------------------------------------------------------------------ types

    @Test
    void typesAreAllowedDefaultedRefusedAndPersisted() throws Exception {
        final ProfileSettings.Types types = new ProfileSettings.Types(EnumSet.of(CLASSIC, IRONMAN), IRONMAN);
        final UUID player = UUID.randomUUID();
        final UUID defaulted;
        final UUID classic;
        try (CoopFixtures.EngineHarness harness = format2(types)) {
            final ProfileEngine engine = harness.engine;
            final ProfileDetails byDefault = join(engine.createDetailed(ProfileRequest.of(player, "skyblock")));
            assertEquals(IRONMAN, byDefault.profileType(), "types.default applies when a request names none");
            defaulted = byDefault.info().id();
            final Map<String, String> before = tree(this.directory);
            refusedWith(ProfileErrorCode.TYPE_NOT_ALLOWED, () -> join(engine.createDetailed(
                ProfileRequest.of(player, "skyblock", BINGO))));
            refusedWith(ProfileErrorCode.TYPE_NOT_ALLOWED, () -> join(engine.createDetailed(
                ProfileRequest.of(player, "skyblock", STRANDED))));
            assertEquals(before, tree(this.directory), "a refused type wrote something");
            classic = join(engine.createDetailed(ProfileRequest.of(player, "skyblock", CLASSIC))).info().id();
            // The consumer namespace is free and independent of the policy type.
            final ProfileDetails namespaced = join(engine.createDetailed(ProfileRequest.of(player, "bingo")));
            assertEquals("bingo", namespaced.info().type());
            assertEquals(IRONMAN, namespaced.profileType());
        }
        // Persisted: a new process reads the same types back.
        try (CoopFixtures.EngineHarness harness = format2(types)) {
            assertEquals(IRONMAN, join(harness.engine.getDetails(defaulted)).orElseThrow().profileType());
            assertEquals(CLASSIC, join(harness.engine.getDetails(classic)).orElseThrow().profileType());
            assertEquals(3, join(harness.engine.listDetails(player)).size());
        }
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Types(EnumSet.of(CLASSIC), IRONMAN));
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Types(EnumSet.noneOf(
            dev.iyanz.sourbycraft.api.profile.ProfileType.class), CLASSIC));
        assertEquals(EnumSet.allOf(dev.iyanz.sourbycraft.api.profile.ProfileType.class), ProfileSettings.DEFAULT_TYPES.allowed());
        assertEquals(CLASSIC, ProfileSettings.DEFAULT_TYPES.defaultType());
    }

    @Test
    void aFormat1StoreRefusesWhatItCannotStore() throws Exception {
        try (CoopFixtures.EngineHarness harness = new CoopFixtures.EngineHarness(this.directory,
            CoopFixtures.settings(ProfileSettings.DEFAULT_TYPES), false)) {
            assertEquals(StoreFormat.Gate.LEGACY, harness.open().gate());
            final ProfileEngine engine = harness.engine;
            final UUID player = UUID.randomUUID();
            refusedWith(ProfileErrorCode.MIGRATION_REQUIRED, () -> join(engine.createDetailed(
                ProfileRequest.of(player, "skyblock", IRONMAN))));
            refusedWith(ProfileErrorCode.MIGRATION_REQUIRED, () -> join(engine.createDetailed(
                ProfileRequest.of(player, "skyblock").withName("Apple"))));
            assertTrue(join(engine.list(player)).isEmpty());
            final ProfileDetails plain = join(engine.createDetailed(ProfileRequest.of(player, "skyblock")));
            assertEquals(CuteNames.fromId(plain.info().id()), plain.name(), "a format-1 profile shows its derived name");
            assertEquals(CLASSIC, plain.profileType());
            assertFalse(Files.exists(this.directory.resolve(StoreFormat.FILE_NAME)), "a format-1 store got a marker");
            final PlayerClaim q = join(harness.players.claim(player, UUID.randomUUID())).claim();
            refusedWith(ProfileErrorCode.MIGRATION_REQUIRED, () -> harness.repository.commitAccount(
                harness.repository.loadAccount(player, SLOTS).withSlotLimit(4),
                CoopWriteGuard.alias(op(CoopOperation.Kind.ACCOUNT_LIMIT, player), q, 1L)));
            refusedWith(ProfileErrorCode.MIGRATION_REQUIRED, () -> harness.repository.memberHeads(plain.info().id()));
        }
        // A non-classic types.default cannot be honoured by a format-1 store either.
        try (CoopFixtures.EngineHarness harness = new CoopFixtures.EngineHarness(this.directory,
            CoopFixtures.settings(new ProfileSettings.Types(EnumSet.of(CLASSIC, BINGO), BINGO)), false)) {
            harness.open();
            refusedWith(ProfileErrorCode.MIGRATION_REQUIRED, () -> join(harness.engine.createDetailed(
                ProfileRequest.of(UUID.randomUUID(), "skyblock"))));
        }
    }

    // ------------------------------------------------------------------ one-intent create / delete recovery

    @Test
    void anInterruptedCreateIsAbortedAtStartupAndLeavesNoProfile() throws Exception {
        final UUID player = UUID.randomUUID();
        final CoopFixtures.CreatePlan plan;
        try (CoopFixtures.EngineHarness harness = format2(ProfileSettings.DEFAULT_TYPES)) {
            final PlayerClaim q = join(harness.players.claim(player, UUID.randomUUID())).claim();
            plan = CoopFixtures.planCreate(harness.repository, player, "Tomato", CLASSIC);
            CoopFixtures.prepareCreate(harness.repository, q, plan);
            // The process dies here: PREPARED, account reserved, no profile.
        }
        try (CoopFixtures.EngineHarness harness = format2(ProfileSettings.DEFAULT_TYPES)) {
            assertTrue(harness.engine.storeStatus().unresolvedIntents().isEmpty(), harness.engine.storeStatus().toString());
            assertEquals(ProfileIntent.Phase.ABORTED, harness.repository.intent(plan.op().operationId()).orElseThrow().phase());
            final PlayerAccount account = harness.repository.loadAccount(player, SLOTS);
            assertNull(account.pendingIntent(), "the reservation survived the abort");
            assertTrue(account.profileIds().isEmpty());
            assertTrue(harness.repository.profile(plan.profile().id()).isEmpty(), "an uncommitted create left a profile");
            // The account is usable again and the name is free.
            assertEquals("Tomato", join(harness.engine.createDetailed(ProfileRequest.of(player, "skyblock").withName("Tomato"))).name());
        }
    }

    @Test
    void anInterruptedDeleteIsAbortedOrRolledForwardNeverHalfApplied() throws Exception {
        final UUID player = UUID.randomUUID();
        final UUID first;
        final UUID second;
        final CoopFixtures.DeletePlan uncommitted;
        try (CoopFixtures.EngineHarness harness = format2(ProfileSettings.DEFAULT_TYPES)) {
            first = join(harness.engine.createDetailed(ProfileRequest.of(player, "skyblock").withName("Apple"))).info().id();
            second = join(harness.engine.createDetailed(ProfileRequest.of(player, "skyblock").withName("Banana"))).info().id();
            final PlayerClaim q = join(harness.players.claim(player, UUID.randomUUID())).claim();
            final ProfileClaim p = join(harness.profiles.claimTransaction(first, UUID.randomUUID())).claim();
            uncommitted = CoopFixtures.planDelete(harness.repository, harness.repository.profile(first).orElseThrow());
            CoopFixtures.prepare(harness.repository, uncommitted.op(), p, q, uncommitted.before(), uncommitted.after(),
                uncommitted.images(), List.of());
            // The process dies before COMMIT.
        }
        try (CoopFixtures.EngineHarness harness = format2(ProfileSettings.DEFAULT_TYPES)) {
            assertEquals(ProfileIntent.Phase.ABORTED, harness.repository.intent(uncommitted.op().operationId()).orElseThrow().phase());
            final AuroraProfile kept = harness.repository.profile(first).orElseThrow();
            assertEquals(ProfileStatus.ACTIVE, kept.status(), "an uncommitted delete tombstoned the profile");
            assertNull(kept.pendingIntent());
            assertTrue(harness.repository.loadAccount(player, SLOTS).owns(first), "an uncommitted delete unlisted the profile");

            // A delete whose COMMITTED marker was forced before the process died is finished at the next open.
            final PlayerClaim q = join(harness.players.claim(player, UUID.randomUUID())).claim();
            final ProfileClaim p = join(harness.profiles.claimTransaction(second, UUID.randomUUID())).claim();
            final CoopFixtures.DeletePlan decided = CoopFixtures.planDelete(harness.repository,
                harness.repository.profile(second).orElseThrow());
            final ProfileIntent prepared = CoopFixtures.prepare(harness.repository, decided.op(), p, q, decided.before(), decided.after(),
                decided.images(), List.of());
            final ProfileIntent committedMarker = prepared.at(ProfileIntent.Phase.COMMITTED, "committed");
            harness.close();
            final Path file = this.directory.resolve("intents").resolve(committedMarker.operationId() + ".awfp");
            FileProfileIo.writeDurable(file, ProfileRecords.frame2(ProfileRecords.TYPE_INTENT, committedMarker.revision(),
                ProfileRecords.intentPayload2(committedMarker), file.toString()));
            try (CoopFixtures.EngineHarness restarted = format2(ProfileSettings.DEFAULT_TYPES)) {
                assertEquals(ProfileIntent.Phase.FINALIZED, restarted.repository.intent(decided.op().operationId()).orElseThrow().phase());
                assertEquals(ProfileStatus.DELETED, restarted.repository.profile(second).orElseThrow().status());
                assertFalse(restarted.repository.loadAccount(player, SLOTS).owns(second));
                assertTrue(restarted.repository.loadAccount(player, SLOTS).owns(first));
                // An ordinary delete through the engine is one intent too and leaves nothing pending.
                assertTrue(join(restarted.engine.delete(first)));
                assertEquals(ProfileStatus.DELETED, restarted.repository.profile(first).orElseThrow().status());
                assertTrue(restarted.repository.loadAccount(player, SLOTS).profileIds().isEmpty());
                assertTrue(restarted.repository.index(ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL, null, null, null)
                    .entries().isEmpty());
            }
        }
    }
}
