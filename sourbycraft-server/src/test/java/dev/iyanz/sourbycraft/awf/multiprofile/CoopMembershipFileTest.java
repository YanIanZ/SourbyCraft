package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.CLASSIC;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.SLOTS;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.WAIT_SECONDS;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.committed;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.op;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusal;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusedWith;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.tree;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.Expected;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ProfileClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileRepository.CommitResult;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Build 52 membership intents on the FILE store, at repository level (COOP-DESIGN §5, §9, §10 slice 4): accept and
 * kick recovered at every crash point, all-or-nothing visibility of a membership and the account's access, the
 * accept capacity race, an invite that expires before its accept commits, the one-way conversion and the §9 bounds
 * at commit. Plans are built by {@link CoopMembership} and run exactly as the engine runs them; a crash is a closed
 * and reopened repository, with record files edited in between where a crash point needs it.
 */
class CoopMembershipFileTest {

    @TempDir
    Path directory;

    private Path store() {
        return this.directory.resolve("store");
    }

    private Path scratch(final String name) {
        return this.directory.resolve("scratch-" + name);
    }

    /** A co-op of a new owner with its P held: the owner's Q, P, and the stored co-op profile. */
    private record Coop(UUID owner, PlayerClaim q, ProfileClaim p, AuroraProfile profile) {}

    private static Coop coop(final CoopFixtures.FileStore store, final String name) throws Exception {
        final UUID owner = UUID.randomUUID();
        final PlayerClaim q = store.q(owner);
        final AuroraProfile solo = CoopFixtures.create(store.repository, q, name, CLASSIC);
        final ProfileClaim p = store.p(solo.id());
        return new Coop(owner, q, p, convert(store.repository, p, q, solo));
    }

    // ------------------------------------------------------------------ accept: crash at every phase

    @Test
    void CoopAcceptIntentCrashAtEveryPhase() throws Exception {
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(store())) {
            final Coop coop = coop(store, "Apple");
            final UUID profileId = coop.profile().id();

            // 1. Crash after staging, before PREPARE: nothing is visible; the same plan can still run.
            final PlayerClaim q1 = store.q(UUID.randomUUID());
            final ProfileInvite i1 = invite(store.repository, coop.p(), coop.q(), profileId, q1.playerId());
            final CoopIntentPlan a1 = acceptPlan(store.repository, i1);
            a1.stage(store.repository);
            store.reopen();
            assertTrue(store.repository.intent(a1.op().operationId()).isEmpty());
            assertNotAccepted(store.repository, profileId, i1);
            assertEquals(0L, account(store.repository, q1.playerId()).revision());
            runPlan(store.repository, a1, coop.p(), q1, null);
            assertAccepted(store.repository, a1, i1);

            // 2. Crash after PREPARED: the reservations survive, nothing is visible, abort clears exactly them, and the
            //    still-open invite can be accepted by a new operation (a new epoch).
            final PlayerClaim q2 = store.q(UUID.randomUUID());
            final ProfileInvite i2 = invite(store.repository, coop.p(), coop.q(), profileId, q2.playerId());
            final CoopIntentPlan a2 = acceptPlan(store.repository, i2);
            final long m2 = profile(store.repository, profileId).revision();
            preparePlan(store.repository, a2, coop.p(), q2, null);
            store.reopen();
            assertEquals(ProfileIntent.Phase.PREPARED, store.repository.intent(a2.op().operationId()).orElseThrow().phase());
            assertEquals(a2.op().operationId(), profile(store.repository, profileId).pendingIntent());
            assertEquals(m2 + 1L, profile(store.repository, profileId).revision());
            assertEquals(a2.op().operationId(), account(store.repository, q2.playerId()).pendingIntent());
            assertNotAccepted(store.repository, profileId, i2);
            final CommitResult aborted = abortPlan(store.repository, a2, coop.p(), q2, null);
            committed(aborted);
            assertEquals(ProfileReceipt.Outcome.ABORTED, aborted.receipt().outcome());
            assertNull(profile(store.repository, profileId).pendingIntent());
            assertNull(account(store.repository, q2.playerId()).pendingIntent());
            assertEquals(2L, account(store.repository, q2.playerId()).revision(), "reserved once, cleared once");
            assertNotAccepted(store.repository, profileId, i2);
            assertTrue(store.repository.memberHead(profileId, q2.playerId(), a2.result().epoch()).isEmpty(),
                "an aborted accept left the head of its epoch");
            assertEquals(CommitResult.Status.REPLAYED, abortPlan(store.repository, a2, coop.p(), q2, null).status());
            refusedWith(ProfileErrorCode.STALE_STATE, () -> commitPlan(store.repository, a2, coop.p(), q2, null));
            final CoopIntentPlan a2b = acceptPlan(store.repository, i2);
            runPlan(store.repository, a2b, coop.p(), q2, null);
            assertAccepted(store.repository, a2b, i2);
            assertNotEquals(a2.result().epoch(), a2b.result().epoch(), "a new operation allocates a new epoch");

            // 3. Crash after PREPARED, then the decision is COMMIT: it applies once; a replay is REPLAYED.
            final PlayerClaim q3 = store.q(UUID.randomUUID());
            final ProfileInvite i3 = invite(store.repository, coop.p(), coop.q(), profileId, q3.playerId());
            final CoopIntentPlan a3 = acceptPlan(store.repository, i3);
            preparePlan(store.repository, a3, coop.p(), q3, null);
            store.reopen();
            committed(commitPlan(store.repository, a3, coop.p(), q3, null));
            assertAccepted(store.repository, a3, i3);
            assertEquals(CommitResult.Status.REPLAYED, commitPlan(store.repository, a3, coop.p(), q3, null).status());
            assertAccepted(store.repository, a3, i3);

            // 4. Crash after the COMMITTED marker, before any after-image: open rolls it forward.
            final PlayerClaim q4 = store.q(UUID.randomUUID());
            final ProfileInvite i4 = invite(store.repository, coop.p(), coop.q(), profileId, q4.playerId());
            final CoopIntentPlan a4 = acceptPlan(store.repository, i4);
            final ProfileIntent prepared4 = preparePlan(store.repository, a4, coop.p(), q4, null);
            store.repository.close();
            CoopMembershipFixtures.writeIntent(store(), prepared4.at(ProfileIntent.Phase.COMMITTED, "committed"));
            store.reopen();
            assertAccepted(store.repository, a4, i4);
            refusedWith(ProfileErrorCode.STALE_STATE, () -> abortPlan(store.repository, a4, coop.p(), q4, null));
            assertEquals(CommitResult.Status.REPLAYED, commitPlan(store.repository, a4, coop.p(), q4, null).status());

            // 5. Crash after the after-images, before indexes, receipts and FINALIZED: open finishes it idempotently.
            final PlayerClaim q5 = store.q(UUID.randomUUID());
            final ProfileInvite i5 = invite(store.repository, coop.p(), coop.q(), profileId, q5.playerId());
            final CoopIntentPlan a5 = acceptPlan(store.repository, i5);
            final ProfileIntent prepared5 = preparePlan(store.repository, a5, coop.p(), q5, null);
            CoopFixtures.copyTree(store().resolve("indexes"), scratch("accept").resolve("indexes"));
            CoopFixtures.copyTree(store().resolve("staging"), scratch("accept").resolve("staging"));
            committed(commitPlan(store.repository, a5, coop.p(), q5, null));
            store.repository.close();
            CoopMembershipFixtures.writeIntent(store(), prepared5.at(ProfileIntent.Phase.COMMITTED, "committed"));
            CoopFixtures.copyTree(scratch("accept").resolve("indexes"), store().resolve("indexes"));
            CoopFixtures.copyTree(scratch("accept").resolve("staging"), store().resolve("staging"));
            CoopMembershipFixtures.deleteReceipts(store(), a5.op().operationId());
            store.reopen();
            assertAccepted(store.repository, a5, i5);

            // Six members (owner and five accepts), nothing pending.
            assertEquals(6, profile(store.repository, profileId).members().size());
            assertTrue(indexed(store.repository, ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL).isEmpty());
        }
    }

    // ------------------------------------------------------------------ kick: crash at every phase

    /** A two-member co-op whose owner opened a passed kick vote against the member at a store time past the inactivity rule. */
    private record Kick(Coop coop, PlayerClaim member, ProfileMember row, KickVote vote, long at) {}

    private static Kick kickSetup(final CoopFixtures.FileStore store) throws Exception {
        final Coop coop = coop(store, "Mango");
        final PlayerClaim member = store.q(UUID.randomUUID());
        final ProfileMember row = admit(store.repository, coop.p(), coop.q(), coop.profile().id(), member);
        final long at = store.repository.storeClockMillis() + INACTIVE_SHIFT;
        final KickVote vote = passedVote(store.repository, coop.p(), coop.q(), coop.profile().id(), member.playerId(), at);
        return new Kick(coop, member, row, vote, at);
    }

    private static void assertNotKicked(final ProfileRepository repository, final Kick kick) throws Exception {
        final AuroraProfile profile = profile(repository, kick.coop().profile().id());
        assertEquals(kick.row(), profile.member(kick.member().playerId()), "the member row changed without a committed kick");
        assertTrue(account(repository, kick.member().playerId()).owns(profile.id()));
        assertEquals(MemberStateHead.State.UNINITIALIZED, repository.memberHead(profile.id(), kick.member().playerId(),
            kick.row().epoch()).orElseThrow().state());
        assertEquals(KickVote.Status.PASSED, repository.vote(kick.vote().id()).orElseThrow().status());
        assertTrue(indexed(repository, ProfileIndexPage.Kind.SALVAGE_BY_PROFILE, profile.id()).isEmpty());
    }

    @Test
    void CoopKickIntentCrashAtEveryPhase() throws Exception {
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(store())) {
            // 1. Crash after staging: nothing visible; the plan still runs.
            final Kick k1 = kickSetup(store);
            final CoopIntentPlan p1 = kickPlan(store.repository, k1.coop().owner(), k1.vote(), k1.at());
            assertEquals(k1.member().playerId(), p1.affectedAccount());
            p1.stage(store.repository);
            store.reopen();
            assertTrue(store.repository.intent(p1.op().operationId()).isEmpty());
            assertNotKicked(store.repository, k1);
            runPlan(store.repository, p1, k1.coop().p(), k1.coop().q(), k1.member());
            assertKicked(store.repository, p1, k1.row());

            // 2. Crash after PREPARED: reservations on the profile, the member's account and head; the member is still
            //    a member; abort clears them; the PASSED vote is retried by a new operation.
            final Kick k2 = kickSetup(store);
            final CoopIntentPlan p2 = kickPlan(store.repository, k2.coop().owner(), k2.vote(), k2.at());
            preparePlan(store.repository, p2, k2.coop().p(), k2.coop().q(), k2.member());
            store.reopen();
            final UUID profile2 = k2.coop().profile().id();
            assertEquals(ProfileIntent.Phase.PREPARED, store.repository.intent(p2.op().operationId()).orElseThrow().phase());
            assertEquals(p2.op().operationId(), profile(store.repository, profile2).pendingIntent());
            assertEquals(p2.op().operationId(), account(store.repository, k2.member().playerId()).pendingIntent());
            assertNotKicked(store.repository, k2);
            assertTrue(store.repository.salvage(p2.result().salvageId()).isEmpty(), "a PREPARED kick published its salvage record");
            committed(abortPlan(store.repository, p2, k2.coop().p(), k2.coop().q(), k2.member()));
            assertNull(profile(store.repository, profile2).pendingIntent());
            assertNull(account(store.repository, k2.member().playerId()).pendingIntent());
            assertNotKicked(store.repository, k2);
            final CoopIntentPlan p2b = kickPlan(store.repository, k2.coop().owner(), k2.vote(), k2.at());
            runPlan(store.repository, p2b, k2.coop().p(), k2.coop().q(), k2.member());
            assertKicked(store.repository, p2b, k2.row());
            assertTrue(store.repository.salvage(p2.result().salvageId()).isEmpty());

            // 3. Crash after the COMMITTED marker: open rolls the removal forward.
            final Kick k3 = kickSetup(store);
            final CoopIntentPlan p3 = kickPlan(store.repository, k3.coop().owner(), k3.vote(), k3.at());
            final ProfileIntent prepared3 = preparePlan(store.repository, p3, k3.coop().p(), k3.coop().q(), k3.member());
            store.repository.close();
            CoopMembershipFixtures.writeIntent(store(), prepared3.at(ProfileIntent.Phase.COMMITTED, "committed"));
            store.reopen();
            assertKicked(store.repository, p3, k3.row());
            assertEquals(CommitResult.Status.REPLAYED, commitPlan(store.repository, p3, k3.coop().p(), k3.coop().q(), k3.member())
                .status());

            // 4. Crash after the after-images, before indexes, receipts and FINALIZED: open finishes it once.
            final Kick k4 = kickSetup(store);
            final CoopIntentPlan p4 = kickPlan(store.repository, k4.coop().owner(), k4.vote(), k4.at());
            final ProfileIntent prepared4 = preparePlan(store.repository, p4, k4.coop().p(), k4.coop().q(), k4.member());
            CoopFixtures.copyTree(store().resolve("indexes"), scratch("kick").resolve("indexes"));
            CoopFixtures.copyTree(store().resolve("staging"), scratch("kick").resolve("staging"));
            committed(commitPlan(store.repository, p4, k4.coop().p(), k4.coop().q(), k4.member()));
            store.repository.close();
            CoopMembershipFixtures.writeIntent(store(), prepared4.at(ProfileIntent.Phase.COMMITTED, "committed"));
            CoopFixtures.copyTree(scratch("kick").resolve("indexes"), store().resolve("indexes"));
            CoopFixtures.copyTree(scratch("kick").resolve("staging"), store().resolve("staging"));
            CoopMembershipFixtures.deleteReceipts(store(), p4.op().operationId());
            store.reopen();
            assertKicked(store.repository, p4, k4.row());
            assertEquals(1, indexed(store.repository, ProfileIndexPage.Kind.SALVAGE_BY_PROFILE, k4.coop().profile().id()).size(),
                "the roll-forward wrote a second salvage record");

            assertTrue(indexed(store.repository, ProfileIndexPage.Kind.PENDING_INTENTS, ProfileIndexPage.GLOBAL).isEmpty());
        }
    }

    // ------------------------------------------------------------------ CoopMembershipAccountAtomicVisibility

    /**
     * A membership row and the account's access to the profile become visible in the same commit and disappear in
     * the same commit: a PREPARED accept or leave shows neither change, the commit shows both, and every read in
     * between agrees (profile record, account record, the account's profile listing).
     */
    @Test
    void CoopMembershipAccountAtomicVisibility() throws Exception {
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(store())) {
            final Coop coop = coop(store, "Kiwi");
            final UUID profileId = coop.profile().id();
            final PlayerClaim member = store.q(UUID.randomUUID());
            final ProfileInvite invite = invite(store.repository, coop.p(), coop.q(), profileId, member.playerId());

            final CoopIntentPlan accept = acceptPlan(store.repository, invite);
            preparePlan(store.repository, accept, coop.p(), member, null);
            assertNotAccepted(store.repository, profileId, invite);
            assertTrue(store.repository.profiles(member.playerId()).isEmpty());
            assertEquals(List.of(coop.owner()), profile(store.repository, profileId).members().stream().map(ProfileMember::playerId)
                .toList());
            committed(commitPlan(store.repository, accept, coop.p(), member, null));
            assertAccepted(store.repository, accept, invite);
            assertEquals(List.of(profileId), store.repository.profiles(member.playerId()).stream().map(AuroraProfile::id).toList());
            assertEquals(List.of(coop.owner(), member.playerId()), profile(store.repository, profileId).members().stream()
                .map(ProfileMember::playerId).toList());
            // The owner's account is not touched by a member's accept.
            assertEquals(List.of(profileId), account(store.repository, coop.owner()).profileIds());

            // Leave: PREPARED shows the membership unchanged; the commit removes row and access together.
            final ProfileMember row = profile(store.repository, profileId).member(member.playerId());
            final MemberStateHead head = store.repository.memberHead(profileId, member.playerId(), row.epoch()).orElseThrow();
            final CoopIntentPlan leave = CoopMembership.leave(op(CoopOperation.Kind.MEMBER_REMOVE, member.playerId()),
                profile(store.repository, profileId), account(store.repository, member.playerId()), head,
                List.of(),
                List.of(),
                store.repository.storeClockMillis(), 0L);
            assertNull(leave.affectedAccount(), "a leave holds the member's own Q only");
            preparePlan(store.repository, leave, coop.p(), member, null);
            assertEquals(row, profile(store.repository, profileId).member(member.playerId()));
            assertTrue(account(store.repository, member.playerId()).owns(profileId));
            committed(commitPlan(store.repository, leave, coop.p(), member, null));
            assertNull(profile(store.repository, profileId).member(member.playerId()));
            assertFalse(account(store.repository, member.playerId()).owns(profileId));
            assertNull(account(store.repository, member.playerId()).alias(profileId), "the alias leaves with the access");
            assertTrue(store.repository.profiles(member.playerId()).isEmpty());
            assertEquals(MemberStateHead.State.CONSUMED, store.repository.memberHead(profileId, member.playerId(), row.epoch())
                .orElseThrow().state());
            assertEquals(ProfileSalvage.State.UNAVAILABLE, store.repository.salvage(leave.result().salvageId()).orElseThrow().state());
        }
    }

    // ------------------------------------------------------------------ CoopAcceptCapacityRace

    @Test
    void CoopAcceptCapacityRace() throws Exception {
        final CoopMembership.Policy pair = new CoopMembership.Policy(2, 5 * MINUTE, 10 * MINUTE, 35 * DAY);
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(store())) {
            // Sequential interleaving: both accepts are built on the same M (one free seat); the second PREPARE finds M
            // reserved, then moved, and a plan rebuilt on the new M is COOP_FULL.
            final Coop coop = coop(store, "Grapes");
            final UUID profileId = coop.profile().id();
            final PlayerClaim b = store.q(UUID.randomUUID());
            final PlayerClaim c = store.q(UUID.randomUUID());
            final ProfileInvite ib = invite(store.repository, coop.p(), coop.q(), profileId, b.playerId(),
                store.repository.storeClockMillis(), pair);
            final ProfileInvite ic = invite(store.repository, coop.p(), coop.q(), profileId, c.playerId(),
                store.repository.storeClockMillis(), pair);
            final long now = store.repository.storeClockMillis();
            final CoopIntentPlan ab = acceptPlan(store.repository, ib, now, pair);
            final CoopIntentPlan ac = acceptPlan(store.repository, ic, now, pair);
            preparePlan(store.repository, ab, coop.p(), b, null);
            final Map<String, String> reserved = tree(store());
            refusedWith(ProfileErrorCode.STALE_STATE, () -> preparePlan(store.repository, ac, coop.p(), c, null));
            assertEquals(reserved, tree(store()), "a refused PREPARE changed the store");
            committed(commitPlan(store.repository, ab, coop.p(), b, null));
            refusedWith(ProfileErrorCode.STALE_STATE, () -> preparePlan(store.repository, ac, coop.p(), c, null));
            refusedWith(ProfileErrorCode.COOP_FULL, () -> acceptPlan(store.repository, ic, store.repository.storeClockMillis(), pair));
            assertEquals(2, profile(store.repository, profileId).members().size());
            assertNotAccepted(store.repository, profileId, ic);
            assertEquals(0L, account(store.repository, c.playerId()).revision(), "the loser's account was advanced");

            // Concurrent: two threads race their accepts for the last seat; exactly one commits, whatever the order.
            final Coop race = coop(store, "Lime");
            final PlayerClaim x = store.q(UUID.randomUUID());
            final PlayerClaim y = store.q(UUID.randomUUID());
            final ProfileInvite ix = invite(store.repository, race.p(), race.q(), race.profile().id(), x.playerId(),
                store.repository.storeClockMillis(), pair);
            final ProfileInvite iy = invite(store.repository, race.p(), race.q(), race.profile().id(), y.playerId(),
                store.repository.storeClockMillis(), pair);
            final long at = store.repository.storeClockMillis();
            final CoopIntentPlan ax = acceptPlan(store.repository, ix, at, pair);
            final CoopIntentPlan ay = acceptPlan(store.repository, iy, at, pair);
            final CountDownLatch start = new CountDownLatch(1);
            final ExecutorService threads = Executors.newFixedThreadPool(2);
            try {
                final List<Future<ProfileErrorCode>> outcomes = new ArrayList<>();
                for (final Object[] entry : List.of(new Object[] {ax, x}, new Object[] {ay, y})) {
                    final CoopIntentPlan plan = (CoopIntentPlan) entry[0];
                    final PlayerClaim q = (PlayerClaim) entry[1];
                    outcomes.add(threads.submit(() -> {
                        assertTrue(start.await(WAIT_SECONDS, TimeUnit.SECONDS));
                        try {
                            runPlan(store.repository, plan, race.p(), q, null);
                            return null;
                        } catch (final ProfileOperationException refused) {
                            return refused.code();
                        }
                    }));
                }
                start.countDown();
                int winners = 0;
                for (final Future<ProfileErrorCode> outcome : outcomes) {
                    final ProfileErrorCode code = outcome.get(WAIT_SECONDS, TimeUnit.SECONDS);
                    if (code == null) {
                        winners++;
                    } else {
                        assertEquals(ProfileErrorCode.STALE_STATE, code, "the losing accept must be refused as stale");
                    }
                }
                assertEquals(1, winners, "exactly one accept takes the last seat");
            } finally {
                threads.shutdownNow();
            }
            final AuroraProfile after = profile(store.repository, race.profile().id());
            assertEquals(2, after.members().size());
            final int access = (account(store.repository, x.playerId()).owns(after.id()) ? 1 : 0)
                + (account(store.repository, y.playerId()).owns(after.id()) ? 1 : 0);
            assertEquals(1, access, "exactly one account gained access");
            // The loser's intent, if its PREPARE was stored at all, is not pending (refused before or aborted by the caller).
            for (final CoopIntentPlan plan : List.of(ax, ay)) {
                final var intent = store.repository.intent(plan.op().operationId());
                if (intent.isPresent() && intent.get().phase() == ProfileIntent.Phase.PREPARED) {
                    final PlayerClaim q = plan == ax ? x : y;
                    committed(abortPlan(store.repository, plan, race.p(), q, null));
                }
            }
            assertNull(profile(store.repository, race.profile().id()).pendingIntent());
        }
    }

    // ------------------------------------------------------------------ CoopInviteExpiredAtCommit

    /**
     * The invite's expiry is the accept's commit deadline, judged by the store clock at COMMIT: an accept whose caller
     * still saw the invite open (its own store-clock read was earlier) is refused {@code TIMEOUT} when it commits after
     * the expiry, consumes nothing and can only be aborted.
     */
    @Test
    void CoopInviteExpiredAtCommit() throws Exception {
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(store())) {
            final Coop coop = coop(store, "Banana");
            final UUID profileId = coop.profile().id();
            final PlayerClaim member = store.q(UUID.randomUUID());
            final long now = store.repository.storeClockMillis();
            // Issued ten minutes ago with a five-minute lifetime: expired five minutes ago by the store clock.
            final ProfileInvite invite = invite(store.repository, coop.p(), coop.q(), profileId, member.playerId(), now - 10 * MINUTE,
                POLICY);
            assertTrue(invite.expired(store.repository.storeClockMillis()));
            final CoopIntentPlan accept = acceptPlan(store.repository, invite, invite.expiresAtMillis() - 1L, POLICY);
            assertEquals(invite.expiresAtMillis(), accept.deadlineMillis(), "the accept's deadline is the invite's expiry");
            preparePlan(store.repository, accept, coop.p(), member, null);
            refusedWith(ProfileErrorCode.TIMEOUT, () -> commitPlan(store.repository, accept, coop.p(), member, null));
            assertEquals(ProfileIntent.Phase.PREPARED, store.repository.intent(accept.op().operationId()).orElseThrow().phase());
            assertNotAccepted(store.repository, profileId, invite);
            committed(abortPlan(store.repository, accept, coop.p(), member, null));
            assertNotAccepted(store.repository, profileId, invite);
            assertNull(profile(store.repository, profileId).pendingIntent());
            assertNull(account(store.repository, member.playerId()).pendingIntent());
            // The caller that reads the store clock now refuses it before writing anything.
            refusedWith(ProfileErrorCode.INVITE_EXPIRED, () -> acceptPlan(store.repository, invite));
            // A cleanup closes it EXPIRED; it leaves both indexes in that commit.
            final CoopIntentPlan close = CoopMembership.closeInvite(op(CoopOperation.Kind.INVITE_REVOKE, member.playerId()),
                profile(store.repository, profileId), store.repository.invite(invite.id()).orElseThrow(),
                account(store.repository, member.playerId()), ProfileInvite.Status.EXPIRED, store.repository.storeClockMillis(), 0L);
            runPlan(store.repository, close, coop.p(), member, null);
            assertEquals(ProfileInvite.Status.EXPIRED, store.repository.invite(invite.id()).orElseThrow().status());
            assertFalse(indexed(store.repository, ProfileIndexPage.Kind.INVITES_BY_INVITEE, member.playerId()).contains(invite.id()));
            assertFalse(indexed(store.repository, ProfileIndexPage.Kind.INVITES_BY_PROFILE, profileId).contains(invite.id()));
            // The store clock never runs backwards within the process.
            final long first = store.repository.storeClockMillis();
            assertTrue(store.repository.storeClockMillis() >= first);
        }
    }

    // ------------------------------------------------------------------ one-way conversion and §9 bounds at commit

    @Test
    void convertIsOneWayAtCommit() throws Exception {
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(store())) {
            final Coop coop = coop(store, "Cherry");
            final AuroraProfile c = profile(store.repository, coop.profile().id());
            final PlayerAccount owner = account(store.repository, coop.owner());
            final AuroraProfile solo = new AuroraProfile(c.id(), c.accountId(), c.type(), c.profileType(), c.name(), c.metadata(),
                c.createdAtMillis(), c.revision() + 2L, c.status(), c.members(), c.bindingRevision(), null, ProfileKind.SOLO, null);
            final CoopIntentPlan back = new CoopIntentPlan(op(CoopOperation.Kind.RENAME_DISPLAY, coop.owner()), c.id(), null,
                List.of(Expected.account(coop.owner(), owner.revision()), Expected.metadata(c.id(), c.revision())),
                List.of(Expected.account(coop.owner(), owner.revision()), Expected.metadata(c.id(), c.revision() + 1L)),
                List.of(new CoopIntentPlan.Image(ProfileRecords.TYPE_PROFILE, c.id(), null, null, solo.revision(),
                    ProfileRecords.profilePayload2(solo))), List.of(), 0L, null);
            final ProfileOperationException refused = refusal(() -> runPlan(store.repository, back, coop.p(), coop.q(), null));
            assertNotEquals(ProfileErrorCode.INTERNAL_ERROR, refused.code(), refused.getMessage());
            final var stored = store.repository.intent(back.op().operationId());
            if (stored.isPresent() && stored.get().phase() == ProfileIntent.Phase.PREPARED) {
                committed(abortPlan(store.repository, back, coop.p(), coop.q(), null));
            }
            assertTrue(profile(store.repository, c.id()).coop(), "a co-op profile was turned back into a solo one");
            // The engine-side builder refuses it as well.
            refusedWith(ProfileErrorCode.ALREADY_COOP, () -> CoopMembership.convertToCoop(op(CoopOperation.Kind.CONVERT_COOP,
                coop.owner()), profile(store.repository, c.id()), account(store.repository, coop.owner()), 0L));
        }
    }

    @Test
    void oneUndecidedVotePerProfileAtCommit() throws Exception {
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(store())) {
            final Coop coop = coop(store, "Pear");
            final UUID profileId = coop.profile().id();
            final PlayerClaim b = store.q(UUID.randomUUID());
            final PlayerClaim c = store.q(UUID.randomUUID());
            admit(store.repository, coop.p(), coop.q(), profileId, b);
            admit(store.repository, coop.p(), coop.q(), profileId, c);
            final long now = store.repository.storeClockMillis();
            final CoopIntentPlan first = CoopMembership.createVote(op(CoopOperation.Kind.VOTE_CREATE, coop.owner()),
                profile(store.repository, profileId), account(store.repository, coop.owner()), c.playerId(), List.of(), POLICY, now, 0L);
            runPlan(store.repository, first, coop.p(), coop.q(), null);
            assertEquals(KickVote.Status.OPEN, store.repository.vote(first.result().recordId()).orElseThrow().status());
            // A caller that skipped the index read (no open votes passed) is stopped by the store's bound.
            final CoopIntentPlan second = CoopMembership.createVote(op(CoopOperation.Kind.VOTE_CREATE, coop.owner()),
                profile(store.repository, profileId), account(store.repository, coop.owner()), b.playerId(), List.of(), POLICY, now, 0L);
            final Map<String, String> before = tree(store());
            final ProfileOperationException refused = refusal(() -> runPlan(store.repository, second, coop.p(), coop.q(), null));
            assertEquals(ProfileErrorCode.CAPACITY_EXCEEDED, refused.code(), refused.getMessage());
            final var stored = store.repository.intent(second.op().operationId());
            if (stored.isPresent() && stored.get().phase() == ProfileIntent.Phase.PREPARED) {
                committed(abortPlan(store.repository, second, coop.p(), coop.q(), null));
            } else {
                assertEquals(before, tree(store()), "a refused vote changed the store");
            }
            assertTrue(store.repository.vote(second.result().recordId()).isEmpty());
            assertEquals(List.of(first.result().recordId()), indexed(store.repository, ProfileIndexPage.Kind.VOTES_BY_PROFILE, profileId));
            assertEquals(Set.of(coop.owner(), b.playerId(), c.playerId()), Set.copyOf(profile(store.repository, profileId).members()
                .stream().map(ProfileMember::playerId).toList()));
        }
    }

    // ------------------------------------------------------------------ store clock mark

    private static final int CLOCK_MAGIC = 0x41574643;

    private static long readMark(final Path mark) throws Exception {
        final ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(mark));
        assertEquals(CLOCK_MAGIC, buffer.getInt());
        return buffer.getLong();
    }

    private static void writeMark(final Path mark, final long value) throws Exception {
        final ByteBuffer buffer = ByteBuffer.allocate(12);
        buffer.putInt(CLOCK_MAGIC);
        buffer.putLong(value);
        Files.write(mark, buffer.array());
    }

    /**
     * The FILE store clock persists a high-water mark ({@code clock.awfc}, written ahead of the clock by the lease) and
     * resumes from it: never below a value it returned, also across a restart with the wall clock set back. A mark too
     * far ahead is capped, a malformed one is replaced; neither refuses the open.
     */
    @Test
    void CoopStoreClockMarkSurvivesRestart() throws Exception {
        try (CoopFixtures.FileStore store = CoopFixtures.FileStore.ready(store())) {
            final Path mark = store().resolve("clock.awfc");
            final long first = store.repository.storeClockMillis();
            assertTrue(Files.isRegularFile(mark), "the first read wrote the mark");
            final long written = readMark(mark);
            assertTrue(written > first && written <= first + FileProfileRepository.CLOCK_LEASE_MILLIS, "the mark runs ahead by the lease");
            store.reopen();
            assertTrue(store.repository.storeClockMillis() >= first);

            // A mark within the lease and tolerance ahead of the wall clock is honoured: the clock resumes from it.
            store.repository.close();
            final long ahead = System.currentTimeMillis() + 5 * MINUTE;
            writeMark(mark, ahead);
            store.reopen();
            final long resumed = store.repository.storeClockMillis();
            assertTrue(resumed >= ahead, "the clock went below a valid mark: " + resumed + " < " + ahead);
            assertTrue(resumed < ahead + MINUTE);
            assertTrue(readMark(mark) >= ahead, "the rewritten mark went backwards");

            // A mark beyond the lease plus the tolerance is capped to the wall clock plus the lease, never trusted as is.
            store.repository.close();
            writeMark(mark, System.currentTimeMillis() + 2 * 60 * MINUTE);
            store.reopen();
            final long capped = store.repository.storeClockMillis();
            assertTrue(capped <= System.currentTimeMillis() + FileProfileRepository.CLOCK_LEASE_MILLIS + MINUTE, "a far-future mark froze the clock");

            // A malformed mark never refuses the open: the clock resumes from the wall clock and the mark is rewritten.
            store.repository.close();
            Files.write(mark, new byte[] {1, 2, 3});
            store.reopen();
            final long now = System.currentTimeMillis();
            final long fresh = store.repository.storeClockMillis();
            assertTrue(fresh >= now - MINUTE && fresh <= now + FileProfileRepository.CLOCK_LEASE_MILLIS + MINUTE, "clock " + fresh);
            assertEquals(12, Files.size(mark), "the malformed mark was rewritten");
            assertTrue(readMark(mark) > fresh);
        }
    }
}
