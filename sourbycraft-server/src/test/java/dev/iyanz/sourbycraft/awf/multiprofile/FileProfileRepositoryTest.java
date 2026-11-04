package dev.iyanz.sourbycraft.awf.multiprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileProfileRepositoryTest {

    @TempDir
    Path directory;

    private FileProfileRepository repository;

    @BeforeEach
    void setUp() throws IOException {
        this.repository = new FileProfileRepository(this.directory);
    }

    @AfterEach
    void tearDown() {
        this.repository.close();
    }

    private static ProfileSnapshot snapshot(final UUID profileId, final long revision, final ProfileState state) {
        final byte[] encoded = ProfileStateCodec.encode(state);
        return new ProfileSnapshot(profileId, UUID.randomUUID(), ProfileState.SCHEMA, revision, encoded,
            ProfileStateCodec.checksum(encoded), System.currentTimeMillis());
    }

    @Test
    void accountsProfilesAndBindingsRoundTrip() throws IOException {
        final UUID player = UUID.randomUUID();
        final PlayerAccount empty = this.repository.loadAccount(player, 5);
        assertEquals(5, empty.slotLimit());
        assertEquals(0, empty.revision());

        final AuroraProfile profile = new AuroraProfile(UUID.randomUUID(), player, "skyblock",
            Map.of("name", "Island"), 123L, 0L, ProfileStatus.ACTIVE);
        this.repository.saveProfile(profile);
        final PlayerAccount account = empty.withProfile(profile.id()).withActive(profile.id());
        this.repository.saveAccount(account);
        this.repository.saveBinding(new ProfileWorldBinding(profile.id(),
            Map.of("island-1", ProfileWorldRole.PRIMARY), "island-template"));

        final PlayerAccount loaded = this.repository.loadAccount(player, 5);
        assertEquals(account.profileIds(), loaded.profileIds());
        assertEquals(account.activeProfile(), loaded.activeProfile());
        assertEquals(2L, loaded.revision());
        final AuroraProfile found = this.repository.profile(profile.id()).orElseThrow();
        assertEquals("skyblock", found.type());
        assertEquals("Island", found.metadata().get("name"));
        final ProfileWorldBinding binding = this.repository.binding(profile.id()).orElseThrow();
        assertEquals(ProfileWorldRole.PRIMARY, binding.worlds().get("island-1"));
        assertEquals("island-template", binding.template());
        assertEquals(1, this.repository.profiles(player).size());
    }

    @Test
    void theLatestSnapshotWinsAndOldOnesArePruned() throws IOException {
        final UUID profile = UUID.randomUUID();
        for (int revision = 1; revision <= 5; revision++) {
            this.repository.saveSnapshot(snapshot(profile, revision, ProfileState.empty()), 3);
        }
        final ProfileSnapshot latest = this.repository.latestSnapshot(profile).orElseThrow();
        assertEquals(5L, latest.revision());
        assertTrue(this.repository.snapshot(profile, 5L).isPresent());
        assertTrue(this.repository.snapshot(profile, 1L).isEmpty(), "revision 1 was pruned");
        try (var files = Files.list(this.directory.resolve("snapshots").resolve(profile.toString()))) {
            assertEquals(3, files.count());
        }
    }

    @Test
    void aCorruptedSnapshotIsReportedNotReturned() throws IOException {
        final UUID profile = UUID.randomUUID();
        final ProfileSnapshot written = snapshot(profile, 1L, ProfileState.empty());
        this.repository.saveSnapshot(written, 3);
        final Path file;
        try (var files = Files.list(this.directory.resolve("snapshots").resolve(profile.toString()))) {
            file = files.findFirst().orElseThrow();
        }
        final byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 40] ^= 0x55;
        Files.write(file, bytes);
        final IOException failed = assertThrows(IOException.class, () -> this.repository.latestSnapshot(profile));
        assertTrue(failed.getMessage().contains("checksum"));
    }

    @Test
    void transitionJournalsRoundTripAndAreListed() throws IOException {
        final ProfileTransition journal = ProfileTransition.started(UUID.randomUUID(), UUID.randomUUID(),
            UUID.randomUUID(), System.currentTimeMillis() + 10_000L, 7L).at(TransitionPhase.SAVING);
        this.repository.saveTransition(journal);
        final ProfileTransition loaded = this.repository.transition(journal.transitionId()).orElseThrow();
        assertEquals(TransitionPhase.SAVING, loaded.phase());
        assertEquals(7L, loaded.fence());
        assertEquals(1, this.repository.transitions().size());
        this.repository.deleteTransition(journal.transitionId());
        assertTrue(this.repository.transition(journal.transitionId()).isEmpty());
    }

    @Test
    void deletingAProfileKeepsATombstoneAndRemovesData() throws IOException {
        final UUID profileId = UUID.randomUUID();
        this.repository.saveProfile(new AuroraProfile(profileId, UUID.randomUUID(), "skyblock", Map.of(),
            System.currentTimeMillis(), 0L, ProfileStatus.ACTIVE));
        this.repository.saveSnapshot(snapshot(profileId, 1L, ProfileState.empty()), 3);
        this.repository.saveBinding(ProfileWorldBinding.empty(profileId));
        this.repository.deleteProfileData(profileId);
        assertNotNull(this.repository.profile(profileId).orElseThrow());
        assertTrue(this.repository.latestSnapshot(profileId).isEmpty());
        assertTrue(this.repository.binding(profileId).isEmpty());
        this.repository.deleteProfile(profileId);
        assertTrue(this.repository.profile(profileId).isEmpty());
    }

    @Test
    void anAtomicRewriteLeavesNoTemporaryFile() throws IOException {
        final UUID player = UUID.randomUUID();
        this.repository.saveAccount(PlayerAccount.empty(player, 3));
        this.repository.saveAccount(PlayerAccount.empty(player, 3).withProfile(UUID.randomUUID()));
        try (var files = Files.list(this.directory.resolve("accounts"))) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }
}
