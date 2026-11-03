package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.CLASSIC;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.SLOTS;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.join;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.op;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusedWith;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.tree;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileRepositoryMigration.Format2Result;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The offline format 1 to 2 migration of a solo FILE store (COOP-DESIGN §4, OD-2): dry-run writes nothing, apply
 * writes the verified backup before anything else, an interrupted migration resumes, and the migrated store holds
 * exactly the format-1 data with owner epochs, stable derived names, the classic type and exact member heads.
 */
class CoopMigrationTest {

    @TempDir
    Path directory;

    /** Two profiles whose ids derive the same legacy name ({@code Banana}), so the younger one is suffixed. */
    private static final UUID OLDER = new UUID(0L, 1L);
    private static final UUID YOUNGER = new UUID(0L, 22L);

    private Path store() {
        return this.directory.resolve("store");
    }

    private Path backups() {
        return this.directory.resolve("backups");
    }

    private record Seed(UUID player, List<ProfileSnapshot> olderSnapshots, PlayerAccount account) {}

    /** A format-1 store as PR #62 wrote it: account, two profiles, snapshots, binding, archive, a terminal journal. */
    private Seed seed(final FileProfileRepository repository, final boolean pendingJournal) throws Exception {
        final UUID player = UUID.randomUUID();
        assertEquals("Banana", CuteNames.fromId(OLDER));
        assertEquals("Banana", CuteNames.fromId(YOUNGER));
        repository.saveProfile(new AuroraProfile(OLDER, player, "skyblock", Map.of("slot", "1"), 100L, 0L, ProfileStatus.ACTIVE));
        repository.saveProfile(new AuroraProfile(YOUNGER, player, "skyblock", Map.of(), 200L, 0L, ProfileStatus.ACTIVE));
        final ProfileSnapshot s1 = flat(OLDER, 1L);
        final ProfileSnapshot s2 = flat(OLDER, 2L);
        repository.saveSnapshot(s1, 3);
        repository.saveSnapshot(s2, 3);
        repository.saveBinding(new ProfileWorldBinding(OLDER, Map.of("island-1", ProfileWorldRole.PRIMARY), "island-template"));
        final PlayerAccount account = PlayerAccount.empty(player, SLOTS).withProfile(OLDER).withProfile(YOUNGER).withActive(OLDER);
        repository.saveAccount(account);
        final byte[] archived = CoopFixtures.state();
        repository.saveLegacySnapshot(player, new ProfileSnapshot(player, UUID.randomUUID(), ProfileState.SCHEMA, 0L, archived,
            ProfileStateCodec.checksum(archived), 50L), ProfileWriteGuard.UNGUARDED);
        final ProfileTransition done = ProfileTransition.started(player, null, OLDER, 10_000L, 1L).at(TransitionPhase.ACTIVE);
        assertTrue(done.terminal());
        repository.saveTransition(done);
        if (pendingJournal) {
            final ProfileTransition open = ProfileTransition.started(player, OLDER, YOUNGER, 10_000L, 2L).at(TransitionPhase.SAVING);
            assertFalse(open.terminal());
            repository.saveTransition(open);
        }
        assertTrue(repository.storeFormat().legacyFormat());
        return new Seed(player, List.of(s1, s2), account);
    }

    private static ProfileSnapshot flat(final UUID profile, final long revision) {
        final byte[] state = CoopFixtures.state();
        return new ProfileSnapshot(profile, UUID.randomUUID(), ProfileState.SCHEMA, revision, state, ProfileStateCodec.checksum(state),
            1_000L + revision);
    }

    private ProfileSettings.Migration apply() {
        return new ProfileSettings.Migration(ProfileSettings.MigrationMode.APPLY, backups().toString());
    }

    // ------------------------------------------------------------------ dry-run

    @Test
    void aDryRunWritesNothing() throws Exception {
        try (FileProfileRepository repository = new FileProfileRepository(store())) {
            seed(repository, false);
            final Map<String, String> before = tree(store());
            final Format2Result result = ProfileRepositoryMigration.toFormat2(repository,
                new ProfileSettings.Migration(ProfileSettings.MigrationMode.DRY_RUN, backups().toString()), CoopFixtures.BUILD);
            assertEquals(Format2Result.Outcome.DRY_RUN, result.outcome(), result.summary());
            assertTrue(result.refused().isEmpty(), result.summary());
            assertEquals(1, result.accounts());
            assertEquals(2, result.profiles());
            assertEquals(2, result.heads());
            assertEquals(2, result.snapshots());
            assertEquals(1, result.bindings());
            assertEquals(1, result.archives());
            assertNull(result.backup(), "a dry-run wrote a backup");
            assertTrue(result.warnings().stream().anyMatch(warning -> warning.contains("Banana 2")), result.summary());
            assertEquals(before, tree(store()), "a dry-run changed the store");
            assertFalse(Files.exists(backups()) && tree(backups()).size() > 0, "a dry-run wrote into the backup directory");
            assertTrue(repository.storeFormat().legacyFormat());
            // Mode off reads nothing at all.
            assertEquals(Format2Result.Outcome.OFF, ProfileRepositoryMigration.toFormat2(repository,
                ProfileSettings.DEFAULT_MIGRATION, CoopFixtures.BUILD).outcome());
        }
    }

    // ------------------------------------------------------------------ CoopFormat1SoloMigration

    @Test
    void CoopFormat1SoloMigration() throws Exception {
        final Seed seed;
        final Map<String, String> original;
        try (FileProfileRepository repository = new FileProfileRepository(store())) {
            seed = seed(repository, false);
            original = tree(store());
            final Format2Result result = ProfileRepositoryMigration.toFormat2(repository, apply(), CoopFixtures.BUILD);
            assertEquals(Format2Result.Outcome.MIGRATED, result.outcome(), result.summary());
            assertTrue(result.ready());

            // Backup first: the backup is the complete pre-migration store, byte for byte, and verified.
            assertNotNull(result.backup());
            final Path backup = Path.of(result.backup());
            assertTrue(backup.startsWith(backups().toRealPath()) || backup.startsWith(backups()), backup.toString());
            assertTrue(Files.isRegularFile(backup.resolve(FileProfileBackup.MANIFEST)));
            final Map<String, String> copied = new TreeMap<>(tree(backup));
            copied.remove(FileProfileBackup.MANIFEST);
            assertEquals(original, copied, "the backup is not the pre-migration store");
            assertEquals(backup.toString(), repository.verifiedBackup(backups().toString()).orElseThrow());

            assertMigrated(repository, seed);

            // Idempotent: a second apply finds format 2 and writes nothing.
            final Map<String, String> migrated = tree(store());
            assertEquals(Format2Result.Outcome.ALREADY_FORMAT_2,
                ProfileRepositoryMigration.toFormat2(repository, apply(), CoopFixtures.BUILD).outcome());
            assertEquals(migrated, tree(store()));
        }

        // The migrated store works with the format-2 paths: names stay unique against the derived ones.
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.open(store())) {
            final PlayerClaim q = store.q(seed.player());
            refusedWith(ProfileErrorCode.NAME_TAKEN, () -> CoopFixtures.prepareCreate(store.repository, q,
                CoopFixtures.planCreate(store.repository, seed.player(), "banana", CLASSIC)));
            final AuroraProfile created = CoopFixtures.create(store.repository, q, "Kiwi", CLASSIC);
            assertEquals("Kiwi", created.name());
            assertEquals(seed.account().revision() + 2L, store.repository.loadAccount(seed.player(), SLOTS).revision(),
                "the migrated account kept its revision A");
            // The migrated owner can save into its stream; R continues from the legacy revision.
            final MemberStateHead next = CoopFixtures.save(store.repository, q, OLDER);
            assertEquals(3L, next.revision());
        }
    }

    private void assertMigrated(final FileProfileRepository repository, final Seed seed) throws Exception {
        assertTrue(repository.storeFormat().readyFormat2());
        assertTrue(repository.legacyRecordsRemaining(16).isEmpty(), repository.legacyRecordsRemaining(16).toString());
        final UUID player = seed.player();

        final AuroraProfile older = repository.profile(OLDER).orElseThrow();
        final AuroraProfile younger = repository.profile(YOUNGER).orElseThrow();
        assertEquals("Banana", older.name(), "the older profile keeps the derived name");
        assertEquals("Banana 2", younger.name(), "the younger one is suffixed");
        assertEquals(CLASSIC, older.profileType());
        assertEquals(CLASSIC, younger.profileType());
        assertEquals("skyblock", older.type());
        assertEquals(Map.of("slot", "1"), older.metadata());
        assertEquals(100L, older.createdAtMillis());
        assertEquals(0L, older.revision(), "M starts at 0");
        assertEquals(1, older.members().size());
        final ProfileMember owner = older.owner();
        assertEquals(player, owner.playerId());
        assertEquals(ProfileRole.OWNER, owner.role());
        assertEquals(ProfileRepositoryMigration.migrationEpoch(OLDER), owner.epoch(), "the owner epoch is deterministic");
        assertEquals(1L, older.bindingRevision());

        // The owner's stream head names exactly the latest legacy snapshot, at its legacy revision R.
        final ProfileSnapshot latest = seed.olderSnapshots().getLast();
        final MemberStateHead head = repository.memberHead(OLDER, player, owner.epoch()).orElseThrow();
        assertEquals(MemberStateHead.State.READY, head.state());
        assertEquals(latest.revision(), head.revision());
        assertEquals(latest.snapshotId(), head.snapshotId());
        final ProfileSnapshot member = repository.memberSnapshot(OLDER, player, owner.epoch(), latest.snapshotId()).orElseThrow();
        assertTrue(head.matches(member));
        org.junit.jupiter.api.Assertions.assertArrayEquals(latest.state(), member.state(), "the archived state was not preserved");
        for (final ProfileSnapshot flat : seed.olderSnapshots()) {
            assertTrue(repository.memberSnapshot(OLDER, player, owner.epoch(), flat.snapshotId()).isPresent(),
                "legacy snapshot " + flat.snapshotId() + " was not copied");
        }
        // A profile without a snapshot stays UNINITIALIZED: no inventory is synthesised.
        final MemberStateHead empty = repository.memberHead(YOUNGER, player, younger.owner().epoch()).orElseThrow();
        assertEquals(MemberStateHead.State.UNINITIALIZED, empty.state());
        assertEquals(0L, empty.revision());

        final PlayerAccount account = repository.loadAccount(player, SLOTS);
        assertEquals(seed.account().revision(), account.revision(), "A is preserved");
        assertEquals(List.of(OLDER, YOUNGER), account.profileIds());
        assertEquals("banana", account.alias(OLDER));
        assertEquals("banana-2", account.alias(YOUNGER));
        assertEquals(OLDER, account.activeProfile());
        assertEquals(owner.epoch(), account.activeEpoch());
        assertTrue(account.legacyAdoptionClosed());
        assertNull(account.pendingIntent());

        assertEquals(Map.of("island-1", ProfileWorldRole.PRIMARY), repository.binding(OLDER).orElseThrow().worlds());
        assertEquals(1L, repository.bindingRevision(OLDER).orElseThrow());
        assertTrue(repository.legacySnapshot(player).isPresent(), "the pre-profile archive was lost");
        assertTrue(repository.transitions().isEmpty(), "terminal journals are retired (they are in the backup)");
        refusedWith(ProfileErrorCode.STORE_FORMAT_REFUSED, () -> repository.latestSnapshot(OLDER));
    }

    // ------------------------------------------------------------------ resume / refuse

    @Test
    void anInterruptedMigrationResumesFromItsVerifiedBackup() throws Exception {
        try (FileProfileRepository repository = new FileProfileRepository(store())) {
            final Seed seed = seed(repository, false);
            // A previous run wrote its backup and marker and converted part of the store before it stopped.
            repository.backup(backups().toString());
            repository.writeStoreFormat(StoreFormat.Marker.migrating(CoopFixtures.BUILD));
            assertEquals(1, repository.convertLegacyArchives());
            final UUID epoch = ProfileRepositoryMigration.migrationEpoch(OLDER);
            final ProfileSnapshot first = seed.olderSnapshots().getFirst();
            repository.writeMigratedMemberSnapshot(new ProfileSnapshot(OLDER, seed.player(), epoch, first.snapshotId(),
                first.schemaVersion(), first.revision(), first.state(), first.checksum(), first.createdAtMillis(), null));

            // While IN_PROGRESS nothing but the migration writes.
            final PlayerClaim q = join(PlayerSessionOwnership.local("node-a", 600).claim(seed.player(), UUID.randomUUID())).claim();
            refusedWith(ProfileErrorCode.STORE_FORMAT_REFUSED, () -> repository.commitAccount(
                repository.loadAccount(seed.player(), SLOTS).withSlotLimit(4),
                CoopWriteGuard.alias(op(CoopOperation.Kind.ACCOUNT_LIMIT, seed.player()), q, seed.account().revision())));
            refusedWith(ProfileErrorCode.STORE_FORMAT_REFUSED, () -> repository.saveAccount(PlayerAccount.empty(seed.player(), SLOTS)));
            assertEquals(StoreFormat.Gate.REFUSED, StoreFormat.gate(repository.storeFormat()).gate());

            final Format2Result resumed = ProfileRepositoryMigration.toFormat2(repository, apply(), CoopFixtures.BUILD);
            assertEquals(Format2Result.Outcome.MIGRATED, resumed.outcome(), resumed.summary());
            assertMigrated(repository, seed);
        }
    }

    @Test
    void aResumeWithoutItsBackupIsRefusedAndWritesNothing() throws Exception {
        try (FileProfileRepository repository = new FileProfileRepository(store())) {
            seed(repository, false);
            repository.writeStoreFormat(StoreFormat.Marker.migrating(CoopFixtures.BUILD));
            final Map<String, String> before = tree(store());
            final Format2Result result = ProfileRepositoryMigration.toFormat2(repository, apply(), CoopFixtures.BUILD);
            assertEquals(Format2Result.Outcome.REFUSED, result.outcome(), result.summary());
            assertEquals(before, tree(store()), "a refused resume changed the store");
            assertEquals(StoreFormat.MigrationState.IN_PROGRESS, repository.storeFormat().migration());
        }
        // The engine does not start on an interrupted migration unless asked to resume it.
        try (CoopFixtures.EngineHarness harness = new CoopFixtures.EngineHarness(store(),
            CoopFixtures.settings(ProfileSettings.DEFAULT_TYPES), false)) {
            refusedWith(ProfileErrorCode.STORE_FORMAT_REFUSED, harness::open);
        }
    }

    @Test
    void aMigrationWithAnUnresolvedJournalIsRefusedBeforeAnyWrite() throws Exception {
        try (FileProfileRepository repository = new FileProfileRepository(store())) {
            seed(repository, true);
            final Map<String, String> before = tree(store());
            final Format2Result result = ProfileRepositoryMigration.toFormat2(repository, apply(), CoopFixtures.BUILD);
            assertEquals(Format2Result.Outcome.REFUSED, result.outcome(), result.summary());
            assertFalse(result.refused().isEmpty());
            assertNull(result.backup());
            assertEquals(before, tree(store()), "a refused migration changed the store");
            assertTrue(!Files.exists(backups()) || tree(backups()).isEmpty(), "a refused migration wrote a backup");
            assertTrue(repository.storeFormat().legacyFormat());
        }
        assertThrows(IllegalArgumentException.class, () -> new ProfileSettings.Migration(ProfileSettings.MigrationMode.APPLY, ""),
            "apply without a backup directory is refused by the settings");
    }
}
