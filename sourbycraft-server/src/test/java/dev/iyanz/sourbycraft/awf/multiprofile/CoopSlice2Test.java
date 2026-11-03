package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.CLASSIC;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.SLOTS;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.committed;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.join;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.op;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusedWith;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.tree;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.AuroraProfiles;
import dev.iyanz.sourbycraft.api.profile.ProfileDetails;
import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileInfo;
import dev.iyanz.sourbycraft.api.profile.ProfileRequest;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ClaimKind;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.Expected;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ProfileClaim;
import dev.iyanz.sourbycraft.awf.redis.fabric.OwnerClaim;
import java.lang.reflect.RecordComponent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Owner cases of COOP-DESIGN §10 slice 2: revision domains, typed claims, strict same-node claims, claim loss,
 * counter exhaustion and the binary compatibility of the public profile view. Cross-node cases that need a
 * shared claim store are in {@link CoopRedisTest}.
 */
class CoopSlice2Test {

    @TempDir
    Path directory;

    // ------------------------------------------------------------------ CoopRevisionDomainsIndependent

    /**
     * A, M, S and R advance only for their own mutations: a member save moves R only, a metadata change M only,
     * an alias rename A only, a shared mutation S only.
     */
    @Test
    void CoopRevisionDomainsIndependent() throws Exception {
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(this.directory)) {
            final FileProfileRepository repository = store.repository;
            final UUID player = UUID.randomUUID();
            final PlayerClaim q = store.q(player);
            final AuroraProfile created = CoopFixtures.create(repository, q, "Apple", CLASSIC);
            final UUID profileId = created.id();
            final UUID epoch = created.owner().epoch();
            CoopFixtures.activate(repository, q, created);

            final long a0 = repository.loadAccount(player, SLOTS).revision();
            final long m0 = repository.profile(profileId).orElseThrow().revision();
            assertTrue(repository.memberHead(profileId, player, epoch).isEmpty(), "a new membership has no head yet");
            assertTrue(repository.shared(profileId).isEmpty(), "a new profile has no shared state yet");

            // Member save: R only.
            final MemberStateHead h1 = CoopFixtures.save(repository, q, profileId);
            assertEquals(1L, h1.revision());
            assertEquals(MemberStateHead.State.READY, h1.state());
            assertEquals(a0, repository.loadAccount(player, SLOTS).revision(), "a member save does not advance A");
            assertEquals(m0, repository.profile(profileId).orElseThrow().revision(), "a member save does not advance M");
            final MemberStateHead h2 = CoopFixtures.save(repository, q, profileId);
            assertEquals(2L, h2.revision(), "each publish advances R exactly once");

            // Metadata change: M only.
            final ProfileClaim p = store.p(profileId);
            final AuroraProfile stored = repository.profile(profileId).orElseThrow();
            final AuroraProfile locked = stored.withStatus(ProfileStatus.LOCKED);
            committed(repository.commitProfile(locked, CoopWriteGuard.metadata(op(CoopOperation.Kind.SET_STATUS, player), p, q,
                a0, m0)));
            assertEquals(m0 + 1L, repository.profile(profileId).orElseThrow().revision());
            assertEquals(a0, repository.loadAccount(player, SLOTS).revision(), "a metadata change does not advance A");
            assertEquals(2L, repository.memberHead(profileId, player, epoch).orElseThrow().revision(),
                "a metadata change does not advance R");

            // Alias rename: A only.
            final PlayerAccount account = repository.loadAccount(player, SLOTS);
            committed(repository.commitAccount(account.withAlias(profileId, "orchard"),
                CoopWriteGuard.alias(op(CoopOperation.Kind.RENAME_ALIAS, player), q, a0)));
            assertEquals(a0 + 1L, repository.loadAccount(player, SLOTS).revision());
            assertEquals("orchard", repository.loadAccount(player, SLOTS).alias(profileId));
            assertEquals(m0 + 1L, repository.profile(profileId).orElseThrow().revision(), "an alias rename does not advance M");
            assertEquals("Apple", repository.profile(profileId).orElseThrow().name(), "an alias rename does not rename the profile");
            assertEquals(2L, repository.memberHead(profileId, player, epoch).orElseThrow().revision());

            // Shared mutation: S only. Unlock first (M + 1), the shared write itself must not move M.
            final AuroraProfile unlocked = repository.profile(profileId).orElseThrow().withStatus(ProfileStatus.ACTIVE);
            committed(repository.commitProfile(unlocked, CoopWriteGuard.metadata(op(CoopOperation.Kind.SET_STATUS, player), p, q,
                a0 + 1L, m0 + 1L)));
            final long a1 = repository.loadAccount(player, SLOTS).revision();
            final long m1 = repository.profile(profileId).orElseThrow().revision();
            final ProfileSharedState shared = ProfileSharedState.empty(profileId,
                new ProfileSharedState.Currency("test", "coins", 0), 0L);
            committed(repository.mutateShared(shared, CoopWriteGuard.shared(op(CoopOperation.Kind.SHARED_MUTATE, player), p, q,
                a1, m1, CoopWriteGuard.ABSENT)));
            assertEquals(0L, repository.shared(profileId).orElseThrow().revision(), "a new shared record starts at 0");
            assertEquals(a1, repository.loadAccount(player, SLOTS).revision(), "a shared mutation does not advance A");
            assertEquals(m1, repository.profile(profileId).orElseThrow().revision(), "a shared mutation does not advance M");
            assertEquals(2L, repository.memberHead(profileId, player, epoch).orElseThrow().revision(),
                "a shared mutation does not advance R");

            // A refused write advances nothing.
            final Map<String, String> before = tree(this.directory);
            final PlayerAccount current = repository.loadAccount(player, SLOTS);
            refusedWith(ProfileErrorCode.STALE_STATE, () -> repository.commitAccount(current.withSlotLimit(4),
                CoopWriteGuard.alias(op(CoopOperation.Kind.ACCOUNT_LIMIT, player), q, a0)));
            assertEquals(before, tree(this.directory), "a stale write changed the store");
        }
    }

    // ------------------------------------------------------------------ CoopClaimKindsCannotAlias

    @Test
    void CoopClaimKindsCannotAlias() throws Exception {
        final UUID id = UUID.randomUUID();
        final OwnerClaim playerKind = new OwnerClaim(ClaimKind.PLAYER.kind(), id.toString(), "node-a",
            UUID.randomUUID().toString(), 1L, 1L, 0L);
        final OwnerClaim profileKind = new OwnerClaim(ClaimKind.PROFILE.kind(), id.toString(), "node-a",
            UUID.randomUUID().toString(), 1L, 1L, 0L);
        final OwnerClaim soloKind = new OwnerClaim("profile", id.toString(), "node-a", UUID.randomUUID().toString(), 1L, 1L, 0L);
        assertThrows(IllegalArgumentException.class, () -> ProfileClaim.of(playerKind, "i"), "a Q cannot pose as a P");
        assertThrows(IllegalArgumentException.class, () -> PlayerClaim.of(profileKind, "i"), "a P cannot pose as a Q");
        assertThrows(IllegalArgumentException.class, () -> ProfileClaim.of(soloKind, "i"), "a solo session claim is not a P");
        assertThrows(IllegalArgumentException.class, () -> PlayerClaim.of(soloKind, "i"), "a solo session claim is not a Q");
        assertNotEquals(ClaimKind.PLAYER.kind(), ClaimKind.PROFILE.kind());

        // Disjoint local tables: P and Q on the same UUID are both granted and independent.
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(this.directory)) {
            final ProfileClaim p = store.p(id);
            final PlayerClaim q = store.q(id);
            assertTrue(join(store.profiles.validateTransaction(p)));
            assertTrue(join(store.players.validate(q)));
            assertTrue(join(store.players.release(q)));
            assertTrue(join(store.profiles.validateTransaction(p)), "releasing Q does not release the P of the same id");

            // A claim on another resource never authorises a write: Q of another player, P of another profile.
            final FileProfileRepository repository = store.repository;
            final UUID player = UUID.randomUUID();
            final PlayerClaim own = store.q(player);
            final AuroraProfile profile = CoopFixtures.create(repository, own, "Banana", CLASSIC);
            final long a = repository.loadAccount(player, SLOTS).revision();
            final PlayerClaim stranger = store.q(UUID.randomUUID());
            final Map<String, String> before = tree(this.directory);
            final PlayerAccount account = repository.loadAccount(player, SLOTS);
            refusedWith(ProfileErrorCode.OWNERSHIP_CONFLICT, () -> repository.commitAccount(account.withSlotLimit(4),
                new CoopWriteGuard(op(CoopOperation.Kind.ACCOUNT_LIMIT, stranger.playerId()), null, stranger, null,
                    List.of(Expected.account(player, a)), null, Set.of())));
            final ProfileClaim otherProfile = store.p(UUID.randomUUID());
            final AuroraProfile locked = profile.withStatus(ProfileStatus.LOCKED);
            refusedWith(ProfileErrorCode.OWNERSHIP_CONFLICT, () -> repository.commitProfile(locked,
                new CoopWriteGuard(op(CoopOperation.Kind.SET_STATUS, player), otherProfile, own, null,
                    List.of(Expected.account(player, a), Expected.metadata(profile.id(), profile.revision())), null, Set.of())));
            assertEquals(before, tree(this.directory), "a write under a claim on another resource changed the store");
        }
    }

    // ------------------------------------------------------------------ CoopSameNodeMembersDoNotSupersede

    @Test
    void CoopSameNodeMembersDoNotSupersede() {
        final PlayerSessionOwnership players = PlayerSessionOwnership.local("node-a", 600);
        final UUID player = UUID.randomUUID();
        final UUID first = UUID.randomUUID();
        final PlayerSessionOwnership.Grant granted = join(players.claim(player, first));
        assertTrue(granted.granted());
        final PlayerClaim q = granted.claim();

        // Same node, same process, another token: refused while live (no same-node supersession).
        final PlayerSessionOwnership.Grant second = join(players.claim(player, UUID.randomUUID()));
        assertFalse(second.granted());
        assertEquals("held", second.reason());
        assertEquals(q.ownerToken(), second.holder().ownerToken());
        assertTrue(join(players.validate(q)), "the first session keeps its claim");

        // The exact same token renews, same fence.
        final PlayerSessionOwnership.Grant renewed = join(players.claim(player, first));
        assertTrue(renewed.granted());
        assertEquals(q.fence(), renewed.claim().fence());

        // Members of one profile hold distinct Q claims at once.
        final UUID member = UUID.randomUUID();
        final PlayerSessionOwnership.Grant other = join(players.claim(member, UUID.randomUUID()));
        assertTrue(other.granted(), "another member's session is independent");
        assertTrue(join(players.validate(q)));

        // P is strict as well, on the same node.
        final LocalProfileOwnership profiles = new LocalProfileOwnership("node-a", 600);
        final UUID profile = UUID.randomUUID();
        final ProfileOwnership.TransactionGrant p = join(profiles.claimTransaction(profile, UUID.randomUUID()));
        assertTrue(p.granted());
        final ProfileOwnership.TransactionGrant p2 = join(profiles.claimTransaction(profile, UUID.randomUUID()));
        assertFalse(p2.granted(), "a second transaction claim on one profile is refused, also on the same node");
        assertEquals("held", p2.reason());
        assertTrue(join(profiles.validateTransaction(p.claim())));

        // The solo session table keeps its documented same-node supersession, and never touches P.
        assertTrue(join(profiles.claim(profile, "node-a", UUID.randomUUID())).granted());
        assertTrue(join(profiles.validateTransaction(p.claim())), "a solo session claim does not supersede P");

        // After release a new token gets a higher fence; the released claim never validates again.
        assertTrue(join(players.release(q)));
        final PlayerSessionOwnership.Grant next = join(players.claim(player, UUID.randomUUID()));
        assertTrue(next.granted());
        assertTrue(next.claim().fence() > q.fence(), "fences never restart");
        assertFalse(join(players.validate(q)));
    }

    // ------------------------------------------------------------------ CoopLeaseLossCancelsCapture

    /**
     * A capture ticket minted under a session claim that is then lost (released and taken by another session)
     * never publishes: the engine reports the loss, freezes the player through its handler and refuses the save;
     * the stream head does not move.
     */
    @Test
    void CoopLeaseLossCancelsCapture() throws Exception {
        try (CoopFixtures.EngineHarness harness = new CoopFixtures.EngineHarness(this.directory,
            CoopFixtures.settings(ProfileSettings.DEFAULT_TYPES), true)) {
            assertEquals(StoreFormat.Gate.FORMAT_2, harness.open().gate());
            final ProfileEngine engine = harness.engine;
            final UUID player = UUID.randomUUID();
            harness.ports.online.add(player);
            final ProfileDetails created = join(engine.createDetailed(ProfileRequest.of(player, "skyblock")));
            final UUID profileId = created.info().id();
            final AuroraProfile profile = harness.repository.profile(profileId).orElseThrow();
            final UUID epoch = profile.owner().epoch();

            // Make the profile active under a short administrative Q, released before the session is admitted.
            final PlayerSessionOwnership.Grant admin = join(harness.players.claim(player, UUID.randomUUID()));
            assertTrue(admin.granted());
            CoopFixtures.activate(harness.repository, admin.claim(), profile);
            assertTrue(join(harness.players.release(admin.claim())));

            final ProfileEngine.Admission admission = join(engine.admitSession(player));
            assertTrue(admission.admitted(), admission.reason());
            final PlayerClaim session = engine.playerSessionClaim(player);
            assertNotNull(session);

            // Control: a capture under the live session publishes R = 1.
            final ProfileEngine.CaptureTicket ticket = engine.captureTicket(player);
            assertEquals(profileId, ticket.profileId());
            assertTrue(join(engine.saveCaptured(player, ProfileState.empty(), ticket)));
            assertEquals(1L, harness.repository.memberHead(profileId, player, epoch).orElseThrow().revision());

            final CountDownLatch lost = new CountDownLatch(1);
            engine.onSessionLost(lostPlayer -> {
                if (lostPlayer.equals(player)) lost.countDown();
            });
            final ProfileEngine.CaptureTicket stale = engine.captureTicket(player);

            // Confirmed loss: another session of the player now holds Q with a higher fence.
            assertTrue(join(harness.players.release(session)));
            final PlayerSessionOwnership.Grant thief = join(harness.players.claim(player, UUID.randomUUID()));
            assertTrue(thief.granted());
            assertTrue(thief.claim().fence() > session.fence());

            final ProfileErrorCode code = CoopFixtures.refusal(() -> join(engine.saveCaptured(player, ProfileState.empty(), stale)))
                .code();
            assertTrue(code == ProfileErrorCode.CLAIM_LOST || code == ProfileErrorCode.OWNERSHIP_CONFLICT, "refused with " + code);
            assertTrue(lost.await(CoopFixtures.WAIT_SECONDS, TimeUnit.SECONDS), "the engine did not report the lost session");
            assertEquals(1L, harness.repository.memberHead(profileId, player, epoch).orElseThrow().revision(),
                "a capture under a lost claim was published");
            assertNull(engine.playerSessionClaim(player), "a lost session is not kept as admitted");
            // No re-acquire-and-publish: the same ticket stays refused.
            CoopFixtures.refusal(() -> join(engine.saveCaptured(player, ProfileState.empty(), stale)));
            assertEquals(1L, harness.repository.memberHead(profileId, player, epoch).orElseThrow().revision());
        }
    }

    private static void assertNull(final Object value, final String message) {
        org.junit.jupiter.api.Assertions.assertNull(value, message);
    }

    // ------------------------------------------------------------------ CoopCounterExhaustionRefused

    @Test
    void CoopCounterExhaustionRefused() throws Exception {
        final long max = CoopWriteGuard.MAX_COUNTER;
        assertEquals((1L << 53) - 1L, max);
        assertEquals(max, CoopWriteGuard.next(max - 1L, "test"));
        refusedWith(ProfileErrorCode.COUNTER_EXHAUSTED, () -> CoopWriteGuard.next(max, "test"));
        assertThrows(IllegalArgumentException.class, () -> CoopWriteGuard.counter(max + 1L, "test"));
        assertThrows(IllegalArgumentException.class, () -> Expected.account(UUID.randomUUID(), max + 1L));

        // Every record mutation that would pass the cap refuses; nothing wraps or resets.
        final UUID player = UUID.randomUUID();
        final PlayerAccount account = PlayerAccount.empty(player, SLOTS).atRevision(max);
        refusedWith(ProfileErrorCode.COUNTER_EXHAUSTED, () -> account.withProfile(UUID.randomUUID()));
        refusedWith(ProfileErrorCode.COUNTER_EXHAUSTED, () -> account.withSlotLimit(4));
        final PlayerAccount generation = new PlayerAccount(player, List.of(), Map.of(), null, null, SLOTS, 0L, max, null,
            List.of(), false);
        refusedWith(ProfileErrorCode.COUNTER_EXHAUSTED, generation::nextSession);
        final AuroraProfile profile = AuroraProfile.create(player, "skyblock", CLASSIC, "Apple", Map.of(), 1L).atRevision(max);
        refusedWith(ProfileErrorCode.COUNTER_EXHAUSTED, () -> profile.withStatus(ProfileStatus.LOCKED));
        refusedWith(ProfileErrorCode.COUNTER_EXHAUSTED, () -> profile.withPendingIntent(UUID.randomUUID()));
        final MemberStateHead head = new MemberStateHead(profile.id(), player, UUID.randomUUID(), max,
            MemberStateHead.State.UNINITIALIZED, null, null, null, null);
        refusedWith(ProfileErrorCode.COUNTER_EXHAUSTED, () -> head.withPendingIntent(UUID.randomUUID()));
        refusedWith(ProfileErrorCode.COUNTER_EXHAUSTED, () -> head.consumed(UUID.randomUUID()));
        final ProfileIndexPage page = new ProfileIndexPage(ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL, null,
            null, 1L, 0, max, List.of());
        refusedWith(ProfileErrorCode.COUNTER_EXHAUSTED, () -> page.with(new ProfileIndexPage.Entry(1L, UUID.randomUUID())));

        // A stored record at the cap: the store refuses the write (it cannot even be expressed) and keeps the record.
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(this.directory)) {
            final PlayerClaim q = store.q(player);
            final java.nio.file.Path file = this.directory.resolve("accounts").resolve(player + ".awfp");
            java.nio.file.Files.createDirectories(file.getParent());
            FileProfileIo.writeDurable(file, ProfileRecords.frame2(ProfileRecords.TYPE_ACCOUNT, max,
                ProfileRecords.accountPayload2(account), file.toString()));
            final PlayerAccount stored = store.repository.loadAccount(player, SLOTS);
            assertEquals(max, stored.revision());
            final Map<String, String> before = tree(this.directory);
            refusedWith(ProfileErrorCode.COUNTER_EXHAUSTED, () -> store.repository.commitAccount(stored.withSlotLimit(4),
                CoopWriteGuard.alias(op(CoopOperation.Kind.ACCOUNT_LIMIT, player), q, max)));
            refusedWith(ProfileErrorCode.COUNTER_EXHAUSTED, () -> CoopFixtures.prepareCreate(store.repository, q,
                CoopFixtures.planCreate(store.repository, player, "Apple", CLASSIC)));
            assertEquals(before, tree(this.directory), "an exhausted counter changed the store");
        }
    }

    // ------------------------------------------------------------------ ProfileInfoBinaryCompatibility

    /**
     * {@link ProfileInfo} keeps its Build 50 shape exactly (components, order, canonical constructor), the old
     * {@link ProfileRequest} constructor and factory remain, the error codes of Build 50 keep their ordinals, and
     * name and type are only reachable through the additive {@link ProfileDetails} view.
     */
    @Test
    void ProfileInfoBinaryCompatibility() throws Exception {
        final RecordComponent[] components = ProfileInfo.class.getRecordComponents();
        final List<String> names = java.util.Arrays.stream(components).map(RecordComponent::getName).toList();
        assertEquals(List.of("id", "accountId", "type", "metadata", "createdAtMillis", "revision", "status", "active", "worlds"),
            names, "ProfileInfo components changed");
        final List<Class<?>> types = java.util.Arrays.stream(components).<Class<?>>map(RecordComponent::getType).toList();
        assertEquals(List.of(UUID.class, UUID.class, String.class, Map.class, long.class, long.class, String.class, boolean.class,
            List.class), types);
        assertNotNull(ProfileInfo.class.getConstructor(UUID.class, UUID.class, String.class, Map.class, long.class, long.class,
            String.class, boolean.class, List.class));
        assertNotNull(ProfileInfo.class.getMethod("usable"));

        assertNotNull(ProfileRequest.class.getConstructor(UUID.class, String.class, Map.class), "the Build 50 constructor");
        assertNotNull(ProfileRequest.class.getMethod("of", UUID.class, String.class));
        final UUID account = UUID.randomUUID();
        final ProfileRequest legacy = new ProfileRequest(account, "skyblock", Map.of("k", "v"));
        assertEquals(account, legacy.accountId());
        assertEquals("skyblock", legacy.type());
        org.junit.jupiter.api.Assertions.assertNull(legacy.profileType(), "an old request names no policy type");
        org.junit.jupiter.api.Assertions.assertNull(legacy.name(), "an old request names no name");
        assertEquals(CoopFixtures.IRONMAN, legacy.withProfileType(CoopFixtures.IRONMAN).profileType());
        assertEquals("Apple", legacy.withName("Apple").name());

        final List<String> build50 = List.of("DISABLED", "SWITCHING_DISABLED", "PLAYER_OFFLINE", "PROFILE_NOT_FOUND", "NOT_OWNED",
            "SLOT_LIMIT", "PROFILE_LOCKED", "SWITCH_IN_PROGRESS", "STALE_STATE", "OWNERSHIP_CONFLICT", "WORLD_UNAVAILABLE",
            "TIMEOUT", "RECOVERY_REQUIRED", "RECOVERED", "IO_FAILURE", "INVALID_REQUEST", "STATE_TOO_LARGE", "INTERNAL_ERROR");
        for (int i = 0; i < build50.size(); i++) {
            assertEquals(i, ProfileErrorCode.valueOf(build50.get(i)).ordinal(), build50.get(i) + " moved");
        }
        for (final String added : List.of("NAME_TAKEN", "NAME_INVALID", "TYPE_NOT_ALLOWED", "STORE_FORMAT_REFUSED",
            "MIGRATION_REQUIRED")) {
            assertTrue(ProfileErrorCode.valueOf(added).ordinal() >= build50.size(), added + " was not appended");
        }

        // The additive view and its accessors on the service interface are default methods (old providers still link).
        assertTrue(AuroraProfiles.class.getMethod("getProfileDetails", UUID.class).isDefault());
        assertTrue(AuroraProfiles.class.getMethod("listProfileDetails", UUID.class).isDefault());
        final ProfileInfo info = new ProfileInfo(UUID.randomUUID(), account, "skyblock", Map.of(), 1L, 0L, "ACTIVE", false, List.of());
        final ProfileDetails details = new ProfileDetails(info, "Apple", CoopFixtures.BINGO);
        assertEquals(info, details.info());
        assertEquals("Apple", details.name());
        assertEquals(CoopFixtures.BINGO, details.profileType());
        for (final RecordComponent component : components) {
            assertFalse(component.getName().equals("name") || component.getName().equals("profileType"),
                "name and type must not be added to ProfileInfo");
        }

        // The engine fills the view from the stored record without changing the ProfileInfo it returns.
        try (CoopFixtures.EngineHarness harness = new CoopFixtures.EngineHarness(this.directory,
            CoopFixtures.settings(ProfileSettings.DEFAULT_TYPES), true)) {
            harness.open();
            final ProfileDetails made = join(harness.engine.createDetailed(
                ProfileRequest.of(account, "skyblock", CoopFixtures.STRANDED).withName("Kiwi")));
            final ProfileInfo plain = join(harness.engine.create(ProfileRequest.of(account, "skyblock")));
            assertEquals("Kiwi", made.name());
            assertEquals(CoopFixtures.STRANDED, made.profileType());
            final Optional<ProfileDetails> read = join(harness.engine.getDetails(made.info().id()));
            assertEquals(made.info().id(), read.orElseThrow().info().id());
            assertEquals(join(harness.engine.get(plain.id())).orElseThrow(), plain);
            final CompletableFuture<List<ProfileInfo>> listed = harness.engine.list(account);
            assertEquals(2, join(listed).size());
        }
    }

    @Test
    void theRequestDigestBindsEveryFieldInOrder() {
        final UUID id = UUID.randomUUID();
        assertArrayEquals(CoopOperation.digest("a", id, 1L), CoopOperation.digest("a", id, 1L));
        assertFalse(java.util.Arrays.equals(CoopOperation.digest("ab", "c"), CoopOperation.digest("a", "bc")));
        assertFalse(java.util.Arrays.equals(CoopOperation.digest((Object) null), CoopOperation.digest("")));
        final CoopOperation op = op(CoopOperation.Kind.CREATE_PROFILE, id);
        assertTrue(op.sameRequest(op.requestDigest()));
        assertFalse(op.sameRequest(CoopOperation.digest("other")));
    }
}
