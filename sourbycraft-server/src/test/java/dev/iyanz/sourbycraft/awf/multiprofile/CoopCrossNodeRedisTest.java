package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.join;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusedWith;
import static dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.level;
import static dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.playSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.api.profile.ProfileRequest;
import dev.iyanz.sourbycraft.api.profile.ProfileSwitchResult;
import dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.PlayingPorts;
import dev.iyanz.sourbycraft.awf.redis.RedisSettings;
import dev.iyanz.sourbycraft.awf.redis.RedisTestServer;
import dev.iyanz.sourbycraft.awf.redis.fabric.ClaimResult;
import dev.iyanz.sourbycraft.awf.redis.fabric.FabricSettings;
import dev.iyanz.sourbycraft.awf.redis.fabric.FabricTestNodes;
import dev.iyanz.sourbycraft.awf.redis.fabric.RedisFabric;
import dev.iyanz.sourbycraft.awf.redis.fabric.SessionFabric;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Build 56, co-op across nodes ({@code coop.cross-node}, {@code coop.world-host}) on the shared Redis store with two
 * engines as two nodes, in the WuretaMC shape: node A is the <b>island</b> (a world host, switching on) and node B the
 * <b>spawn</b> (a satellite: switching off, no node claim, no bound world). The cases: a member on each node at once
 * (B's join is admitted without the node claim, B's restore is not {@code WRONG_NODE}, each member's stream moves only
 * with that member's saves); a bank posting from each node (conservation; a transaction claim held on one node refuses
 * the other with nothing written, and the refused node retries and succeeds); membership changes while a member is on
 * the other node (an invite accepted on the spawn node joins the co-op; a leave or a removal of a member who plays on
 * the other node is {@code MEMBER_ONLINE}; an operator lock on the island while a member plays on the spawn node is
 * refused); and the default (cross-node off) keeping the Build 55.1 refusal. The FILE refusal is a settings case
 * ({@link ProfileSettingsBuild56Test}).
 *
 * <p>The node fixture is that of {@link CoopMemberPlayRedisTest}: one {@link RedisFabric}, one {@link SessionFabric} with
 * its own incarnation per node. Each test starts its own {@code redis-server} ({@link RedisTestServer}); without the
 * binary it is skipped, and with {@code SOURBY_REQUIRE_REDIS=1} (CI) it fails instead. Written by an agent and not run
 * by it (owner rule).</p>
 */
class CoopCrossNodeRedisTest {

    @TempDir
    Path directory;

    private RedisTestServer server;
    private RedisFabric fabric;
    private final List<Node> nodes = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        this.server = RedisTestServer.start("--appendonly", "yes", "--appendfsync", "always");
        final RedisSettings redis = new RedisSettings(this.server.uri(), "t:coop56:", 4, 1_000, 30, false);
        final FabricSettings settings = FabricSettings.parse(Map.of(FabricSettings.ENABLED_KEY, true)).settings();
        this.fabric = RedisFabric.start(settings, redis, "node-a");
    }

    @AfterEach
    void tearDown() throws Exception {
        for (final Node node : this.nodes) node.close();
        this.nodes.clear();
        if (this.fabric != null) this.fabric.close();
        if (this.server != null) this.server.close();
    }

    /** {@link MemberPlayFixtures#playSettings()} in distributed mode on the Redis repository, with the Build 56 keys as given. */
    static ProfileSettings nodeSettings(final boolean crossNode, final boolean worldHost, final boolean switching) {
        final ProfileSettings base = playSettings();
        final ProfileSettings.Coop coop = base.coop();
        return new ProfileSettings(base.enabled(), ProfileSettings.Mode.DISTRIBUTED, base.defaultSlots(), base.maxSlots(),
            new ProfileSettings.Switching(switching, base.switching().timeoutMillis(), base.switching().requireDurableSave()),
            base.isolation(), base.worlds(), new ProfileSettings.Coordination(ProfileSettings.Provider.REDIS, true, true),
            base.directory(), new ProfileSettings.Repository(ProfileSettings.Backend.REDIS, true, false), base.types(), base.names(),
            base.migration(), new ProfileSettings.Coop(coop.enabled(), coop.maxMembers(), coop.inviteExpiryMinutes(),
                coop.kickVoteExpiryMinutes(), coop.kickVoteInactiveDays(), coop.allowedTypes(), crossNode, worldHost),
            base.shutdown(), base.login(), base.adapters());
    }

    /** The island: a world host with switching on. */
    static ProfileSettings island() {
        return nodeSettings(true, true, true);
    }

    /** The spawn node: a satellite with switching off (as {@code ProfileRuntime.validateCrossNode} requires). */
    static ProfileSettings spawn() {
        return nodeSettings(true, false, false);
    }

    /** One node: its own session fabric and incarnation, repository, claim providers, player port and engine, opened. */
    private final class Node implements AutoCloseable {

        final String id;
        final SessionFabric sessions;
        final RedisProfileRepository repository;
        final RedisProfileOwnership profiles;
        final PlayerSessionOwnership players;
        final PlayingPorts ports = new PlayingPorts();
        final ExecutorService io = Executors.newFixedThreadPool(2);
        final ProfileEngine engine;

        Node(final String id, final ProfileSettings settings, final boolean writeMarker) throws Exception {
            this.id = id;
            final RedisFabric shared = CoopCrossNodeRedisTest.this.fabric;
            this.sessions = FabricTestNodes.sessionsAs(shared, "test-" + id + "-" + UUID.randomUUID());
            this.repository = RedisProfileRepository.open(shared, this.sessions, new ProfileSettings.Repository(
                ProfileSettings.Backend.REDIS, true, false), CoopCrossNodeRedisTest.this.directory.resolve("no-file-store-" + id));
            this.repository.attachDefaultSlots(CoopFixtures.SLOTS);
            if (writeMarker) this.repository.writeStoreFormat(StoreFormat.Marker.ready(CoopFixtures.BUILD));
            this.profiles = new RedisProfileOwnership(this.sessions, id, 1_000);
            this.players = PlayerSessionOwnership.redis(this.profiles.coopClaims(), id);
            this.engine = new ProfileEngine(settings, this.repository, this.profiles, this.players, this.ports,
                ProfileWorldPort.NONE, id, this.io, null, null);
            this.engine.bankPolicy(ProfileBank.Policy.DEFAULT);
            assertEquals(StoreFormat.Gate.FORMAT_2, join(this.engine.open(CoopFixtures.NO_MIGRATION)).gate());
        }

        AuroraProfile create(final UUID player, final String name) throws Exception {
            final dev.iyanz.sourbycraft.api.profile.ProfileDetails details = join(this.engine.createDetailed(
                ProfileRequest.of(player, "skyblock", CoopFixtures.CLASSIC).withName(name)));
            return this.repository.profile(details.info().id()).orElseThrow();
        }

        AuroraProfile profile(final UUID profileId) throws Exception {
            return this.repository.profile(profileId).orElseThrow();
        }

        PlayerAccount account(final UUID player) throws Exception {
            return this.repository.loadAccount(player, CoopFixtures.SLOTS);
        }

        /** The account names {@code profile} as active (a direct commit under a short Q, released again). */
        void activate(final UUID player, final AuroraProfile profile) throws Exception {
            final PlayerSessionOwnership.Grant grant = join(this.players.claim(player, UUID.randomUUID()));
            assertTrue(grant.granted(), grant.reason());
            try {
                CoopFixtures.activate(this.repository, grant.claim(), this.repository.profile(profile.id()).orElseThrow());
            } finally {
                join(this.players.release(grant.claim()));
            }
        }

        /** The player joins without a login gate: online, session admitted. */
        void online(final UUID player) {
            this.ports.online.add(player);
            final ProfileEngine.Admission admission = join(this.engine.admitSession(player));
            assertTrue(admission.admitted(), this.id + ": " + admission.reason());
        }

        /**
         * The runtime's authoritative join: gate, admission, recovery, the node claim of the active profile (granted
         * without a claim on a satellite), the restore under the gate, then the gate is lifted. Returns the restore.
         */
        ProfileEngine.LoginRestore login(final UUID player) throws Exception {
            this.ports.online.add(player);
            final UUID gate = this.engine.gateLogin(player);
            assertNotNull(gate, "authoritative login gates the join");
            final ProfileEngine.Admission admission = join(this.engine.admitSession(player));
            assertTrue(admission.admitted(), this.id + ": " + admission.reason());
            assertEquals(0, join(this.engine.recoverPending(player)));
            final UUID active = account(player).activeProfile();
            if (active != null) {
                final ClaimResult claim = join(this.engine.claimSession(player, active));
                assertTrue(claim.granted(), this.id + ": the node claim of " + active + ": " + claim.reason());
            }
            final ProfileEngine.LoginRestore restore = join(this.engine.restoreOnLogin(player, gate, () -> false, false));
            if (restore.outcome() == ProfileEngine.LoginRestore.Outcome.REFUSED) {
                this.engine.holdLoginGate(player, gate);
            } else {
                this.engine.releaseLoginGate(player, gate);
            }
            return restore;
        }

        /** The runtime's quit: ticket, the final save of what is on the player, then the node claim and Q are released. */
        boolean quit(final UUID player) {
            final ProfileEngine.CaptureTicket ticket = this.engine.captureTicket(player);
            final CoopWriteGuard.PlayerClaim session = this.engine.playerSessionClaim(player);
            boolean saved = false;
            if (ticket != null && ticket.profileId() != null) {
                saved = join(this.engine.saveCapturedAndClose(player, PlayerStatePort.CaptureResult.of(this.ports.state(player)), ticket));
            }
            this.ports.online.remove(player);
            join(this.engine.releaseSession(player));
            if (session != null) join(this.engine.releasePlayerSession(player, session));
            return saved;
        }

        ProfileSwitchResult switchTo(final UUID player, final UUID profileId) {
            return join(this.engine.switchProfile(player, profileId));
        }

        boolean save(final UUID player, final ProfileState state) {
            return join(this.engine.saveCaptured(player, PlayerStatePort.CaptureResult.of(state), this.engine.captureTicket(player)));
        }

        MemberStateHead head(final UUID profileId, final UUID player, final UUID epoch) throws Exception {
            return this.repository.memberHead(profileId, player, epoch).orElseThrow();
        }

        int levelOf(final MemberStateHead head) throws Exception {
            assertEquals(MemberStateHead.State.READY, head.state(), head.describe());
            return ProfileStateCodec.decode(this.repository.memberSnapshot(head.profileId(), head.memberId(), head.epoch(),
                head.snapshotId()).orElseThrow().state()).level();
        }

        long sharedBalance(final UUID profileId) throws Exception {
            return this.repository.shared(profileId).map(ProfileSharedState::sharedBalance).orElse(0L);
        }

        @Override
        public void close() {
            this.engine.close();
            this.io.shutdownNow();
            this.repository.close();
        }
    }

    private Node node(final String id, final ProfileSettings settings, final boolean writeMarker) throws Exception {
        final Node node = new Node(id, settings, writeMarker);
        this.nodes.add(node);
        return node;
    }

    private static void success(final ProfileSwitchResult result) {
        assertTrue(result.success(), result.errorCode() + ": " + result.message());
    }

    /** An owner's co-op "Apple" with two members, each owning a solo profile that is their active one. */
    private record Party(UUID owner, UUID first, UUID second, UUID coop, UUID firstEpoch, UUID secondEpoch, UUID ownerEpoch,
                         AuroraProfile kiwi, AuroraProfile lime) {}

    private static Party party(final Node a) throws Exception {
        final UUID owner = UUID.randomUUID();
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();
        final AuroraProfile solo = a.create(owner, "Apple");
        join(a.engine.convertToCoop(owner, solo.id()));
        final UUID coop = solo.id();
        final AuroraProfile kiwi = a.create(first, "Kiwi");
        final AuroraProfile lime = a.create(second, "Lime");
        join(a.engine.acceptInvite(first, join(a.engine.invite(owner, coop, first)).id()));
        join(a.engine.acceptInvite(second, join(a.engine.invite(owner, coop, second)).id()));
        final AuroraProfile profile = a.profile(coop);
        a.activate(first, kiwi);
        a.activate(second, lime);
        return new Party(owner, first, second, coop, profile.member(first).epoch(), profile.member(second).epoch(),
            profile.member(owner).epoch(), kiwi, lime);
    }

    // ------------------------------------------------------------------ members on the spawn node and the island at once

    /**
     * The first member plays the co-op on the island (node A holds the node claim); the second member, whose account
     * names the co-op, joins the spawn node (B, a satellite): the join is admitted without a node claim, the restore is
     * {@code RESTORED} (not {@code WRONG_NODE}), B prepares no world, each member's stream moves only with that member's
     * saves, and B's quit publishes the second member's head and releases nothing the island holds. Then the second
     * member joins the island too: two members on the same world host, as in Build 55.
     */
    @Test
    void CoopMembersOnSpawnAndIslandAtOnce() throws Exception {
        final Node a = node("island", island(), true);
        final Node b = node("spawn", spawn(), false);
        final Party p = party(a);

        // The first member plays the co-op on the island (a member switch into a co-op over Redis).
        a.ports.live.put(p.first(), level(1));
        a.online(p.first());
        success(a.switchTo(p.first(), p.coop()));
        assertTrue(a.save(p.first(), level(11)));
        final MemberStateHead firstSaved = a.head(p.coop(), p.first(), p.firstEpoch());
        assertEquals(11, a.levelOf(firstSaved));
        assertNotNull(join(a.profiles.holder(p.coop())), "the island holds the co-op's node claim");

        // The second member's account names the co-op (played before); they join the spawn node.
        a.activate(p.second(), a.profile(p.coop()));
        b.ports.live.put(p.second(), level(5));
        final ProfileEngine.LoginRestore restore = b.login(p.second());
        assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, restore.outcome(), restore.detail());
        assertTrue(b.engine.satelliteSession(p.second()), "a satellite records the session without a node claim");
        assertTrue(b.engine.holdsSession(p.second()), "the quit and the lockstep save see the session");
        assertEquals("island", join(b.profiles.holder(p.coop())).node(), "the node claim stayed with the island");
        assertNull(join(a.profiles.foreignHolder(p.coop())), "to the island, no other node holds the co-op");
        assertEquals(0, b.ports.levelOf(p.second()), "the first entry applies the member's empty initial state");
        assertFalse(join(b.engine.prepareActive(p.second(), "spawn")), "a satellite prepares no bound world");

        // Each member's stream is their own: a save on B moves only the second member's head.
        assertTrue(b.save(p.second(), level(22)));
        final MemberStateHead secondSaved = b.head(p.coop(), p.second(), p.secondEpoch());
        assertEquals(22, b.levelOf(secondSaved));
        assertEquals(firstSaved, a.head(p.coop(), p.first(), p.firstEpoch()), "the spawn node's save moved the island member's stream");
        assertTrue(a.save(p.first(), level(12)));
        assertEquals(secondSaved, b.head(p.coop(), p.second(), p.secondEpoch()), "the island's save moved the spawn member's stream");
        assertNotEquals(a.head(p.coop(), p.first(), p.firstEpoch()).snapshotId(), secondSaved.snapshotId());

        // B's quit publishes the second member's head; the island keeps its claim and its member keeps playing.
        b.ports.live.put(p.second(), level(23));
        assertTrue(b.quit(p.second()));
        assertEquals(23, b.levelOf(b.head(p.coop(), p.second(), p.secondEpoch())));
        assertFalse(b.engine.holdsSession(p.second()));
        assertEquals("island", join(b.profiles.holder(p.coop())).node());
        assertTrue(a.save(p.first(), level(13)));

        // The second member now joins the island: two members on the world host, Build 55 behaviour.
        a.ports.live.put(p.second(), level(0));
        final ProfileEngine.LoginRestore onIsland = a.login(p.second());
        assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, onIsland.outcome(), onIsland.detail());
        assertFalse(a.engine.satelliteSession(p.second()), "a world host records the node claim");
        assertEquals(23, a.ports.levelOf(p.second()), "the island restores the head the spawn node's quit published");
        assertTrue(a.save(p.second(), level(24)));
        assertEquals(24, a.levelOf(a.head(p.coop(), p.second(), p.secondEpoch())));
        assertEquals(13, a.levelOf(a.head(p.coop(), p.first(), p.firstEpoch())));
    }

    // ------------------------------------------------------------------ bank from each node

    /**
     * The owner (island) and the second member (spawn node) both have the co-op active. A deposit from each node posts
     * (conservation over both); two deposits fired at once from both nodes either both post or one is refused
     * {@code OWNERSHIP_CONFLICT} by the transaction claim {@code P} with nothing written, and a retry of the refused one
     * posts; with {@code P} held on the island, the spawn node's deposit is refused with nothing written and succeeds
     * once it is released.
     */
    @Test
    void CoopBankFromEachNode() throws Exception {
        final Node a = node("island", island(), true);
        final Node b = node("spawn", spawn(), false);
        final Party p = party(a);
        a.activate(p.owner(), a.profile(p.coop()));
        a.activate(p.second(), a.profile(p.coop()));
        a.ports.live.put(p.owner(), level(1));
        assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, a.login(p.owner()).outcome());
        b.ports.live.put(p.second(), level(1));
        assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, b.login(p.second()).outcome());

        assertEquals(100L, join(a.engine.deposit(p.owner(), p.coop(), 100L)).sharedBalance());
        assertEquals(150L, join(b.engine.deposit(p.second(), p.coop(), 50L)).sharedBalance(), "the spawn node posts on the shared record");
        assertEquals(150L, a.sharedBalance(p.coop()));
        assertEquals(150L, b.sharedBalance(p.coop()));

        // Both nodes at once: the transaction claim serialises them; a refused posting writes nothing and is retried.
        final CompletableFuture<ProfileBank.View> fromA = a.engine.deposit(p.owner(), p.coop(), 7L);
        final CompletableFuture<ProfileBank.View> fromB = b.engine.deposit(p.second(), p.coop(), 5L);
        long posted = 150L;
        for (final CompletableFuture<ProfileBank.View> posting : List.of(fromA, fromB)) {
            try {
                posted += posting == fromA ? 7L : 5L;
                join(posting);
            } catch (final AssertionError | RuntimeException failed) {
                final Throwable cause = failed instanceof RuntimeException ? failed : failed.getCause();
                assertEquals(ProfileErrorCode.OWNERSHIP_CONFLICT, ProfileEngine.codeOf(cause), String.valueOf(cause));
                posted -= posting == fromA ? 7L : 5L;
                assertEquals(posted, a.sharedBalance(p.coop()), "a refused posting wrote nothing");
                // The retry posts.
                if (posting == fromA) {
                    join(a.engine.deposit(p.owner(), p.coop(), 7L));
                    posted += 7L;
                } else {
                    join(b.engine.deposit(p.second(), p.coop(), 5L));
                    posted += 5L;
                }
            }
        }
        assertEquals(162L, posted);
        assertEquals(162L, a.sharedBalance(p.coop()), "every posting counted exactly once");
        assertEquals(162L, b.sharedBalance(p.coop()));

        // P held on the island: the spawn node's deposit is refused with nothing written; released, it posts.
        final ProfileOwnership.TransactionGrant held = join(a.profiles.claimTransaction(p.coop(), UUID.randomUUID()));
        assertTrue(held.granted(), held.reason());
        refusedWith(ProfileErrorCode.OWNERSHIP_CONFLICT, () -> join(b.engine.deposit(p.second(), p.coop(), 1L)));
        assertEquals(162L, b.sharedBalance(p.coop()));
        assertTrue(join(a.profiles.releaseTransaction(held.claim())));
        assertEquals(163L, join(b.engine.deposit(p.second(), p.coop(), 1L)).sharedBalance());
        assertEquals(163L, a.sharedBalance(p.coop()));
    }

    // ------------------------------------------------------------------ membership while a member is on the other node

    /**
     * While the first member plays the co-op on the island: an invite the owner issues is accepted by a third player on
     * the spawn node (the satellite skips the OD-1 node check; the accept commits under {@code P} and the invitee's
     * {@code Q}); a leave by the second member while they play the co-op on the spawn node is {@code MEMBER_ONLINE}
     * (switching is off there, so they cannot be switched out); the same leave run from the island while they play on the
     * spawn node is {@code MEMBER_ONLINE} too (their {@code Q} is held there); an operator lock from the island is refused
     * while they play it on the spawn node; once they quit, the lock from the island goes through.
     */
    @Test
    void CoopMembershipChangeWhileMemberOnOtherNode() throws Exception {
        final Node a = node("island", island(), true);
        final Node b = node("spawn", spawn(), false);
        final Party p = party(a);
        final UUID third = UUID.randomUUID();
        final AuroraProfile mango = a.create(third, "Mango");
        a.activate(third, mango);

        // The first member plays the co-op on the island.
        a.ports.live.put(p.first(), level(1));
        a.online(p.first());
        success(a.switchTo(p.first(), p.coop()));

        // An invite accepted on the spawn node while the island holds the co-op's node claim.
        b.ports.live.put(third, level(3));
        assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, b.login(third).outcome());
        final UUID inviteId = join(a.engine.invite(p.owner(), p.coop(), third)).id();
        join(b.engine.acceptInvite(third, inviteId));
        final AuroraProfile joined = b.profile(p.coop());
        assertNotNull(joined.member(third), "the accept on the spawn node admitted the member");
        assertEquals(4, joined.members().size());
        assertTrue(a.account(third).profileIds().contains(p.coop()), "the account gained the co-op slot");

        // The second member plays the co-op on the spawn node.
        a.activate(p.second(), a.profile(p.coop()));
        b.ports.live.put(p.second(), level(5));
        assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, b.login(p.second()).outcome());
        assertTrue(b.save(p.second(), level(6)));

        // A leave on the spawn node while playing it: switching is off there, no switch-out, nothing written.
        final ProfileOperationException leaveHere = CoopFixtures.refusal(() -> join(b.engine.leave(p.second(), p.coop())));
        assertEquals(ProfileErrorCode.MEMBER_ONLINE, leaveHere.code(), leaveHere.getMessage());
        assertNotNull(a.profile(p.coop()).member(p.second()), "the member stayed");
        // The same leave run from the island: the member's Q is held on the spawn node.
        final ProfileOperationException leaveThere = CoopFixtures.refusal(() -> join(a.engine.leave(p.second(), p.coop())));
        assertEquals(ProfileErrorCode.MEMBER_ONLINE, leaveThere.code(), leaveThere.getMessage());
        assertNotNull(a.profile(p.coop()).member(p.second()));
        assertEquals(6, b.levelOf(b.head(p.coop(), p.second(), p.secondEpoch())), "the member's stream is untouched");

        // An operator lock from the island while the second member plays the co-op on the spawn node is refused: the
        // satellite holds no node claim, so the members' session claims are what the island looks at.
        a.ports.live.put(p.first(), level(2));
        success(a.switchTo(p.first(), p.kiwi().id()));
        assertNull(join(a.profiles.foreignHolder(p.coop())), "no node claim names another node");
        final ProfileOperationException lock = CoopFixtures.refusal(() -> join(a.engine.setLocked(p.coop(), true)));
        assertEquals(ProfileErrorCode.OWNERSHIP_CONFLICT, lock.code(), lock.getMessage());
        assertTrue(lock.getMessage().contains("spawn"), lock.getMessage());
        assertEquals(ProfileStatus.ACTIVE, a.profile(p.coop()).status());

        // Once the member quit the spawn node (their quit published their head), the leave from the island repoints them.
        b.ports.live.put(p.second(), level(7));
        assertTrue(b.quit(p.second()));
        join(a.engine.leave(p.second(), p.coop()));
        assertNull(a.profile(p.coop()).member(p.second()), "the member left");
        assertEquals(p.lime().id(), a.account(p.second()).activeProfile(), "the removal moved the account to the solo profile");
    }

    // ------------------------------------------------------------------ default: cross-node off is Build 55.1

    /**
     * With {@code coop.cross-node: false} on both nodes (the default) nothing changes: while the island plays the co-op,
     * the other node's node claim is refused, its restore is {@code WRONG_NODE}, its co-op operations are
     * {@code WRONG_NODE}, and it records no satellite session.
     */
    @Test
    void CoopCrossNodeOffKeepsWrongNode() throws Exception {
        final Node a = node("island", nodeSettings(false, true, true), true);
        final Node b = node("other", nodeSettings(false, true, false), false);
        final Party p = party(a);
        a.ports.live.put(p.first(), level(1));
        a.online(p.first());
        success(a.switchTo(p.first(), p.coop()));

        a.activate(p.second(), a.profile(p.coop()));
        b.ports.live.put(p.second(), level(5));
        b.ports.online.add(p.second());
        final UUID gate = b.engine.gateLogin(p.second());
        assertTrue(join(b.engine.admitSession(p.second())).admitted());
        final ClaimResult claim = join(b.engine.claimSession(p.second(), p.coop()));
        assertFalse(claim.granted(), "OD-1: the island holds the node claim");
        assertEquals("island", claim.claim().node());
        assertFalse(b.engine.satelliteSession(p.second()));
        assertFalse(b.engine.holdsSession(p.second()));
        final ProfileEngine.LoginRestore restore = join(b.engine.restoreOnLogin(p.second(), gate, () -> false, false));
        b.engine.holdLoginGate(p.second(), gate);
        assertEquals(ProfileEngine.LoginRestore.Outcome.REFUSED, restore.outcome());
        assertEquals(ProfileErrorCode.WRONG_NODE, restore.code(), restore.detail());
        assertEquals(5, b.ports.levelOf(p.second()), "nothing was applied");
        refusedWith(ProfileErrorCode.WRONG_NODE, () -> join(b.engine.deposit(p.owner(), p.coop(), 1L)));
        refusedWith(ProfileErrorCode.WRONG_NODE, () -> join(b.engine.setCoopLabel(p.owner(), p.coop(), "Pears")));
    }
}
