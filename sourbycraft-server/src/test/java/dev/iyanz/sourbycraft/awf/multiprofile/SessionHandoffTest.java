package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.join;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import dev.iyanz.sourbycraft.awf.redis.fabric.ClaimResult;
import dev.iyanz.sourbycraft.awf.redis.fabric.OwnerClaim;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Build 55.1, {@code coordination.handoff-wait-ms}: a join whose session claim {@code Q} (or active-profile node claim)
 * is held by another live session retries the claim within a bounded window and never takes a claim that is still
 * live. Written by an agent and not run by it (owner rule); CI runs it on push. The "other node" is a claim taken
 * directly on the shared ownership tables under the node id {@code node-b}, as a previous server whose quit has not
 * released it yet would hold it. Timings are generous (hundreds of milliseconds against windows of seconds).
 */
class SessionHandoffTest {

    @TempDir
    Path root;

    /** The real engine over a format-2 FILE store with its session and profile claims under this test's control. */
    private static final class Node implements AutoCloseable {
        final ExecutorService io = Executors.newFixedThreadPool(2);
        final LocalProfileOwnership profiles = new LocalProfileOwnership("node-a", 600);
        /** Tagged {@code node-b}: a claim taken here directly reads as another node's session. */
        final PlayerSessionOwnership players = PlayerSessionOwnership.local("node-b", 600);
        final MemberPlayFixtures.PlayingPorts ports = new MemberPlayFixtures.PlayingPorts();
        final FileProfileRepository file;
        final ProfileEngine engine;

        Node(final Path root, final int handoffWaitMillis) throws IOException {
            this.file = new FileProfileRepository(root, "node-a");
            if (this.file.storeFormat().legacyFormat()) this.file.writeStoreFormat(StoreFormat.Marker.ready(CoopFixtures.BUILD));
            this.file.attachClaims(FileProfileClaims.of(this.profiles, this.players));
            final ProfileSettings settings = new ProfileSettings(true, ProfileSettings.Mode.STANDALONE, CoopFixtures.SLOTS, 8,
                new ProfileSettings.Switching(true, 15_000, true), ProfileIsolation.ALL, new ProfileSettings.Worlds(0),
                new ProfileSettings.Coordination(ProfileSettings.Provider.LOCAL, true, true, handoffWaitMillis), "awf/profiles",
                ProfileSettings.DEFAULT_REPOSITORY, ProfileSettings.DEFAULT_TYPES, ProfileSettings.DEFAULT_NAMES,
                ProfileSettings.DEFAULT_MIGRATION);
            this.engine = new ProfileEngine(settings, this.file, this.profiles, this.players, this.ports, ProfileWorldPort.NONE,
                "node-a", this.io, null, null);
            assertEquals(StoreFormat.Gate.FORMAT_2, join(this.engine.open(CoopFixtures.NO_MIGRATION)).gate());
        }

        /** The previous server still holds the player's session claim. */
        PlayerClaim holdSessionElsewhere(final UUID player) {
            final PlayerSessionOwnership.Grant grant = join(this.players.claim(player, UUID.randomUUID()));
            assertTrue(grant.granted(), grant.reason());
            return grant.claim();
        }

        /** The previous server still holds the node claim on the player's active profile. */
        OwnerClaim holdProfileElsewhere(final UUID profileId) {
            final ClaimResult claim = join(this.profiles.claim(profileId, "node-b", UUID.randomUUID()));
            assertTrue(claim.granted(), claim.reason());
            return claim.claim();
        }

        ProfileMetrics.Snapshot metrics() {
            return this.engine.metricsSnapshot();
        }

        @Override
        public void close() {
            this.engine.close();
            this.io.shutdownNow();
        }
    }

    private static <T> T within(final CompletableFuture<T> future, final long millis) throws Exception {
        return future.get(millis, TimeUnit.MILLISECONDS);
    }

    @Test
    void theAdmissionWaitsUntilThePreviousServerReleasesTheSessionClaim() throws Exception {
        try (Node node = new Node(this.root, 5_000)) {
            final UUID player = UUID.randomUUID();
            node.ports.online.add(player);
            final PlayerClaim previous = node.holdSessionElsewhere(player);
            final AtomicBoolean told = new AtomicBoolean();
            final ProfileEngine.HandoffWindow window = node.engine.handoffWindow().onWait(() -> told.set(true));
            final long started = System.nanoTime();
            final CompletableFuture<ProfileEngine.Admission> admission = node.engine.admitSession(player, window, () -> false);

            Thread.sleep(400L);
            assertFalse(admission.isDone(), "the join must keep waiting while the previous server holds Q");
            assertTrue(told.get(), "the runtime hook runs once the wait starts");
            assertTrue(window.waited());
            assertNull(node.engine.playerSessionClaim(player), "no session is recorded while Q is held elsewhere");
            assertEquals(previous, join(node.players.holder(player)), "the live claim is never replaced by a retry");

            // The previous server's quit: the save committed, then Q is released (the FIFO order of its quit path).
            assertTrue(join(node.players.release(previous)));
            final ProfileEngine.Admission admitted = within(admission, 3_000L);
            final long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(admitted.admitted(), admitted.reason());
            assertTrue(waitedMillis >= 400L, "admitted only after the release: " + waitedMillis + " ms");
            final PlayerClaim mine = node.engine.playerSessionClaim(player);
            assertNotNull(mine, "the join holds Q now");
            assertEquals(mine, join(node.players.holder(player)));
            assertTrue(mine.fence() > previous.fence(), "a released claim is replaced under a higher fence, never reused");

            final ProfileMetrics.Snapshot m = node.metrics();
            assertEquals(1L, m.handoffWaits());
            assertEquals(1L, m.handoffSucceeded());
            assertEquals(0L, m.handoffTimedOut());
            assertEquals(0L, m.handoffAbandoned());
            assertTrue(m.handoffWaitP50Millis() >= 400L, "the admitted wait is sampled: " + m.handoffWaitP50Millis());
        }
    }

    @Test
    void theWindowElapsesWithTheBuild55RefusalAndNeverTakesTheLiveClaim() throws Exception {
        try (Node node = new Node(this.root, 700)) {
            final UUID player = UUID.randomUUID();
            node.ports.online.add(player);
            final PlayerClaim previous = node.holdSessionElsewhere(player);
            final long started = System.nanoTime();
            final ProfileEngine.Admission refused = within(node.engine.admitSession(player, node.engine.handoffWindow(), () -> false), 5_000L);
            final long waitedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertFalse(refused.admitted());
            assertEquals("node-b", refused.holderNode(), "the refusal names the holder exactly as Build 55 did");
            assertEquals("held", refused.reason());
            assertTrue(waitedMillis >= 700L, "refused only once the window elapsed: " + waitedMillis + " ms");
            assertEquals(previous, join(node.players.holder(player)), "the previous server's claim is untouched");
            assertTrue(join(node.players.validate(previous)), "the previous server's claim is still live");
            assertNull(node.engine.playerSessionClaim(player), "nothing was admitted on this node");

            final ProfileMetrics.Snapshot m = node.metrics();
            assertEquals(1L, m.handoffWaits());
            assertEquals(0L, m.handoffSucceeded());
            assertEquals(1L, m.handoffTimedOut());
            assertEquals(-1L, m.handoffWaitP50Millis(), "no admitted wait, no sample");
        }
    }

    @Test
    void theDefaultOfZeroIsTheSingleAttemptOfBuild55() throws Exception {
        assertEquals(0, ProfileSettings.DEFAULT.coordination().handoffWaitMillis());
        try (Node node = new Node(this.root, 0)) {
            final UUID player = UUID.randomUUID();
            node.ports.online.add(player);
            final PlayerClaim previous = node.holdSessionElsewhere(player);
            final ProfileEngine.HandoffWindow window = node.engine.handoffWindow();
            assertTrue(window.expired(), "a wait of 0 is an expired window");
            assertEquals(0L, window.remainingMillis());
            final long started = System.nanoTime();
            final ProfileEngine.Admission refused = within(node.engine.admitSession(player, window, () -> false), 5_000L);
            final long tookMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertFalse(refused.admitted());
            assertEquals("node-b", refused.holderNode());
            assertTrue(tookMillis < 500L, "one attempt, no wait: " + tookMillis + " ms");
            assertFalse(window.waited());
            assertEquals(previous, join(node.players.holder(player)));
            assertEquals(0L, node.metrics().handoffWaits(), "nothing is counted without a wait");
            // The plain overload is the same single attempt.
            assertFalse(join(node.engine.admitSession(player)).admitted());
        }
    }

    @Test
    void aJoinThatWentStaleStopsWaitingWithTheRefusal() throws Exception {
        try (Node node = new Node(this.root, 5_000)) {
            final UUID player = UUID.randomUUID();
            node.ports.online.add(player);
            final PlayerClaim previous = node.holdSessionElsewhere(player);
            final AtomicBoolean left = new AtomicBoolean();
            final CompletableFuture<ProfileEngine.Admission> admission =
                node.engine.admitSession(player, node.engine.handoffWindow(), left::get);
            Thread.sleep(250L);
            assertFalse(admission.isDone());
            left.set(true);
            final ProfileEngine.Admission refused = within(admission, 3_000L);
            assertFalse(refused.admitted());
            assertEquals("node-b", refused.holderNode());
            assertEquals(previous, join(node.players.holder(player)));
            assertNull(node.engine.playerSessionClaim(player));
            final ProfileMetrics.Snapshot m = node.metrics();
            assertEquals(1L, m.handoffWaits());
            assertEquals(1L, m.handoffAbandoned());
            assertEquals(0L, m.handoffTimedOut());
        }
    }

    @Test
    void closingTheEngineEndsAWaitWithTheRefusal() throws Exception {
        final Node node = new Node(this.root, 5_000);
        try {
            final UUID player = UUID.randomUUID();
            node.ports.online.add(player);
            node.holdSessionElsewhere(player);
            final CompletableFuture<ProfileEngine.Admission> admission =
                node.engine.admitSession(player, node.engine.handoffWindow(), () -> false);
            Thread.sleep(250L);
            assertFalse(admission.isDone());
            node.engine.close();
            final ProfileEngine.Admission refused = within(admission, 3_000L);
            assertFalse(refused.admitted(), "the join is refused, so its login gate is released at shutdown");
            assertEquals("node-b", refused.holderNode());
        } finally {
            node.io.shutdownNow();
        }
    }

    @Test
    void theNodeClaimOfTheActiveProfileWaitsUnderTheSameWindow() throws Exception {
        try (Node node = new Node(this.root, 5_000)) {
            final UUID player = UUID.randomUUID();
            final UUID profileId = UUID.randomUUID();
            node.ports.online.add(player);
            final ProfileEngine.HandoffWindow window = node.engine.handoffWindow();
            assertTrue(join(node.engine.admitSession(player, window, () -> false)).admitted(), "Q is free");
            final OwnerClaim previous = node.holdProfileElsewhere(profileId);
            final CompletableFuture<ClaimResult> claim = node.engine.claimSession(player, profileId, window, () -> false);
            Thread.sleep(300L);
            assertFalse(claim.isDone(), "the node claim is held by node-b");
            assertEquals(previous, join(node.profiles.holder(profileId)), "the retry never replaces a live node claim");

            assertTrue(join(node.profiles.release(previous)));
            final ClaimResult granted = within(claim, 3_000L);
            assertTrue(granted.granted(), granted.reason());
            assertEquals("node-a", granted.claim().node());
            assertTrue(granted.claim().epoch() > previous.epoch(), "a new incarnation of the claim, not the released one");
            final ProfileMetrics.Snapshot m = node.metrics();
            assertEquals(1L, m.handoffWaits());
            assertEquals(1L, m.handoffSucceeded());
        }
    }

    @Test
    void aJoinOnAClosedEngineFailsAtOnceAsInBuild55() throws Exception {
        final Node node = new Node(this.root, 5_000);
        try {
            final UUID player = UUID.randomUUID();
            node.ports.online.add(player);
            final PlayerClaim previous = node.holdSessionElsewhere(player);
            node.engine.close();
            final long started = System.nanoTime();
            final CompletableFuture<ProfileEngine.Admission> admission = node.engine.admitSession(player, node.engine.handoffWindow(), () -> false);
            // No I/O step and no retry: the future already failed with the closed-engine code the runtime turns into a kick.
            assertTrue(admission.isCompletedExceptionally(), "failed before any claim attempt");
            final java.util.concurrent.ExecutionException failed = org.junit.jupiter.api.Assertions.assertThrows(
                java.util.concurrent.ExecutionException.class, () -> within(admission, 3_000L));
            assertTrue(failed.getCause() instanceof dev.iyanz.sourbycraft.api.profile.ProfileOperationException refused
                && refused.code() == dev.iyanz.sourbycraft.api.profile.ProfileErrorCode.INTERNAL_ERROR, String.valueOf(failed.getCause()));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000L, "no retry is scheduled after close");
            assertEquals(previous, join(node.players.holder(player)), "the holder's claim is untouched");
            assertEquals(0L, node.metrics().handoffWaits(), "no wait started");
        } finally {
            node.io.shutdownNow();
        }
    }
}
