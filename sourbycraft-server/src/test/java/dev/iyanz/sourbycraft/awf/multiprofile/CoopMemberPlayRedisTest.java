package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.join;
import static dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.level;
import static dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.playSettings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileRequest;
import dev.iyanz.sourbycraft.api.profile.ProfileSwitchResult;
import dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.PlayingPorts;
import dev.iyanz.sourbycraft.awf.redis.RedisSettings;
import dev.iyanz.sourbycraft.awf.redis.RedisTestServer;
import dev.iyanz.sourbycraft.awf.redis.fabric.FabricSettings;
import dev.iyanz.sourbycraft.awf.redis.fabric.FabricTestNodes;
import dev.iyanz.sourbycraft.awf.redis.fabric.RedisFabric;
import dev.iyanz.sourbycraft.awf.redis.fabric.SessionFabric;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Build 53 member-play cases on the shared Redis store, with two engines as two nodes (COOP-DESIGN §6, §10 slice
 * 5, OD-1): a member switches into a co-op over Redis, each member's stream is its own on either node, and only the
 * node that holds the co-op plays it ({@code WRONG_NODE} elsewhere, no routing). {@link RedisFabric} is one per
 * process by design, so the test runs one fabric and gives each node its own {@link SessionFabric} with its own
 * incarnation on it ({@link FabricTestNodes#sessionsAs}): to {@code foreignHolder} and the claim scripts the two nodes
 * are two processes, each with its own repository (opened on that node's session fabric, so its write scripts compare
 * the node's own {@code node/incarnation/owner}), claim providers, player port and engine. Each test starts its own
 * {@code redis-server} ({@link RedisTestServer}); without the binary it is skipped, and with
 * {@code SOURBY_REQUIRE_REDIS=1} (CI) it fails instead.
 */
class CoopMemberPlayRedisTest {

    @TempDir
    Path directory;

    private RedisTestServer server;
    private RedisFabric fabric;
    private final List<Node> nodes = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        this.server = RedisTestServer.start("--appendonly", "yes", "--appendfsync", "always");
        final RedisSettings redis = new RedisSettings(this.server.uri(), "t:coop53:", 4, 1_000, 30, false);
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

    /**
     * One node: its own session fabric and incarnation on the shared fabric (the JVM's incarnation would make every node
     * the same process to {@code foreignHolder}), repository, Redis claim providers, a stateful player port and an
     * engine, opened.
     */
    private final class Node implements AutoCloseable {

        final String id;
        final SessionFabric sessions;
        final RedisProfileRepository repository;
        final RedisProfileOwnership profiles;
        final PlayerSessionOwnership players;
        final PlayingPorts ports = new PlayingPorts();
        final ExecutorService io = Executors.newFixedThreadPool(2);
        final ProfileEngine engine;

        Node(final String id, final boolean writeMarker) throws Exception {
            this.id = id;
            final RedisFabric shared = CoopMemberPlayRedisTest.this.fabric;
            this.sessions = FabricTestNodes.sessionsAs(shared, "test-" + id + "-" + UUID.randomUUID());
            // The repository presents its writes' claims as this node's incarnation, as one process's repository and
            // ownership share fabric.sessions(); on the fabric's own sessions every write of this node would be fenced.
            this.repository = RedisProfileRepository.open(shared, this.sessions, new ProfileSettings.Repository(
                ProfileSettings.Backend.REDIS, true, false), CoopMemberPlayRedisTest.this.directory.resolve("no-file-store-" + id));
            this.repository.attachDefaultSlots(CoopFixtures.SLOTS);
            if (writeMarker) this.repository.writeStoreFormat(StoreFormat.Marker.ready(CoopFixtures.BUILD));
            this.profiles = new RedisProfileOwnership(this.sessions, id, 1_000);
            this.players = PlayerSessionOwnership.redis(this.profiles.coopClaims(), id);
            this.engine = new ProfileEngine(playSettings(), this.repository, this.profiles, this.players, this.ports,
                ProfileWorldPort.NONE, id, this.io, null, null);
            assertEquals(StoreFormat.Gate.FORMAT_2, join(this.engine.open(CoopFixtures.NO_MIGRATION)).gate());
        }

        AuroraProfile create(final UUID player, final String name) throws Exception {
            final dev.iyanz.sourbycraft.api.profile.ProfileDetails details = join(this.engine.createDetailed(
                ProfileRequest.of(player, "skyblock", CoopFixtures.CLASSIC).withName(name)));
            return this.repository.profile(details.info().id()).orElseThrow();
        }

        /** The account names {@code profile} as active (a direct switch commit under a short Q, released again). */
        void activate(final UUID player, final AuroraProfile profile) throws Exception {
            final PlayerSessionOwnership.Grant grant = join(this.players.claim(player, UUID.randomUUID()));
            assertTrue(grant.granted(), grant.reason());
            try {
                CoopFixtures.activate(this.repository, grant.claim(), this.repository.profile(profile.id()).orElseThrow());
            } finally {
                join(this.players.release(grant.claim()));
            }
        }

        void online(final UUID player) {
            this.ports.online.add(player);
            final ProfileEngine.Admission admission = join(this.engine.admitSession(player));
            assertTrue(admission.admitted(), this.id + ": " + admission.reason());
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

        @Override
        public void close() {
            this.engine.close();
            this.io.shutdownNow();
            this.repository.close();
        }
    }

    private Node node(final String id, final boolean writeMarker) throws Exception {
        final Node node = new Node(id, writeMarker);
        this.nodes.add(node);
        return node;
    }

    private static void success(final ProfileSwitchResult result) {
        assertTrue(result.success(), result.errorCode() + ": " + result.message());
    }

    /**
     * Two members of one co-op on two nodes: while node A plays the co-op, the member on node B is refused
     * ({@code WRONG_NODE}) and can still save their own solo stream; each member's co-op stream moves only with that
     * member's own saves; once A no longer holds the co-op, B plays it, and then A is refused.
     */
    @Test
    void CoopTwoNodesIndependentStreams() throws Exception {
        final Node a = node("node-a", true);
        final Node b = node("node-b", false);
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
        final AuroraProfile profile = a.repository.profile(coop).orElseThrow();
        final UUID firstEpoch = profile.member(first).epoch();
        final UUID secondEpoch = profile.member(second).epoch();
        final UUID limeEpoch = lime.member(second).epoch();
        a.activate(first, kiwi);
        a.activate(second, lime);

        // The first member plays the co-op on node A (a member switch into a co-op over Redis).
        a.ports.live.put(first, level(1));
        a.online(first);
        success(a.switchTo(first, coop));
        assertEquals(coop, a.repository.loadAccount(first, CoopFixtures.SLOTS).activeProfile());
        assertEquals(0, a.ports.levelOf(first), "the first entry applies the empty initial state");
        assertTrue(a.save(first, level(11)));
        final MemberStateHead firstSaved = a.head(coop, first, firstEpoch);
        assertEquals(11, a.levelOf(firstSaved));

        // The second member, on node B: OD-1 refuses the co-op there; their own stream is theirs to save.
        b.ports.live.put(second, level(5));
        b.online(second);
        final MemberStateHead secondCoop = b.head(coop, second, secondEpoch);
        final ProfileSwitchResult refused = b.switchTo(second, coop);
        assertEquals(ProfileErrorCode.WRONG_NODE, refused.errorCode(), refused.message());
        assertTrue(b.save(second, level(6)));
        assertEquals(6, b.levelOf(b.head(lime.id(), second, limeEpoch)));
        assertEquals(firstSaved, a.head(coop, first, firstEpoch), "node B's save moved node A's member stream");
        assertEquals(secondCoop, b.head(coop, second, secondEpoch));

        // Node A lets the co-op go (the first member switches out); node B plays it now.
        a.ports.live.put(first, level(12));
        success(a.switchTo(first, kiwi.id()));
        final MemberStateHead firstLeft = a.head(coop, first, firstEpoch);
        assertEquals(12, a.levelOf(firstLeft));
        success(b.switchTo(second, coop));
        assertTrue(b.save(second, level(22)));
        final MemberStateHead secondSaved = b.head(coop, second, secondEpoch);
        assertEquals(22, b.levelOf(secondSaved));
        assertNotEquals(firstLeft.snapshotId(), secondSaved.snapshotId());
        assertEquals(firstLeft, a.head(coop, first, firstEpoch), "the second member's play moved the first member's stream");

        // And now node A is the other node.
        final ProfileSwitchResult wrongNode = a.switchTo(first, coop);
        assertEquals(ProfileErrorCode.WRONG_NODE, wrongNode.errorCode(), wrongNode.message());
        assertEquals(kiwi.id(), a.repository.loadAccount(first, CoopFixtures.SLOTS).activeProfile());
        assertEquals(12, a.levelOf(a.head(coop, first, firstEpoch)));
    }
}
