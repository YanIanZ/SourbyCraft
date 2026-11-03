package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.CLASSIC;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.committed;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.join;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.op;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusal;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusedWith;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.DAY;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.INACTIVE_SHIFT;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.MINUTE;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.POLICY;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.abortPlan;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.acceptPlan;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.account;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.admit;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.assertAccepted;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.assertKicked;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.assertNotAccepted;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.commitPlan;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.convert;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.indexed;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.invite;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.kickPlan;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.passedVote;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.preparePlan;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.profile;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.runPlan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ProfileClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileRepository.CommitResult;
import dev.iyanz.sourbycraft.awf.redis.RedisClient;
import dev.iyanz.sourbycraft.awf.redis.RedisSettings;
import dev.iyanz.sourbycraft.awf.redis.RedisTestServer;
import dev.iyanz.sourbycraft.awf.redis.fabric.FabricSettings;
import dev.iyanz.sourbycraft.awf.redis.fabric.RedisFabric;
import dev.iyanz.sourbycraft.awf.redis.fabric.RedisWorkload;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Build 52 membership intents on the Redis store (COOP-DESIGN §5, §10 slice 4), the same plans as
 * {@link CoopMembershipFileTest}: accept and kick at every crash point Redis has (after staging, after PREPARE; Redis
 * COMMIT installs COMMITTED and FINALIZED in one script), atomic visibility, the capacity race and an invite that
 * expires before its accept commits (judged by Redis {@code TIME} inside the commit script). Each test starts its own
 * {@code redis-server} ({@link RedisTestServer}); without the binary they are skipped, and with
 * {@code SOURBY_REQUIRE_REDIS=1} (CI) they fail instead.
 */
class CoopMembershipRedisTest {

    @TempDir
    Path directory;

    private RedisTestServer server;
    private RedisFabric fabric;
    private RedisProfileRepository repository;
    private PlayerSessionOwnership players;
    private RedisProfileOwnership profiles;

    @BeforeEach
    void setUp() throws Exception {
        this.server = RedisTestServer.start("--appendonly", "yes", "--appendfsync", "always");
        final RedisSettings redis = new RedisSettings(this.server.uri(), "t:coop52:", 4, 1_000, 30, false);
        final FabricSettings settings = FabricSettings.parse(Map.of(FabricSettings.ENABLED_KEY, true)).settings();
        this.fabric = RedisFabric.start(settings, redis, "node-a");
        this.repository = open();
        this.repository.writeStoreFormat(StoreFormat.Marker.ready(CoopFixtures.BUILD));
        this.players = PlayerSessionOwnership.redis(this.fabric.sessions().coopClaims(), "node-a");
        this.profiles = new RedisProfileOwnership(this.fabric.sessions(), "node-a", 1_000);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (this.repository != null) this.repository.close();
        if (this.fabric != null) this.fabric.close();
        if (this.server != null) this.server.close();
    }

    private RedisProfileRepository open() throws IOException {
        return RedisProfileRepository.open(this.fabric, new ProfileSettings.Repository(ProfileSettings.Backend.REDIS, true, false),
            this.directory.resolve("no-file-store"));
    }

    /** Simulates a process restart: a new repository instance over the same Redis. */
    private void reopen() throws IOException {
        this.repository.close();
        this.repository = open();
    }

    private PlayerClaim q(final UUID player) {
        final PlayerSessionOwnership.Grant grant = join(this.players.claim(player, UUID.randomUUID()));
        assertTrue(grant.granted(), grant.reason());
        return grant.claim();
    }

    private ProfileClaim p(final UUID profile) {
        final ProfileOwnership.TransactionGrant grant = join(this.profiles.claimTransaction(profile, UUID.randomUUID()));
        assertTrue(grant.granted(), grant.reason());
        return grant.claim();
    }

    /** Every repository key with a digest of its value, staging keys excluded (as {@code CoopRedisTest.keyspace}). */
    private Map<String, String> keyspace() throws IOException {
        final String namespace = this.fabric.prefix() + "mp:";
        final RedisClient control = this.fabric.client(RedisWorkload.CONTROL);
        final TreeMap<String, String> values = new TreeMap<>();
        for (final Object raw : (List<?>) control.call("KEYS", namespace + "*")) {
            final String key = RedisClient.text(raw);
            final String local = key.substring(namespace.length());
            if (local.startsWith("stage:") || local.startsWith("stage2:")) continue;
            final Object dumped = control.call("DUMP", key);
            values.put(key, dumped instanceof byte[] bytes ? CoopFixtures.sha256(bytes) : String.valueOf(dumped));
        }
        return values;
    }

    private record Coop(UUID owner, PlayerClaim q, ProfileClaim p, AuroraProfile profile) {}

    private Coop coop(final String name) throws Exception {
        final UUID owner = UUID.randomUUID();
        final PlayerClaim q = q(owner);
        final AuroraProfile solo = CoopFixtures.create(this.repository, q, name, CLASSIC);
        final ProfileClaim p = p(solo.id());
        return new Coop(owner, q, p, convert(this.repository, p, q, solo));
    }

    // ------------------------------------------------------------------ accept

    @Test
    void CoopAcceptIntentCrashAtEveryPhase() throws Exception {
        final Coop coop = coop("Apple");
        final UUID profileId = coop.profile().id();
        // The store's feature level was raised before the first co-op record.
        assertTrue(this.repository.storeFormat().covers(StoreFormat.COOP_SCHEMA, StoreFormat.COOP_MINIMUM_BUILD));
        assertEquals(StoreFormat.COOP_MINIMUM_BUILD, this.repository.storeFormat().requiredBuild());

        // 1. After staging: nothing visible; the plan still runs.
        final PlayerClaim q1 = q(UUID.randomUUID());
        final ProfileInvite i1 = invite(this.repository, coop.p(), coop.q(), profileId, q1.playerId());
        final CoopIntentPlan a1 = acceptPlan(this.repository, i1);
        a1.stage(this.repository);
        reopen();
        assertTrue(this.repository.intent(a1.op().operationId()).isEmpty());
        assertNotAccepted(this.repository, profileId, i1);
        runPlan(this.repository, a1, coop.p(), q1, null);
        assertAccepted(this.repository, a1, i1);

        // 2. After PREPARE, then abort: reservations survive the restart; abort clears them; nothing was applied.
        final PlayerClaim q2 = q(UUID.randomUUID());
        final ProfileInvite i2 = invite(this.repository, coop.p(), coop.q(), profileId, q2.playerId());
        final CoopIntentPlan a2 = acceptPlan(this.repository, i2);
        preparePlan(this.repository, a2, coop.p(), q2, null);
        reopen();
        assertEquals(ProfileIntent.Phase.PREPARED, this.repository.intent(a2.op().operationId()).orElseThrow().phase());
        assertEquals(a2.op().operationId(), profile(this.repository, profileId).pendingIntent());
        assertEquals(a2.op().operationId(), account(this.repository, q2.playerId()).pendingIntent());
        assertNotAccepted(this.repository, profileId, i2);
        committed(abortPlan(this.repository, a2, coop.p(), q2, null));
        assertEquals(CommitResult.Status.REPLAYED, abortPlan(this.repository, a2, coop.p(), q2, null).status());
        assertNull(profile(this.repository, profileId).pendingIntent());
        assertNull(account(this.repository, q2.playerId()).pendingIntent());
        assertNotAccepted(this.repository, profileId, i2);
        refusedWith(ProfileErrorCode.STALE_STATE, () -> commitPlan(this.repository, a2, coop.p(), q2, null));

        // 3. After PREPARE, then commit: applied once; a replay is REPLAYED.
        final PlayerClaim q3 = q(UUID.randomUUID());
        final ProfileInvite i3 = invite(this.repository, coop.p(), coop.q(), profileId, q3.playerId());
        final CoopIntentPlan a3 = acceptPlan(this.repository, i3);
        preparePlan(this.repository, a3, coop.p(), q3, null);
        reopen();
        committed(commitPlan(this.repository, a3, coop.p(), q3, null));
        assertEquals(CommitResult.Status.REPLAYED, commitPlan(this.repository, a3, coop.p(), q3, null).status());
        assertAccepted(this.repository, a3, i3);

        assertEquals(3, profile(this.repository, profileId).members().size());
        assertTrue(indexed(this.repository, ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL).isEmpty());
    }

    // ------------------------------------------------------------------ kick

    @Test
    void CoopKickIntentCrashAtEveryPhase() throws Exception {
        for (int phase = 1; phase <= 3; phase++) {
            final Coop coop = coop("Mango");
            final PlayerClaim member = q(UUID.randomUUID());
            final ProfileMember row = admit(this.repository, coop.p(), coop.q(), coop.profile().id(), member);
            final long at = this.repository.storeClockMillis() + INACTIVE_SHIFT;
            final KickVote vote = passedVote(this.repository, coop.p(), coop.q(), coop.profile().id(), member.playerId(), at);
            final CoopIntentPlan kick = kickPlan(this.repository, coop.owner(), vote, at);
            switch (phase) {
                case 1 -> {
                    kick.stage(this.repository);
                    reopen();
                    assertTrue(this.repository.intent(kick.op().operationId()).isEmpty());
                    assertEquals(row, profile(this.repository, coop.profile().id()).member(member.playerId()));
                    runPlan(this.repository, kick, coop.p(), coop.q(), member);
                    assertKicked(this.repository, kick, row);
                }
                case 2 -> {
                    preparePlan(this.repository, kick, coop.p(), coop.q(), member);
                    reopen();
                    assertEquals(row, profile(this.repository, coop.profile().id()).member(member.playerId()));
                    assertTrue(account(this.repository, member.playerId()).owns(coop.profile().id()));
                    assertTrue(this.repository.salvage(kick.result().salvageId()).isEmpty());
                    committed(abortPlan(this.repository, kick, coop.p(), coop.q(), member));
                    assertEquals(row, profile(this.repository, coop.profile().id()).member(member.playerId()));
                    assertEquals(KickVote.Status.PASSED, this.repository.vote(vote.id()).orElseThrow().status());
                    final CoopIntentPlan retry = kickPlan(this.repository, coop.owner(), vote, at);
                    runPlan(this.repository, retry, coop.p(), coop.q(), member);
                    assertKicked(this.repository, retry, row);
                    assertTrue(this.repository.salvage(kick.result().salvageId()).isEmpty());
                }
                default -> {
                    preparePlan(this.repository, kick, coop.p(), coop.q(), member);
                    reopen();
                    committed(commitPlan(this.repository, kick, coop.p(), coop.q(), member));
                    assertEquals(CommitResult.Status.REPLAYED, commitPlan(this.repository, kick, coop.p(), coop.q(), member).status());
                    assertKicked(this.repository, kick, row);
                    assertEquals(1, indexed(this.repository, ProfileIndexPage.Kind.SALVAGE_BY_PROFILE, coop.profile().id()).size());
                }
            }
        }
        assertTrue(indexed(this.repository, ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL).isEmpty());
    }

    // ------------------------------------------------------------------ visibility, race, expiry

    @Test
    void CoopMembershipAccountAtomicVisibility() throws Exception {
        final Coop coop = coop("Kiwi");
        final UUID profileId = coop.profile().id();
        final PlayerClaim member = q(UUID.randomUUID());
        final ProfileInvite invite = invite(this.repository, coop.p(), coop.q(), profileId, member.playerId());
        final CoopIntentPlan accept = acceptPlan(this.repository, invite);
        preparePlan(this.repository, accept, coop.p(), member, null);
        assertNotAccepted(this.repository, profileId, invite);
        assertTrue(this.repository.profiles(member.playerId()).isEmpty());
        committed(commitPlan(this.repository, accept, coop.p(), member, null));
        assertAccepted(this.repository, accept, invite);
        assertEquals(List.of(profileId), this.repository.profiles(member.playerId()).stream().map(AuroraProfile::id).toList());
    }

    @Test
    void CoopAcceptCapacityRace() throws Exception {
        final CoopMembership.Policy pair = new CoopMembership.Policy(2, 5 * MINUTE, 10 * MINUTE, 35 * DAY);
        final Coop coop = coop("Grapes");
        final UUID profileId = coop.profile().id();
        final PlayerClaim b = q(UUID.randomUUID());
        final PlayerClaim c = q(UUID.randomUUID());
        final ProfileInvite ib = invite(this.repository, coop.p(), coop.q(), profileId, b.playerId(), this.repository.storeClockMillis(),
            pair);
        final ProfileInvite ic = invite(this.repository, coop.p(), coop.q(), profileId, c.playerId(), this.repository.storeClockMillis(),
            pair);
        final long now = this.repository.storeClockMillis();
        final CoopIntentPlan ab = acceptPlan(this.repository, ib, now, pair);
        final CoopIntentPlan ac = acceptPlan(this.repository, ic, now, pair);
        preparePlan(this.repository, ab, coop.p(), b, null);
        final Map<String, String> reserved = keyspace();
        refusedWith(ProfileErrorCode.STALE_STATE, () -> preparePlan(this.repository, ac, coop.p(), c, null));
        assertEquals(reserved, keyspace(), "a refused PREPARE changed Redis");
        committed(commitPlan(this.repository, ab, coop.p(), b, null));
        refusedWith(ProfileErrorCode.STALE_STATE, () -> preparePlan(this.repository, ac, coop.p(), c, null));
        refusedWith(ProfileErrorCode.COOP_FULL, () -> acceptPlan(this.repository, ic, this.repository.storeClockMillis(), pair));
        assertEquals(2, profile(this.repository, profileId).members().size());
        assertNotAccepted(this.repository, profileId, ic);
    }

    @Test
    void CoopInviteExpiredAtCommit() throws Exception {
        final Coop coop = coop("Banana");
        final UUID profileId = coop.profile().id();
        final PlayerClaim member = q(UUID.randomUUID());
        final long now = this.repository.storeClockMillis();
        final ProfileInvite invite = invite(this.repository, coop.p(), coop.q(), profileId, member.playerId(), now - 10 * MINUTE, POLICY);
        final CoopIntentPlan accept = acceptPlan(this.repository, invite, invite.expiresAtMillis() - 1L, POLICY);
        assertEquals(invite.expiresAtMillis(), accept.deadlineMillis());
        preparePlan(this.repository, accept, coop.p(), member, null);
        final Map<String, String> prepared = keyspace();
        final ProfileOperationException refused = refusal(() -> commitPlan(this.repository, accept, coop.p(), member, null));
        assertEquals(ProfileErrorCode.TIMEOUT, refused.code(), refused.getMessage());
        assertEquals(prepared, keyspace(), "a commit refused past its deadline changed Redis");
        assertNotAccepted(this.repository, profileId, invite);
        committed(abortPlan(this.repository, accept, coop.p(), member, null));
        assertNotAccepted(this.repository, profileId, invite);
        assertNull(account(this.repository, member.playerId()).pendingIntent());
        refusedWith(ProfileErrorCode.INVITE_EXPIRED, () -> acceptPlan(this.repository, invite));
    }

    // ------------------------------------------------------------------ abort after a compared record moved

    /**
     * An intent that reserves {@code M} but only compares the actor's account (an invite create) is aborted after the
     * compared account moved (a session login: a direct commit the reservation does not block). The abort compares the
     * intent's own revision and the records the intent reserved, never a compared one, clears the reservation, and
     * nothing of the invite is visible; the actor is not locked out behind a PREPARED intent.
     */
    @Test
    void CoopAbortAfterComparedRecordMovedOnRedis() throws Exception {
        final Coop coop = coop("Cherry");
        final UUID profileId = coop.profile().id();
        final UUID invitee = UUID.randomUUID();
        final CoopIntentPlan create = CoopMembership.createInvite(op(CoopOperation.Kind.INVITE_CREATE, coop.owner()),
            profile(this.repository, profileId), account(this.repository, coop.owner()), invitee, null, List.of(), POLICY,
            this.repository.storeClockMillis(), 0L);
        preparePlan(this.repository, create, coop.p(), coop.q(), null);
        final long reserved = profile(this.repository, profileId).revision();
        assertEquals(create.op().operationId(), profile(this.repository, profileId).pendingIntent());
        // The owner's account moves while the intent is PREPARED: A is compared, not reserved, so the login commits.
        final PlayerAccount owner = account(this.repository, coop.owner());
        committed(this.repository.commitAccount(owner.nextSession(), CoopWriteGuard.alias(op(CoopOperation.Kind.LOGIN, coop.owner()),
            coop.q(), owner.revision())));
        assertEquals(owner.revision() + 1L, account(this.repository, coop.owner()).revision());
        refusedWith(ProfileErrorCode.STALE_STATE, () -> commitPlan(this.repository, create, coop.p(), coop.q(), null));
        committed(abortPlan(this.repository, create, coop.p(), coop.q(), null));
        assertEquals(CommitResult.Status.REPLAYED, abortPlan(this.repository, create, coop.p(), coop.q(), null).status());
        assertNull(profile(this.repository, profileId).pendingIntent());
        assertEquals(reserved + 1L, profile(this.repository, profileId).revision(), "the release advanced M once");
        assertTrue(this.repository.invite(create.result().recordId()).isEmpty());
        assertTrue(indexed(this.repository, ProfileIndexPage.Kind.INVITES_BY_PROFILE, profileId).isEmpty());
        assertTrue(indexed(this.repository, ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL).isEmpty());
        // Not locked out: the next invite of the owner runs.
        final CoopIntentPlan again = CoopMembership.createInvite(op(CoopOperation.Kind.INVITE_CREATE, coop.owner()),
            profile(this.repository, profileId), account(this.repository, coop.owner()), invitee, null, List.of(), POLICY,
            this.repository.storeClockMillis(), 0L);
        runPlan(this.repository, again, coop.p(), coop.q(), null);
        assertEquals(List.of(again.result().recordId()), indexed(this.repository, ProfileIndexPage.Kind.INVITES_BY_PROFILE, profileId));
    }

    /** A solo (Build 51) intent aborts on Redis: the delete's reservations on M and A are cleared and nothing was applied. */
    @Test
    void CoopSoloIntentAbortOnRedis() throws Exception {
        final UUID player = UUID.randomUUID();
        final PlayerClaim q = q(player);
        final AuroraProfile solo = CoopFixtures.create(this.repository, q, "Plum", CLASSIC);
        final ProfileClaim p = p(solo.id());
        final CoopFixtures.DeletePlan delete = CoopFixtures.planDelete(this.repository, solo);
        CoopFixtures.prepare(this.repository, delete.op(), p, q, delete.before(), delete.after(), delete.images(), List.of());
        assertEquals(delete.op().operationId(), profile(this.repository, solo.id()).pendingIntent());
        assertEquals(delete.op().operationId(), account(this.repository, player).pendingIntent());
        committed(CoopFixtures.abort(this.repository, delete.op(), p, q));
        assertEquals(CommitResult.Status.REPLAYED, CoopFixtures.abort(this.repository, delete.op(), p, q).status());
        assertNull(profile(this.repository, solo.id()).pendingIntent());
        assertNull(account(this.repository, player).pendingIntent());
        assertEquals(ProfileStatus.ACTIVE, profile(this.repository, solo.id()).status());
        assertTrue(account(this.repository, player).owns(solo.id()));
        refusedWith(ProfileErrorCode.STALE_STATE, () -> CoopFixtures.commit(this.repository, delete.op(), p, q));
        assertTrue(indexed(this.repository, ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL).isEmpty());
    }

    // ------------------------------------------------------------------ a PASSED vote keeps the profile's vote slot

    /** A vote PASSED at creation (two-member co-op) stays in both vote indexes until its kick executes; the bound holds. */
    @Test
    void CoopPassedVoteStaysIndexedOnRedis() throws Exception {
        final Coop coop = coop("Fig");
        final UUID profileId = coop.profile().id();
        final PlayerClaim member = q(UUID.randomUUID());
        admit(this.repository, coop.p(), coop.q(), profileId, member);
        final long at = this.repository.storeClockMillis() + INACTIVE_SHIFT;
        final KickVote vote = passedVote(this.repository, coop.p(), coop.q(), profileId, member.playerId(), at);
        assertEquals(KickVote.Status.PASSED, vote.status());
        assertEquals(List.of(vote.id()), indexed(this.repository, ProfileIndexPage.Kind.VOTES_BY_PROFILE, profileId));
        assertEquals(List.of(vote.id()), indexed(this.repository, ProfileIndexPage.Kind.VOTES_BY_TARGET, member.playerId()));
        assertEquals(List.of(vote.id()), CoopMembership.votesByProfile(this.repository, profileId, null).items().stream()
            .map(KickVote::id).toList());
        // A caller that skipped the index read (no open votes passed) is stopped by the store's one-vote bound.
        final CoopIntentPlan second = CoopMembership.createVote(op(CoopOperation.Kind.VOTE_CREATE, coop.owner()),
            profile(this.repository, profileId), account(this.repository, coop.owner()), member.playerId(), List.of(), POLICY, at, 0L);
        final ProfileOperationException refused = refusal(() -> runPlan(this.repository, second, coop.p(), coop.q(), null));
        assertEquals(ProfileErrorCode.CAPACITY_EXCEEDED, refused.code(), refused.getMessage());
        final var stored = this.repository.intent(second.op().operationId());
        if (stored.isPresent() && stored.get().phase() == ProfileIntent.Phase.PREPARED) {
            committed(abortPlan(this.repository, second, coop.p(), coop.q(), null));
        }
        assertEquals(List.of(vote.id()), indexed(this.repository, ProfileIndexPage.Kind.VOTES_BY_PROFILE, profileId));
        // The executed kick leaves both indexes.
        runPlan(this.repository, kickPlan(this.repository, coop.owner(), vote, at), coop.p(), coop.q(), member);
        assertEquals(KickVote.Status.EXECUTED, this.repository.vote(vote.id()).orElseThrow().status());
        assertTrue(indexed(this.repository, ProfileIndexPage.Kind.VOTES_BY_PROFILE, profileId).isEmpty());
        assertTrue(indexed(this.repository, ProfileIndexPage.Kind.VOTES_BY_TARGET, member.playerId()).isEmpty());
    }
}
