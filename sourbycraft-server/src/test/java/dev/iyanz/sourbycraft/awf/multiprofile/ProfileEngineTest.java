package dev.iyanz.sourbycraft.awf.multiprofile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileInfo;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.api.profile.ProfileRequest;
import dev.iyanz.sourbycraft.api.profile.ProfileSwitchResult;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileEngineTest {

    @TempDir
    Path directory;

    private ExecutorService io;
    private FileProfileRepository repository;
    private LocalProfileOwnership ownership;
    private FakePlayers players;
    private FakeWorlds worlds;
    private ProfileEngine engine;
    private UUID player;

    private static final ProfileIsolation ISOLATION = ProfileIsolation.ALL;

    private static final class FakePlayers implements PlayerStatePort {
        private final Map<UUID, ProfileState> states = new HashMap<>();
        private final Set<UUID> online = new HashSet<>();
        private final AtomicBoolean failNextApply = new AtomicBoolean();
        private final AtomicReference<CountDownLatch> captureGate = new AtomicReference<>();
        private final AtomicReference<CountDownLatch> captureEntered = new AtomicReference<>();
        private final List<ProfileState.Location> teleports = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public boolean online(final UUID playerId) {
            return this.online.contains(playerId);
        }

        @Override
        public CompletableFuture<ProfileState> capture(final UUID playerId, final ProfileIsolation isolation) {
            final CountDownLatch gate = this.captureGate.getAndSet(null);
            final CountDownLatch entered = this.captureEntered.getAndSet(null);
            if (entered != null) entered.countDown();
            if (gate != null) {
                try {
                    gate.await(5, TimeUnit.SECONDS);
                } catch (final InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return CompletableFuture.completedFuture(this.states.getOrDefault(playerId, ProfileState.empty()));
        }

        @Override
        public CompletableFuture<Void> apply(final UUID playerId, final ProfileState state,
                                             final ProfileIsolation isolation) {
            if (this.failNextApply.compareAndSet(true, false)) {
                return CompletableFuture.failedFuture(new IllegalStateException("injected apply failure"));
            }
            this.states.put(playerId, state);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> teleport(final UUID playerId, final ProfileState.Location location) {
            this.teleports.add(location);
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class FakeWorlds implements ProfileWorldPort {
        private final Set<String> loaded = new HashSet<>();

        @Override
        public CompletableFuture<Void> ensureReady(final ProfileWorldBinding binding) {
            this.loaded.addAll(binding.worlds().keySet());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> saveAndUnload(final String world) {
            this.loaded.remove(world);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean loaded(final String world) {
            return this.loaded.contains(world);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        this.io = Executors.newFixedThreadPool(2);
        this.repository = new FileProfileRepository(this.directory);
        this.ownership = new LocalProfileOwnership("test-node", 60);
        this.players = new FakePlayers();
        this.worlds = new FakeWorlds();
        this.player = UUID.randomUUID();
        this.players.online.add(this.player);
        final ProfileSettings settings = new ProfileSettings(true, ProfileSettings.Mode.STANDALONE, 3, 4,
            new ProfileSettings.Switching(true, 15_000, true), ISOLATION, new ProfileSettings.Worlds(0),
            new ProfileSettings.Coordination(ProfileSettings.Provider.LOCAL, true, true), "awf/profiles");
        this.engine = new ProfileEngine(settings, this.repository, this.ownership, this.players, this.worlds,
            "test-node", this.io, null, null);
    }

    @AfterEach
    void tearDown() {
        this.engine.close();
        this.io.shutdownNow();
    }

    private ProfileInfo create(final String type) {
        return this.engine.create(ProfileRequest.of(this.player, type)).join();
    }

    /** Unwraps a completion failure to the {@link ProfileOperationException} the engine raised. */
    private static ProfileOperationException failure(final org.junit.jupiter.api.function.Executable call) {
        final Throwable thrown = org.junit.jupiter.api.Assertions.assertThrows(Throwable.class, call);
        Throwable current = thrown;
        while (current != null && !(current instanceof ProfileOperationException)) current = current.getCause();
        assertNotNull(current, "expected a ProfileOperationException, got " + thrown);
        return (ProfileOperationException) current;
    }

    @Test
    void profilesRespectTheSlotLimit() {
        final ProfileSettings limited = new ProfileSettings(true, ProfileSettings.Mode.STANDALONE, 2, 2,
            new ProfileSettings.Switching(true, 15_000, true), ISOLATION, new ProfileSettings.Worlds(0),
            new ProfileSettings.Coordination(ProfileSettings.Provider.LOCAL, true, true), "awf/profiles");
        this.engine.close();
        this.engine = new ProfileEngine(limited, this.repository, this.ownership, this.players, this.worlds,
            "test-node", this.io, null, null);
        create("survival");
        create("skyblock");
        final ProfileOperationException failed = failure(() -> create("oneblock"));
        assertEquals(ProfileErrorCode.SLOT_LIMIT, failed.code());
    }

    @Test
    void theFirstSwitchCommitsAndBecomesActive() throws Exception {
        final ProfileInfo profile = create("skyblock");
        final ProfileSwitchResult result = this.engine.switchProfile(this.player, profile.id()).join();
        assertTrue(result.success(), result.message());
        final PlayerAccount account = this.repository.loadAccount(this.player, 3);
        assertEquals(profile.id(), account.activeProfile());
        assertEquals(profile.id(), this.engine.active(this.player).join().orElseThrow().id());
    }

    @Test
    void switchingSavesTheOldProfileAndAppliesTheTarget() throws Exception {
        final ProfileInfo first = create("survival");
        final ProfileInfo second = create("skyblock");
        assertTrue(this.engine.switchProfile(this.player, first.id()).join().success());
        final ProfileState state = new ProfileState(1, List.of(new ProfileState.Item(0, new byte[] {7})), List.of(),
            List.of(), List.of(), 12, 0.5f, 15.0, 10, 2.0f, List.of(), null, null, Map.of());
        this.players.states.put(this.player, state);
        assertTrue(this.engine.switchProfile(this.player, second.id()).join().success());
        assertNotNull(this.repository.latestSnapshot(first.id()).orElse(null), "the old profile was snapshotted");
        assertEquals(1, this.repository.latestSnapshot(first.id()).orElseThrow().revision());
        assertTrue(this.players.states.get(this.player).inventory().isEmpty(), "the target state was applied");
    }

    @Test
    void aConcurrentSwitchIsRejected() throws Exception {
        final ProfileInfo first = create("survival");
        final ProfileInfo second = create("skyblock");
        final ProfileInfo third = create("oneblock");
        assertTrue(this.engine.switchProfile(this.player, first.id()).join().success());
        final CountDownLatch gate = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);
        this.players.captureGate.set(gate);
        this.players.captureEntered.set(entered);
        final CompletableFuture<ProfileSwitchResult> running = this.engine.switchProfile(this.player, second.id());
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the first switch reached its capture");
        final ProfileSwitchResult duplicate = this.engine.switchProfile(this.player, third.id()).join();
        gate.countDown();
        assertFalse(duplicate.success());
        assertEquals(ProfileErrorCode.SWITCH_IN_PROGRESS, duplicate.errorCode());
        running.join();
    }

    @Test
    void aFailedApplyRollsBackToThePreviousState() throws Exception {
        final ProfileInfo first = create("survival");
        final ProfileInfo second = create("skyblock");
        assertTrue(this.engine.switchProfile(this.player, first.id()).join().success());
        final ProfileState marker = new ProfileState(1, List.of(new ProfileState.Item(0, new byte[] {42})), List.of(),
            List.of(), List.of(), 3, 0.0f, 20.0, 20, 5.0f, List.of(), null, null, Map.of());
        this.players.states.put(this.player, marker);
        this.players.failNextApply.set(true);
        final ProfileSwitchResult failed = this.engine.switchProfile(this.player, second.id()).join();
        assertFalse(failed.success());
        assertEquals(ProfileErrorCode.RECOVERY_REQUIRED, failed.errorCode());
        assertEquals(42, this.players.states.get(this.player).inventory().getFirst().data()[0]);
        assertEquals(first.id(), this.repository.loadAccount(this.player, 3).activeProfile());
    }

    @Test
    void aStaleSnapshotIsRefused() throws Exception {
        final ProfileInfo target = create("skyblock");
        // Advance the profile revision beyond a manually written, older snapshot.
        for (int i = 0; i < 3; i++) {
            final AuroraProfile profile = this.repository.profile(target.id()).orElseThrow();
            this.repository.saveProfile(profile.nextRevision());
        }
        final byte[] encoded = ProfileStateCodec.encode(ProfileState.empty());
        this.repository.saveSnapshot(new ProfileSnapshot(target.id(), UUID.randomUUID(), ProfileState.SCHEMA, 1L,
            encoded, ProfileStateCodec.checksum(encoded), System.currentTimeMillis()), 3);
        final ProfileSwitchResult failed = this.engine.switchProfile(this.player, target.id()).join();
        assertFalse(failed.success());
        assertEquals(ProfileErrorCode.STALE_STATE, failed.errorCode(), failed.message());
    }

    @Test
    void recoveryRollsForwardWhenTheAccountCommitted() throws Exception {
        final ProfileInfo target = create("skyblock");
        final long deadline = System.currentTimeMillis() + 10_000L;
        final ProfileTransition journal = ProfileTransition.started(this.player, null, target.id(), deadline, 0L)
            .at(TransitionPhase.COMMITTING);
        this.repository.saveTransition(journal);
        this.repository.saveAccount(this.repository.loadAccount(this.player, 3).withActive(target.id()));
        final ProfileSwitchResult recovered = this.engine.recover(journal.transitionId()).join();
        assertTrue(recovered.success(), recovered.message());
        assertTrue(this.repository.transition(journal.transitionId()).isEmpty());
    }

    @Test
    void recoveryRollsBackToTheDurableSnapshot() throws Exception {
        final ProfileInfo previous = create("survival");
        final ProfileInfo target = create("skyblock");
        assertTrue(this.engine.switchProfile(this.player, previous.id()).join().success());
        final byte[] markerBytes = ProfileStateCodec.encode(new ProfileState(1,
            List.of(new ProfileState.Item(0, new byte[] {99})), List.of(), List.of(), List.of(), 0, 0.0f, 20.0,
            20, 5.0f, List.of(), null, null, Map.of()));
        this.repository.saveSnapshot(new ProfileSnapshot(previous.id(), UUID.randomUUID(), ProfileState.SCHEMA,
            1L, markerBytes, ProfileStateCodec.checksum(markerBytes), System.currentTimeMillis()), 3);
        final ProfileSnapshot snapshot = this.repository.latestSnapshot(previous.id()).orElseThrow();
        final ProfileTransition journal = ProfileTransition.started(this.player, previous.id(), target.id(),
            System.currentTimeMillis() + 10_000L, 0L).at(TransitionPhase.APPLYING_TARGET)
            .withFromSnapshot(snapshot.snapshotId());
        this.repository.saveTransition(journal);
        this.players.states.put(this.player, ProfileState.empty());
        final ProfileSwitchResult recovered = this.engine.recover(journal.transitionId()).join();
        assertFalse(recovered.success());
        assertEquals(ProfileErrorCode.RECOVERED, recovered.errorCode());
        assertEquals(previous.id(), this.repository.loadAccount(this.player, 3).activeProfile());
        assertEquals(99, this.players.states.get(this.player).inventory().getFirst().data()[0],
            "the durable snapshot was applied during rollback");
        assertTrue(this.repository.transition(journal.transitionId()).isEmpty());
    }

    @Test
    void switchingToTheAlreadyActiveProfileIsRefused() {
        final ProfileInfo profile = create("skyblock");
        assertTrue(this.engine.switchProfile(this.player, profile.id()).join().success());
        final ProfileSwitchResult again = this.engine.switchProfile(this.player, profile.id()).join();
        assertFalse(again.success());
        assertEquals(ProfileErrorCode.INVALID_REQUEST, again.errorCode());
        assertTrue(this.engine.ownership().holder(profile.id()).join() != null,
            "the active profile keeps its claim");
    }

    @Test
    void recoveryRefusesAJournalOlderThanTheCurrentFence() throws Exception {
        final ProfileInfo target = create("skyblock");
        final ProfileTransition journal = ProfileTransition.started(this.player, null, target.id(),
            System.currentTimeMillis() + 10_000L, 99L).at(TransitionPhase.SAVING);
        this.repository.saveTransition(journal);
        this.repository.saveAccount(this.repository.loadAccount(this.player, 3).withActive(target.id()));
        final ProfileSwitchResult refused = this.engine.recover(journal.transitionId()).join();
        assertFalse(refused.success());
        assertEquals(ProfileErrorCode.OWNERSHIP_CONFLICT, refused.errorCode(), refused.message());
        assertTrue(this.repository.transition(journal.transitionId()).isPresent(), "the journal is kept");
    }

    @Test
    void deletingTheActiveProfileIsRefused() {
        final ProfileInfo profile = create("skyblock");
        assertTrue(this.engine.switchProfile(this.player, profile.id()).join().success());
        final ProfileOperationException failed = failure(() -> this.engine.delete(profile.id()).join());
        assertEquals(ProfileErrorCode.PROFILE_LOCKED, failed.code());
    }
    @Test
    void concurrentCreatesKeepEverySuccessfulProfileAndRespectSlots() throws Exception {
        final List<CompletableFuture<ProfileInfo>> requests = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            requests.add(this.engine.create(ProfileRequest.of(this.player, "skyblock"))
                .exceptionally(failed -> null));
        }
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        final Set<UUID> successful = new HashSet<>();
        for (final var request : requests) {
            if (request.join() != null) successful.add(request.join().id());
        }
        assertEquals(3, successful.size());
        assertEquals(successful, new HashSet<>(this.repository.loadAccount(this.player, 3).profileIds()));
    }

    @Test
    void publicBindingPersistsAndRefusesActiveProfileMutation() {
        final ProfileInfo profile = create("skyblock");
        final var request = new dev.iyanz.sourbycraft.api.profile.ProfileWorldBindingRequest(profile.id(),
            Map.of("island_test", dev.iyanz.sourbycraft.api.profile.ProfileWorldRole.PRIMARY), "template_test");
        this.engine.bindWorlds(request).join();
        assertEquals("island_test", this.engine.worlds(profile.id()).join().getFirst().world());
        assertTrue(this.engine.switchProfile(this.player, profile.id()).join().success());
        assertEquals(ProfileErrorCode.PROFILE_LOCKED, failure(() -> this.engine.bindWorlds(request).join()).code());
    }

}
