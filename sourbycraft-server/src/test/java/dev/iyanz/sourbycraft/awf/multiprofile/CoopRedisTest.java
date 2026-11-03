package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.CLASSIC;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.SLOTS;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.committed;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.join;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.op;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusal;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusedWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ProfileClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileRepository.CommitResult;
import dev.iyanz.sourbycraft.awf.redis.RedisClient;
import dev.iyanz.sourbycraft.awf.redis.RedisSettings;
import dev.iyanz.sourbycraft.awf.redis.RedisTestServer;
import dev.iyanz.sourbycraft.awf.redis.fabric.CoopClaimFabric;
import dev.iyanz.sourbycraft.awf.redis.fabric.FabricSettings;
import dev.iyanz.sourbycraft.awf.redis.fabric.RedisFabric;
import dev.iyanz.sourbycraft.awf.redis.fabric.RedisWorkload;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Owner cases of COOP-DESIGN §10 slices 2 and 3 that need the shared Redis store: cross-node claims, Redis FENCED
 * and STALE refusals, lost replies, AOF confirmation, counter exhaustion and bounded reads. Each test starts its own
 * {@code redis-server} ({@link RedisTestServer}); without the binary the tests are skipped, and with
 * {@code SOURBY_REQUIRE_REDIS=1} (CI) they fail instead.
 */
class CoopRedisTest {

    @TempDir
    Path directory;

    private RedisTestServer server;
    private RedisFabric fabric;
    private RedisProfileRepository repository;
    private CoopClaimFabric claims;
    private PlayerSessionOwnership nodeA;
    private PlayerSessionOwnership nodeB;
    private RedisProfileOwnership profilesA;

    @BeforeEach
    void setUp() throws Exception {
        this.server = RedisTestServer.start("--appendonly", "yes", "--appendfsync", "always");
        final RedisSettings redis = new RedisSettings(this.server.uri(), "t:coop:", 4, 1_000, 30, false);
        final FabricSettings settings = FabricSettings.parse(Map.of(FabricSettings.ENABLED_KEY, true)).settings();
        this.fabric = RedisFabric.start(settings, redis, "node-a");
        this.repository = RedisProfileRepository.open(this.fabric, new ProfileSettings.Repository(ProfileSettings.Backend.REDIS, true,
            false), this.directory.resolve("no-file-store"));
        this.repository.writeStoreFormat(StoreFormat.Marker.ready(CoopFixtures.BUILD));
        this.claims = this.fabric.sessions().coopClaims();
        this.nodeA = PlayerSessionOwnership.redis(this.claims, "node-a");
        this.nodeB = PlayerSessionOwnership.redis(this.claims, "node-b");
        this.profilesA = new RedisProfileOwnership(this.fabric.sessions(), "node-a", 1_000);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (this.repository != null) this.repository.close();
        if (this.fabric != null) this.fabric.close();
        if (this.server != null) this.server.close();
    }

    private PlayerClaim q(final PlayerSessionOwnership node, final UUID player) {
        final PlayerSessionOwnership.Grant grant = join(node.claim(player, UUID.randomUUID()));
        assertTrue(grant.granted(), grant.reason());
        return grant.claim();
    }

    private String namespace() {
        return this.fabric.prefix() + "mp:";
    }

    private RedisClient control() {
        return this.fabric.client(RedisWorkload.CONTROL);
    }

    /**
     * Every repository key with a digest of its value, staging keys excluded (staging is outside the commit decision
     * and only ever adds immutable blobs with a TTL).
     */
    private Map<String, String> keyspace() throws IOException {
        final TreeMap<String, String> values = new TreeMap<>();
        for (final Object raw : (List<?>) control().call("KEYS", namespace() + "*")) {
            final String key = RedisClient.text(raw);
            final String local = key.substring(namespace().length());
            if (local.startsWith("stage:") || local.startsWith("stage2:")) continue;
            final Object dumped = control().call("DUMP", key);
            values.put(key, dumped instanceof byte[] bytes ? CoopFixtures.sha256(bytes) : String.valueOf(dumped));
        }
        return values;
    }

    // ------------------------------------------------------------------ CoopDuplicatePlayerAcrossNodesRejected

    @Test
    void CoopDuplicatePlayerAcrossNodesRejected() throws Exception {
        final UUID player = UUID.randomUUID();
        final PlayerClaim onA = q(this.nodeA, player);
        final PlayerSessionOwnership.Grant onB = join(this.nodeB.claim(player, UUID.randomUUID()));
        assertFalse(onB.granted(), "a second node admitted the same player");
        assertEquals("held", onB.reason());
        assertEquals("node-a", onB.holder().node());
        assertEquals(onA.fence(), onB.holder().fence());
        assertTrue(join(this.nodeA.validate(onA)));
        // Not even the same owner token on another node passes: the session is node/incarnation/owner.
        assertFalse(join(this.nodeB.claim(player, onA.ownerToken())).granted());

        // The writes of the first session are accepted; after it is gone and node B took over, they are fenced.
        final AuroraProfile profile = CoopFixtures.create(this.repository, onA, "Apple", CLASSIC);
        assertTrue(join(this.nodeA.release(onA)));
        final PlayerClaim takenOver = q(this.nodeB, player);
        assertTrue(takenOver.fence() > onA.fence(), "a takeover must raise the fence");
        assertFalse(join(this.nodeA.validate(onA)));
        assertEquals(List.of(false), join(this.nodeA.renewAll(List.of(onA))));
        final PlayerAccount account = this.repository.loadAccount(player, SLOTS);
        final Map<String, String> before = keyspace();
        refusedWith(ProfileErrorCode.OWNERSHIP_CONFLICT, () -> this.repository.commitAccount(account.withSlotLimit(4),
            CoopWriteGuard.alias(op(CoopOperation.Kind.ACCOUNT_LIMIT, player), onA, account.revision())));
        assertEquals(before, keyspace(), "a fenced cross-node write changed Redis");
        committed(this.repository.commitAccount(account.withSlotLimit(4),
            CoopWriteGuard.alias(op(CoopOperation.Kind.ACCOUNT_LIMIT, player), takenOver, account.revision())));
        assertTrue(this.repository.profile(profile.id()).isPresent());
    }

    // ------------------------------------------------------------------ CoopClaimKindsCannotAlias (Redis)

    @Test
    void CoopClaimKindsCannotAlias() throws Exception {
        final UUID id = UUID.randomUUID();
        assertNotEquals(this.claims.key(CoopClaimFabric.Kind.PROFILE, id), this.claims.key(CoopClaimFabric.Kind.PLAYER, id));
        assertNotEquals(this.claims.fenceKey(CoopClaimFabric.Kind.PROFILE, id), this.claims.fenceKey(CoopClaimFabric.Kind.PLAYER, id));
        assertFalse(this.claims.key(CoopClaimFabric.Kind.PLAYER, id).equals(this.fabric.prefix() + id), "Q in the raw id namespace");

        final ProfileOwnership.TransactionGrant p = join(this.profilesA.claimTransaction(id, UUID.randomUUID()));
        assertTrue(p.granted());
        final PlayerClaim qOnSameId = q(this.nodeA, id);
        assertTrue(join(this.profilesA.validateTransaction(p.claim())));
        assertTrue(join(this.nodeA.release(qOnSameId)));
        assertTrue(join(this.profilesA.validateTransaction(p.claim())), "releasing Q released the P of the same id");

        // A P presented as a Q (same id, node, owner and fence) is fenced by the write script.
        final UUID player = UUID.randomUUID();
        final ProfileOwnership.TransactionGrant pOnPlayer = join(this.profilesA.claimTransaction(player, UUID.randomUUID()));
        assertTrue(pOnPlayer.granted());
        final ProfileClaim pClaim = pOnPlayer.claim();
        final PlayerClaim forged = new PlayerClaim(player, pClaim.node(), pClaim.incarnation(), pClaim.ownerToken(), pClaim.fence(),
            pClaim.expiresAtMillis());
        final Map<String, String> before = keyspace();
        refusedWith(ProfileErrorCode.OWNERSHIP_CONFLICT, () -> this.repository.commitAccount(
            PlayerAccount.empty(player, SLOTS).withSlotLimit(4),
            CoopWriteGuard.alias(op(CoopOperation.Kind.ACCOUNT_LIMIT, player), forged, 0L)));
        assertEquals(before, keyspace());
    }

    // ------------------------------------------------------------------ CoopCounterExhaustionRefused (Redis fences)

    @Test
    void CoopCounterExhaustionRefused() throws Exception {
        final UUID player = UUID.randomUUID();
        control().call("SET", this.claims.fenceKey(CoopClaimFabric.Kind.PLAYER, player), Long.toString(CoopClaimFabric.MAX_FENCE - 1L));
        final PlayerClaim last = q(this.nodeA, player);
        assertEquals(CoopClaimFabric.MAX_FENCE, last.fence(), "the last fence is 2^53 - 1");
        assertTrue(join(this.nodeA.release(last)));
        final PlayerSessionOwnership.Grant exhausted = join(this.nodeA.claim(player, UUID.randomUUID()));
        assertFalse(exhausted.granted());
        assertEquals("exhausted", exhausted.reason());
        assertEquals(Long.toString(CoopClaimFabric.MAX_FENCE),
            RedisClient.text(control().call("GET", this.claims.fenceKey(CoopClaimFabric.Kind.PLAYER, player))),
            "the fence was incremented past the cap or reset");

        final UUID profile = UUID.randomUUID();
        control().call("SET", this.claims.fenceKey(CoopClaimFabric.Kind.PROFILE, profile), Long.toString(CoopClaimFabric.MAX_FENCE));
        final ProfileOwnership.TransactionGrant p = join(this.profilesA.claimTransaction(profile, UUID.randomUUID()));
        assertFalse(p.granted());
        assertEquals("exhausted", p.reason());
        assertEquals(CoopWriteGuard.MAX_COUNTER, CoopClaimFabric.MAX_FENCE);
    }

    // ------------------------------------------------------------------ CoopRedisFencedAndStaleNoWrites

    @Test
    void CoopRedisFencedAndStaleNoWrites() throws Exception {
        final UUID player = UUID.randomUUID();
        final PlayerClaim q = q(this.nodeA, player);
        final AuroraProfile profile = CoopFixtures.create(this.repository, q, "Banana", CLASSIC);
        CoopFixtures.activate(this.repository, q, profile);
        final PlayerAccount account = this.repository.loadAccount(player, SLOTS);
        final long a = account.revision();

        // STALE: an expected revision that moved.
        Map<String, String> before = keyspace();
        refusedWith(ProfileErrorCode.STALE_STATE, () -> this.repository.commitAccount(account.withSlotLimit(4),
            CoopWriteGuard.alias(op(CoopOperation.Kind.ACCOUNT_LIMIT, player), q, a - 1L)));
        assertEquals(before, keyspace(), "a STALE write changed Redis");
        // A member save that expects a head revision the stream does not have (it has no head yet).
        final UUID epoch = profile.owner().epoch();
        assertTrue(this.repository.memberHead(profile.id(), player, epoch).isEmpty());
        refusedWith(ProfileErrorCode.STALE_STATE, () -> publishExpecting(q, profile, 0L, a));
        assertEquals(before, keyspace(), "a STALE member save changed Redis");
        final CoopFixtures.CreatePlan stalePlan = CoopFixtures.planCreate(this.repository, player, "Cherry", CLASSIC);
        final List<ProfileIntent.BlobRef> refs = CoopFixtures.stage(this.repository, stalePlan.op(), stalePlan.images());
        committed(this.repository.commitAccount(account.withSlotLimit(4),
            CoopWriteGuard.alias(op(CoopOperation.Kind.ACCOUNT_LIMIT, player), q, a)));
        before = keyspace();
        final CoopWriteGuard staleGuard = CoopWriteGuard.intent(stalePlan.op(), null, q, CoopWriteGuard.ABSENT, stalePlan.before(),
            Set.of());
        refusedWith(ProfileErrorCode.STALE_STATE, () -> this.repository.prepareIntent(ProfileIntent.prepared(staleGuard,
            stalePlan.after(), List.of(), refs, List.of(), 0L), staleGuard));
        assertEquals(before, keyspace(), "a STALE prepare changed Redis");

        // FENCED: a claim that no longer holds the lease, and a claim on another resource.
        final PlayerAccount current = this.repository.loadAccount(player, SLOTS);
        assertTrue(join(this.nodeA.release(q)));
        final PlayerClaim next = q(this.nodeA, player);
        before = keyspace();
        refusedWith(ProfileErrorCode.OWNERSHIP_CONFLICT, () -> this.repository.commitAccount(current.withSlotLimit(5),
            CoopWriteGuard.alias(op(CoopOperation.Kind.ACCOUNT_LIMIT, player), q, current.revision())));
        refusedWith(ProfileErrorCode.OWNERSHIP_CONFLICT, () -> publishExpecting(q, profile, 0L, current.revision()));
        final PlayerClaim stranger = q(this.nodeA, UUID.randomUUID());
        final ProfileErrorCode code = refusal(() -> this.repository.commitAccount(current.withSlotLimit(5),
            new CoopWriteGuard(op(CoopOperation.Kind.ACCOUNT_LIMIT, stranger.playerId()), null, stranger, null,
                List.of(CoopWriteGuard.Expected.account(player, current.revision())), null, Set.of()))).code();
        assertTrue(code == ProfileErrorCode.OWNERSHIP_CONFLICT || code == ProfileErrorCode.INVALID_REQUEST, "refused with " + code);
        final ProfileClaim p = join(this.profilesA.claimTransaction(profile.id(), UUID.randomUUID())).claim();
        assertTrue(join(this.profilesA.releaseTransaction(p)));
        refusedWith(ProfileErrorCode.OWNERSHIP_CONFLICT, () -> this.repository.commitProfile(
            this.repository.profile(profile.id()).orElseThrow().withStatus(ProfileStatus.LOCKED),
            CoopWriteGuard.metadata(op(CoopOperation.Kind.SET_STATUS, player), p, next, current.revision(), profile.revision())));
        assertEquals(before, keyspace(), "a FENCED write changed Redis");

        // The live claim still writes.
        committed(this.repository.commitAccount(current.withSlotLimit(5),
            CoopWriteGuard.alias(op(CoopOperation.Kind.ACCOUNT_LIMIT, player), next, current.revision())));
    }

    /** Publishes the stream's next snapshot from a head at {@code expectedHead} (as a writer that read revision {@code expectedHead}). */
    private CommitResult publishExpecting(final PlayerClaim q, final AuroraProfile profile, final long expectedHead,
                                          final long expectedAccount) throws IOException {
        final UUID player = q.playerId();
        final UUID epoch = profile.owner().epoch();
        final MemberStateHead base = new MemberStateHead(profile.id(), player, epoch, expectedHead, MemberStateHead.State.UNINITIALIZED,
            null, null, null, null);
        final CoopOperation saveOp = op(CoopOperation.Kind.SAVE_MEMBER, player);
        final ProfileSnapshot snapshot = ProfileSnapshot.forMember(profile.id(), player, epoch, expectedHead + 1L, CoopFixtures.state(),
            saveOp.operationId());
        return this.repository.publishMemberSnapshot(base.published(snapshot), snapshot, CoopFixtures.RETAIN,
            CoopWriteGuard.memberSave(saveOp, q, profile.id(), epoch, expectedAccount, profile.revision(), expectedHead));
    }

    /**
     * The first save into a membership without a head initialises the stream: the guard expects the head
     * {@link CoopWriteGuard#ABSENT} and the published head is {@code R = 1} ({@link MemberStateHead#published} of an
     * uninitialised head), exactly as the engine and the FILE repository do it.
     */
    @Test
    void theFirstMemberSaveInitialisesTheStream() throws Exception {
        final UUID player = UUID.randomUUID();
        final PlayerClaim q = q(this.nodeA, player);
        final AuroraProfile profile = CoopFixtures.create(this.repository, q, "Apple", CLASSIC);
        CoopFixtures.activate(this.repository, q, profile);
        final MemberStateHead first = CoopFixtures.save(this.repository, q, profile.id());
        assertEquals(1L, first.revision());
        assertEquals(MemberStateHead.State.READY, first.state());
        final MemberStateHead second = CoopFixtures.save(this.repository, q, profile.id());
        assertEquals(2L, second.revision());
        assertTrue(second.matches(this.repository.memberSnapshot(profile.id(), player, profile.owner().epoch(),
            second.snapshotId()).orElseThrow()));
        assertEquals(List.of(second), this.repository.memberHeads(profile.id()));
    }

    /** A profile's name is immutable in the Redis store too (FILE refuses a direct rename with INVALID_REQUEST). */
    @Test
    void aDirectRenameIsRefused() throws Exception {
        final UUID player = UUID.randomUUID();
        final PlayerClaim q = q(this.nodeA, player);
        final AuroraProfile profile = CoopFixtures.create(this.repository, q, "Orange", CLASSIC);
        final ProfileClaim p = join(this.profilesA.claimTransaction(profile.id(), UUID.randomUUID())).claim();
        final long a = this.repository.loadAccount(player, SLOTS).revision();
        final AuroraProfile renamed = new AuroraProfile(profile.id(), profile.accountId(), profile.type(), profile.profileType(),
            "Papaya", profile.metadata(), profile.createdAtMillis(), profile.revision() + 1L, profile.status(), profile.members(),
            profile.bindingRevision(), null);
        final Map<String, String> before = keyspace();
        refusal(() -> this.repository.commitProfile(renamed,
            CoopWriteGuard.metadata(op(CoopOperation.Kind.RENAME_DISPLAY, player), p, q, a, profile.revision())));
        assertEquals("Orange", this.repository.profile(profile.id()).orElseThrow().name());
        assertEquals(before, keyspace(), "a rename changed Redis");
    }

    /** A record reserved by a pending intent is not written by a direct commit (it must be resolved first). */
    @Test
    void aDirectCommitOnAReservedAccountIsRefused() throws Exception {
        final UUID player = UUID.randomUUID();
        final PlayerClaim q = q(this.nodeA, player);
        final CoopFixtures.CreatePlan plan = CoopFixtures.planCreate(this.repository, player, "Lemon", CLASSIC);
        CoopFixtures.prepareCreate(this.repository, q, plan);
        final PlayerAccount reserved = this.repository.loadAccount(player, SLOTS);
        assertEquals(plan.op().operationId(), reserved.pendingIntent());
        final Map<String, String> before = keyspace();
        refusal(() -> this.repository.commitAccount(reserved.withSlotLimit(4),
            CoopWriteGuard.alias(op(CoopOperation.Kind.ACCOUNT_LIMIT, player), q, reserved.revision())));
        assertEquals(before, keyspace(), "a direct commit wrote over a reservation");
        committed(CoopFixtures.commit(this.repository, plan.op(), null, q));
    }

    /** The marker never moves back to format 1 (OD-2: rollback is a backup restore, not an in-place downgrade). */
    @Test
    void aDowngradeMarkerIsNeverWritten() throws Exception {
        assertThrows(Exception.class, () -> this.repository.writeStoreFormat(StoreFormat.Marker.legacy()));
        assertTrue(this.repository.storeFormat().readyFormat2(), "the Redis store was downgraded to format 1");
        // Every format-1 record writer is refused on a format-2 store.
        final UUID player = UUID.randomUUID();
        refusedWith(ProfileErrorCode.STORE_FORMAT_REFUSED, () -> this.repository.saveAccount(PlayerAccount.empty(player, SLOTS),
            ProfileWriteGuard.of(null, 0L)));
    }

    // ------------------------------------------------------------------ CoopIntentCrashAtEveryPhase (Redis)

    /** Simulates a process restart: a new repository instance over the same Redis. */
    private void reopen() throws IOException {
        this.repository.close();
        this.repository = RedisProfileRepository.open(this.fabric, new ProfileSettings.Repository(ProfileSettings.Backend.REDIS, true,
            false), this.directory.resolve("no-file-store"));
    }

    /**
     * Redis decides an intent in two scripts (PREPARE; COMMIT = COMMITTED + FINALIZED in one step), so the crash points
     * are: after staging, after PREPARE. Neither exposes anything; PREPARED survives the restart and is aborted or
     * committed exactly once.
     */
    @Test
    void CoopIntentCrashAtEveryPhase() throws Exception {
        final UUID player = UUID.randomUUID();
        final PlayerClaim q = q(this.nodeA, player);

        final CoopFixtures.CreatePlan staged = CoopFixtures.planCreate(this.repository, player, "Apple", CLASSIC);
        CoopFixtures.stage(this.repository, staged.op(), staged.images());
        reopen();
        assertTrue(this.repository.intent(staged.op().operationId()).isEmpty());
        assertTrue(this.repository.profile(staged.profile().id()).isEmpty());
        assertEquals(0L, this.repository.loadAccount(player, SLOTS).revision());

        final CoopFixtures.CreatePlan aborted = CoopFixtures.planCreate(this.repository, player, "Banana", CLASSIC);
        CoopFixtures.prepareCreate(this.repository, q, aborted);
        reopen();
        assertEquals(ProfileIntent.Phase.PREPARED, this.repository.intent(aborted.op().operationId()).orElseThrow().phase());
        final PlayerAccount reserved = this.repository.loadAccount(player, SLOTS);
        assertEquals(aborted.op().operationId(), reserved.pendingIntent());
        assertEquals(1L, reserved.revision());
        assertTrue(this.repository.profile(aborted.profile().id()).isEmpty(), "a PREPARED create published its profile");
        committed(CoopFixtures.abort(this.repository, aborted.op(), null, q));
        assertEquals(CommitResult.Status.REPLAYED, CoopFixtures.abort(this.repository, aborted.op(), null, q).status());
        final PlayerAccount cleared = this.repository.loadAccount(player, SLOTS);
        assertEquals(2L, cleared.revision());
        assertEquals(null, cleared.pendingIntent());
        assertTrue(cleared.profileIds().isEmpty());

        final CoopFixtures.CreatePlan decided = CoopFixtures.planCreate(this.repository, player, "Cherry", CLASSIC);
        CoopFixtures.prepareCreate(this.repository, q, decided);
        reopen();
        committed(CoopFixtures.commit(this.repository, decided.op(), null, q));
        assertEquals(CommitResult.Status.REPLAYED, CoopFixtures.commit(this.repository, decided.op(), null, q).status());
        assertEquals(decided.accountAfter(), this.repository.loadAccount(player, SLOTS));
        assertEquals(decided.profile(), this.repository.profile(decided.profile().id()).orElseThrow());
        assertTrue(this.repository.index(ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL, null, null, null)
            .entries().isEmpty());
    }

    // ------------------------------------------------------------------ CoopLostCommitReplyReconciles (Redis)

    @Test
    void CoopLostCommitReplyReconciles() throws Exception {
        final UUID player = UUID.randomUUID();
        final PlayerClaim q = q(this.nodeA, player);
        CoopFixtures.create(this.repository, q, "Kiwi", CLASSIC);
        final PlayerAccount account = this.repository.loadAccount(player, SLOTS);
        final long a = account.revision();

        // A retry of a decided operation returns its receipt; a reused id with another request is refused.
        final CoopOperation first = op(CoopOperation.Kind.ACCOUNT_LIMIT, player);
        final CoopWriteGuard firstGuard = CoopWriteGuard.alias(first, q, a);
        committed(this.repository.commitAccount(account.withSlotLimit(4), firstGuard));
        assertEquals(CommitResult.Status.REPLAYED, this.repository.commitAccount(account.withSlotLimit(4), firstGuard).status());
        assertEquals(a + 1L, this.repository.loadAccount(player, SLOTS).revision());
        final CoopOperation forged = new CoopOperation(first.operationId(), first.kind(), player, CoopOperation.digest("other"), 1L);
        refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> this.repository.commitAccount(account.withSlotLimit(5),
            CoopWriteGuard.alias(forged, q, a)));

        // The reply of a write is lost (the server stops answering): the outcome is reported as unknown or not
        // applied, never as committed; once Redis answers again the same operation applies exactly once.
        final PlayerAccount stored = this.repository.loadAccount(player, SLOTS);
        final CoopOperation lost = op(CoopOperation.Kind.ACCOUNT_LIMIT, player);
        final CoopWriteGuard lostGuard = CoopWriteGuard.alias(lost, q, stored.revision());
        this.server.freeze();
        CommitResult during = null;
        try {
            during = this.repository.commitAccount(stored.withSlotLimit(5), lostGuard);
        } catch (final IOException notApplied) {
            // "the reply was lost and no receipt or intent is stored": acceptable, it was not reported as applied
        } finally {
            this.server.thaw();
        }
        if (during != null) assertEquals(CommitResult.Status.UNKNOWN, during.status(), during.detail());
        final CommitResult retried = this.repository.commitAccount(stored.withSlotLimit(5), lostGuard);
        assertTrue(retried.status() == CommitResult.Status.COMMITTED || retried.status() == CommitResult.Status.REPLAYED,
            retried.status() + ": " + retried.detail());
        final PlayerAccount after = this.repository.loadAccount(player, SLOTS);
        assertEquals(stored.revision() + 1L, after.revision(), "the operation applied more or less than once");
        assertEquals(5, after.slotLimit());
        assertTrue(this.repository.receipt(player, lost.operationId()).isPresent());
        assertEquals(CommitResult.Status.REPLAYED, this.repository.commitAccount(stored.withSlotLimit(5), lostGuard).status());
    }

    // ------------------------------------------------------------------ CoopAofUnconfirmedBlocksEffects

    /**
     * With {@code wait-for-aof} a write Redis applied but cannot confirm in its append-only file is UNKNOWN, never
     * COMMITTED: the caller holds its gate and reconciles from the receipt. Here the AOF is turned off at runtime
     * (after the open-time check passed), so no confirmation can arrive.
     */
    @Test
    void CoopAofUnconfirmedBlocksEffects() throws Exception {
        final String info = RedisClient.text(control().call("INFO", "server"));
        final String version = info.lines().filter(line -> line.startsWith("redis_version:")).findFirst().orElse("redis_version:0")
            .substring("redis_version:".length()).trim();
        final String[] parts = version.split("\\.");
        final int major = Integer.parseInt(parts[0]);
        final int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        Assumptions.assumeTrue(major > 7 || (major == 7 && minor >= 2), "WAITAOF needs Redis 7.2, found " + version);

        final RedisProfileRepository aof = RedisProfileRepository.open(this.fabric, new ProfileSettings.Repository(
            ProfileSettings.Backend.REDIS, true, true), this.directory.resolve("no-file-store"));
        final UUID player = UUID.randomUUID();
        final PlayerClaim q = q(this.nodeA, player);
        // Control: appendfsync always confirms at once.
        final AuroraProfile profile = CoopFixtures.create(aof, q, "Peach", CLASSIC);
        assertNotNull(profile);
        final PlayerAccount account = aof.loadAccount(player, SLOTS);
        final CoopOperation confirmedOp = op(CoopOperation.Kind.ACCOUNT_LIMIT, player);
        committed(aof.commitAccount(account.withSlotLimit(4), CoopWriteGuard.alias(confirmedOp, q, account.revision())));

        control().call("CONFIG", "SET", "appendonly", "no");
        final PlayerAccount stored = aof.loadAccount(player, SLOTS);
        final CoopOperation unconfirmed = op(CoopOperation.Kind.ACCOUNT_LIMIT, player);
        final CommitResult result = aof.commitAccount(stored.withSlotLimit(5), CoopWriteGuard.alias(unconfirmed, q, stored.revision()));
        assertEquals(CommitResult.Status.UNKNOWN, result.status(), "an unconfirmed write was reported as " + result.status()
            + ": " + result.detail());
        assertTrue(aof.receipt(player, unconfirmed.operationId()).isPresent(), "the applied write left no receipt to reconcile from");
        // A prepare that cannot be confirmed is not reported as prepared either.
        final CoopFixtures.CreatePlan plan = CoopFixtures.planCreate(aof, player, "Plum", CLASSIC);
        final List<ProfileIntent.BlobRef> refs = CoopFixtures.stage(aof, plan.op(), plan.images());
        final CoopWriteGuard guard = CoopWriteGuard.intent(plan.op(), null, q, CoopWriteGuard.ABSENT, plan.before(), Set.of());
        final CommitResult prepared = aof.prepareIntent(ProfileIntent.prepared(guard, plan.after(), List.of(), refs, List.of(), 0L), guard);
        assertEquals(CommitResult.Status.UNKNOWN, prepared.status(), prepared.detail());
    }

    // ------------------------------------------------------------------ CoopGrowingFileReadBounded (Redis values)

    @Test
    void CoopGrowingFileReadBounded() throws Exception {
        final UUID player = UUID.randomUUID();
        final UUID other = UUID.randomUUID();
        final PlayerClaim q = q(this.nodeA, other);
        CoopFixtures.create(this.repository, q, "Lime", CLASSIC);
        final byte[] oversized = new byte[ProfileRecords.maxPayload(ProfileRecords.TYPE_ACCOUNT) + ProfileRecords.OVERHEAD + 1];
        control().call("HSET", namespace() + "account:" + player, "rev", "0", "rec", oversized);
        assertThrows(IOException.class, () -> this.repository.loadAccount(player, SLOTS),
            "a value larger than its record bound was read");
        // The bounded read discarded its connection; the pool keeps serving bounded reads.
        for (int i = 0; i < 8; i++) assertEquals(2L, this.repository.loadAccount(other, SLOTS).revision());
    }
}
