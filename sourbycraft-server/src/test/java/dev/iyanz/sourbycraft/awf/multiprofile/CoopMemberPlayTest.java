package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.join;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.op;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusal;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusedWith;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.tree;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.INACTIVE_SHIFT;
import static dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.bytes;
import static dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.level;
import static dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.playSettings;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.CoopKickVoteInfo;
import dev.iyanz.sourbycraft.api.profile.CoopKickVoteState;
import dev.iyanz.sourbycraft.api.profile.CoopMemberLeaveEvent;
import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.api.profile.ProfileSwitchResult;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.CoopEngine;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ProfileClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.PlayingPorts;
import dev.iyanz.sourbycraft.awf.multiprofile.MemberPlayFixtures.TestAdapter;
import dev.iyanz.sourbycraft.awf.redis.fabric.ClaimResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Build 53 member-play owner cases through the engine over a FILE store (COOP-DESIGN §6 and §10 slice 5, RFC E03):
 * member switching into and out of a co-op with per-member streams, capture tickets on save and quit, exact rollback,
 * authoritative login (restore, first entry, refusals), adapter criticality in the switch and save paths, online member
 * removal (switch out, repoint), OD-1 for play, and intents retained over a shutdown.
 *
 * <p>The player is a {@link PlayingPorts} port: what is "on" the player is a state told apart by its level. Deterministic:
 * held captures and applies are futures the case completes; waits are bounded; the one timeout case uses the 1 s
 * minimum of {@code switching.timeout-ms} on a held apply, never a sleep.</p>
 */
class CoopMemberPlayTest {

    @TempDir
    Path directory;

    private Path store() {
        return this.directory.resolve("store");
    }

    private CoopEngine engine(final ProfileSettings settings, final PlayingPorts ports) throws Exception {
        final CoopEngine harness = new CoopEngine(store(), settings, true, ports);
        assertEquals(StoreFormat.Gate.FORMAT_2, harness.open().gate());
        return harness;
    }

    private CoopEngine engine() throws Exception {
        return engine(playSettings(), new PlayingPorts());
    }

    private static PlayingPorts ports(final CoopEngine h) {
        return (PlayingPorts) h.ports;
    }

    /** An owner's co-op "Apple", and a member who also owns the solo profile "Kiwi", which is the member's active profile. */
    private record Party(UUID owner, UUID member, AuroraProfile coop, AuroraProfile kiwi, UUID epoch, UUID kiwiEpoch) {}

    private static Party party(final CoopEngine h) throws Exception {
        final UUID owner = UUID.randomUUID();
        final UUID member = UUID.randomUUID();
        final AuroraProfile coop = h.coop(owner, "Apple");
        final AuroraProfile kiwi = h.create(member, "Kiwi");
        h.admit(owner, coop.id(), member);
        h.activate(member, kiwi.id());
        return new Party(owner, member, h.profile(coop.id()), kiwi, h.profile(coop.id()).member(member).epoch(),
            kiwi.member(member).epoch());
    }

    /** The player joins: online, session admitted (no login gate). */
    private static void online(final CoopEngine h, final UUID player) {
        ports(h).online.add(player);
        final ProfileEngine.Admission admission = join(h.engine.admitSession(player));
        assertTrue(admission.admitted(), admission.reason());
    }

    private static void switched(final CoopEngine h, final UUID player, final UUID target) {
        final ProfileSwitchResult result = join(h.engine.switchProfile(player, target));
        assertTrue(result.success(), result.errorCode() + ": " + result.message());
    }

    /**
     * The player quits as the runtime's quit does: ticket first, capture of what is on the player, the final save (which
     * closes the session marker when it commits), release.
     */
    private static boolean quit(final CoopEngine h, final UUID player) {
        final ProfileEngine.CaptureTicket ticket = h.engine.captureTicket(player);
        final PlayerClaim session = h.engine.playerSessionClaim(player);
        boolean saved = false;
        if (ticket != null && ticket.profileId() != null) {
            saved = join(h.engine.saveCapturedAndClose(player, PlayerStatePort.CaptureResult.of(ports(h).state(player)), ticket));
        }
        ports(h).online.remove(player);
        join(h.engine.releaseSession(player));
        if (session != null) join(h.engine.releasePlayerSession(player, session));
        return saved;
    }

    /**
     * The player joins again under authoritative login, in the runtime's order: gated, admitted, pending journals
     * recovered, the active profile's node claim taken; returns the gate.
     */
    private static UUID rejoin(final CoopEngine h, final UUID player) throws Exception {
        return rejoin(h, player, true);
    }

    private static UUID rejoin(final CoopEngine h, final UUID player, final boolean claim) throws Exception {
        ports(h).online.add(player);
        final UUID gate = h.engine.gateLogin(player);
        assertNotNull(gate, "authoritative login gates the join");
        final ProfileEngine.Admission admission = join(h.engine.admitSession(player));
        assertTrue(admission.admitted(), admission.reason());
        assertEquals(0, join(h.engine.recoverPending(player)));
        final UUID active = h.account(player).activeProfile();
        if (claim && active != null) assertTrue(join(h.engine.claimSession(player, active)).granted(), "the node claim of " + active);
        return gate;
    }

    /** The login restore bound to the join's gate (no journal was recovered at this login). */
    private static ProfileEngine.LoginRestore restore(final CoopEngine h, final UUID player, final UUID gate) {
        return join(h.engine.restoreOnLogin(player, gate, () -> false, false));
    }

    private static boolean save(final CoopEngine h, final UUID player, final ProfileState state) {
        return join(h.engine.saveCaptured(player, PlayerStatePort.CaptureResult.of(state), h.engine.captureTicket(player)));
    }

    private static MemberStateHead head(final CoopEngine h, final UUID profileId, final UUID player, final UUID epoch) throws Exception {
        return h.file.memberHead(profileId, player, epoch).orElseThrow(() -> new AssertionError("no head " + profileId + "/" + player));
    }

    private static ProfileState stateOf(final CoopEngine h, final MemberStateHead head) throws Exception {
        assertEquals(MemberStateHead.State.READY, head.state(), head.describe());
        return snapshotState(h, head.profileId(), head.memberId(), head.epoch(), head.snapshotId());
    }

    private static ProfileState snapshotState(final CoopEngine h, final UUID profileId, final UUID player, final UUID epoch,
                                              final UUID snapshotId) throws Exception {
        final ProfileSnapshot snapshot = h.file.memberSnapshot(profileId, player, epoch, snapshotId)
            .orElseThrow(() -> new AssertionError("snapshot " + snapshotId + " of " + profileId + "/" + player + " is not stored"));
        return ProfileStateCodec.decode(snapshot.state());
    }

    // ------------------------------------------------------------------ member switch into a co-op (FILE)

    /**
     * A member switches into the co-op and plays it from their own stream: the first entry applies the empty initial
     * state (never the inventory the player had), the solo state is the rollback point in the solo stream, the target
     * stream is not written by the switch, saves go to the member's co-op stream, and switching back and forth restores
     * each stream's exact head. The owner's stream is never touched.
     */
    @Test
    void memberSwitchesIntoCoopFile() throws Exception {
        try (CoopEngine h = engine()) {
            final Party p = party(h);
            final PlayingPorts ports = ports(h);
            final UUID ownerEpoch = p.coop().member(p.owner()).epoch();
            final Optional<MemberStateHead> ownerHead = h.file.memberHead(p.coop().id(), p.owner(), ownerEpoch);
            final MemberStateHead coopHead = head(h, p.coop().id(), p.member(), p.epoch());
            assertEquals(MemberStateHead.State.UNINITIALIZED, coopHead.state());
            ports.live.put(p.member(), level(7));
            online(h, p.member());

            switched(h, p.member(), p.coop().id());
            final PlayerAccount account = h.account(p.member());
            assertEquals(p.coop().id(), account.activeProfile());
            assertEquals(p.epoch(), account.activeEpoch());
            assertEquals(0, ports.levelOf(p.member()), "the first entry applies the empty initial state, not what the player had");
            assertTrue(ports.state(p.member()).isEmpty());
            assertEquals(7, stateOf(h, head(h, p.kiwi().id(), p.member(), p.kiwiEpoch())).level(), "the rollback point is the solo state");
            assertEquals(coopHead, head(h, p.coop().id(), p.member(), p.epoch()), "the switch wrote into the target stream");
            assertTrue(h.engine.holdsSession(p.member()));

            // Play in the co-op and save: the member's own co-op stream.
            ports.live.put(p.member(), level(12));
            assertTrue(save(h, p.member(), level(12)));
            final MemberStateHead saved = head(h, p.coop().id(), p.member(), p.epoch());
            assertEquals(12, stateOf(h, saved).level());

            // Back to the solo profile: its exact head; the co-op stream holds the state captured at the switch out.
            ports.live.put(p.member(), level(13));
            switched(h, p.member(), p.kiwi().id());
            assertEquals(7, ports.levelOf(p.member()));
            final MemberStateHead left = head(h, p.coop().id(), p.member(), p.epoch());
            assertEquals(saved.revision() + 1L, left.revision());
            assertEquals(13, stateOf(h, left).level());

            // And into the co-op again: the head the switch out published, exactly.
            switched(h, p.member(), p.coop().id());
            assertEquals(13, ports.levelOf(p.member()));
            assertEquals(ownerHead, h.file.memberHead(p.coop().id(), p.owner(), ownerEpoch), "a member's play touched the owner's stream");
        }
    }

    /**
     * The refusals of a member switch, each before anything is written: a non-member ({@code NOT_A_MEMBER}), a member
     * while {@code login.authoritative} is false ({@code COOP_SWITCH_UNAVAILABLE}), a member while co-op is off
     * ({@code COOP_DISABLED}), and OD-1 ({@code WRONG_NODE}, another node holds the co-op).
     */
    @Test
    void memberSwitchRefusals() throws Exception {
        final Party p;
        try (CoopEngine h = engine()) {
            p = party(h);
            final var before = tree(store());
            final ProfileSwitchResult outsider = join(h.engine.switchProfile(UUID.randomUUID(), p.coop().id()));
            assertFalse(outsider.success());
            assertEquals(ProfileErrorCode.NOT_A_MEMBER, outsider.errorCode(), outsider.message());
            // OD-1: another node holds the profile (its bound world is played there).
            final ClaimResult held = join(h.profiles.claim(p.coop().id(), "node-b", UUID.randomUUID()));
            assertTrue(held.granted());
            final ProfileSwitchResult wrongNode = join(h.engine.switchProfile(p.member(), p.coop().id()));
            assertEquals(ProfileErrorCode.WRONG_NODE, wrongNode.errorCode(), wrongNode.message());
            assertTrue(join(h.profiles.release(held.claim())));
            assertEquals(before, tree(store()), "a refused switch wrote something");
            assertEquals(p.kiwi().id(), h.account(p.member()).activeProfile());
        }
        try (CoopEngine h = engine(playSettings(false, 15_000, Set.of()), new PlayingPorts())) {
            final var before = tree(store());
            final ProfileSwitchResult result = join(h.engine.switchProfile(p.member(), p.coop().id()));
            assertEquals(ProfileErrorCode.COOP_SWITCH_UNAVAILABLE, result.errorCode(), result.message());
            assertEquals(before, tree(store()));
        }
        try (CoopEngine h = engine(CoopMembershipFixtures.coopSettings(false, 5, Set.of(CoopFixtures.CLASSIC)), new PlayingPorts())) {
            final var before = tree(store());
            final ProfileSwitchResult result = join(h.engine.switchProfile(p.member(), p.coop().id()));
            assertEquals(ProfileErrorCode.COOP_DISABLED, result.errorCode(), result.message());
            assertEquals(before, tree(store()));
        }
    }

    // ------------------------------------------------------------------ CoopSaveAcrossABAReturnRejected

    /**
     * A capture ticket minted in the co-op does not survive a switch away and back: the co-op is active again, but
     * {@code A} moved, so the save is refused ({@code STALE_STATE}) and the stream does not move. A fresh ticket saves.
     */
    @Test
    void CoopSaveAcrossABAReturnRejected() throws Exception {
        try (CoopEngine h = engine()) {
            final Party p = party(h);
            final PlayingPorts ports = ports(h);
            ports.live.put(p.member(), level(1));
            online(h, p.member());
            switched(h, p.member(), p.coop().id());
            final ProfileEngine.CaptureTicket ticket = h.engine.captureTicket(p.member());
            assertEquals(p.coop().id(), ticket.profileId());
            assertEquals(p.epoch(), ticket.epoch());

            ports.live.put(p.member(), level(2));
            switched(h, p.member(), p.kiwi().id());
            switched(h, p.member(), p.coop().id());
            assertEquals(2, ports.levelOf(p.member()), "the return applied the head the switch out published");
            final PlayerAccount account = h.account(p.member());
            assertEquals(ticket.profileId(), account.activeProfile(), "A -> B -> A: the same profile is active again");
            assertEquals(ticket.epoch(), account.activeEpoch());
            assertNotEquals(ticket.accountRevision(), account.revision());

            final MemberStateHead before = head(h, p.coop().id(), p.member(), p.epoch());
            refusedWith(ProfileErrorCode.STALE_STATE, () -> join(h.engine.saveCaptured(p.member(),
                PlayerStatePort.CaptureResult.of(level(66)), ticket)));
            assertEquals(before, head(h, p.coop().id(), p.member(), p.epoch()), "a capture across A -> B -> A was published");

            assertTrue(save(h, p.member(), level(3)));
            final MemberStateHead after = head(h, p.coop().id(), p.member(), p.epoch());
            assertEquals(before.revision() + 1L, after.revision());
            assertEquals(3, stateOf(h, after).level());
        }
    }

    // ------------------------------------------------------------------ CoopQuitTicketBeforeCapture

    /**
     * A quit save is attributed by the ticket minted before its capture, never by whatever is active later: without a
     * ticket it is refused ({@code CLAIM_LOST}); a ticket of the co-op used after a switch to the solo profile is refused
     * ({@code STALE_STATE}) and writes into neither stream; a ticket of a session the player rejoined since is refused
     * ({@code CLAIM_LOST}). The ticket of the current session publishes into exactly the stream it names.
     */
    @Test
    void CoopQuitTicketBeforeCapture() throws Exception {
        try (CoopEngine h = engine()) {
            final Party p = party(h);
            final PlayingPorts ports = ports(h);
            online(h, p.member());
            switched(h, p.member(), p.coop().id());

            refusedWith(ProfileErrorCode.CLAIM_LOST, () -> join(h.engine.saveCaptured(p.member(),
                PlayerStatePort.CaptureResult.of(level(9)), null)));

            // The ticket is taken in the co-op; the player is switched to the solo profile before the save arrives.
            final ProfileEngine.CaptureTicket inCoop = h.engine.captureTicket(p.member());
            ports.live.put(p.member(), level(4));
            switched(h, p.member(), p.kiwi().id());
            final MemberStateHead coopHead = head(h, p.coop().id(), p.member(), p.epoch());
            final MemberStateHead kiwiHead = head(h, p.kiwi().id(), p.member(), p.kiwiEpoch());
            refusedWith(ProfileErrorCode.STALE_STATE, () -> join(h.engine.saveCaptured(p.member(),
                PlayerStatePort.CaptureResult.of(level(9)), inCoop)));
            assertEquals(coopHead, head(h, p.coop().id(), p.member(), p.epoch()));
            assertEquals(kiwiHead, head(h, p.kiwi().id(), p.member(), p.kiwiEpoch()), "the save went to the profile active later");

            // A ticket of a session the player rejoined since.
            switched(h, p.member(), p.coop().id());
            final ProfileEngine.CaptureTicket old = h.engine.captureTicket(p.member());
            online(h, p.member());
            final ProfileEngine.CaptureTicket current = h.engine.captureTicket(p.member());
            assertNotEquals(old.claim(), current.claim());
            assertTrue(current.sessionGeneration() > old.sessionGeneration());
            final MemberStateHead beforeStale = head(h, p.coop().id(), p.member(), p.epoch());
            refusedWith(ProfileErrorCode.CLAIM_LOST, () -> join(h.engine.saveCaptured(p.member(),
                PlayerStatePort.CaptureResult.of(level(9)), old)));
            assertEquals(beforeStale, head(h, p.coop().id(), p.member(), p.epoch()));

            assertTrue(join(h.engine.saveCaptured(p.member(), PlayerStatePort.CaptureResult.of(level(5)), current)));
            assertEquals(5, stateOf(h, head(h, p.coop().id(), p.member(), p.epoch())).level());
            assertEquals(kiwiHead, head(h, p.kiwi().id(), p.member(), p.kiwiEpoch()));
        }
    }

    // ------------------------------------------------------------------ CoopTwoMembersIndependentSave

    /**
     * Owner and member play the co-op on one node at once: each save lands in its own stream and moves no other; a
     * ticket of one is refused for the other ({@code CLAIM_LOST}); the node holds one claim on the profile, which the
     * member's switch in and out shares and does not release while the owner still plays.
     */
    @Test
    void CoopTwoMembersIndependentSave() throws Exception {
        try (CoopEngine h = engine()) {
            final Party p = party(h);
            final UUID ownerEpoch = p.coop().member(p.owner()).epoch();
            h.activate(p.owner(), p.coop().id());
            online(h, p.owner());
            final ClaimResult ownerClaim = join(h.engine.claimSession(p.owner(), p.coop().id()));
            assertTrue(ownerClaim.granted());
            online(h, p.member());
            switched(h, p.member(), p.coop().id());

            assertTrue(save(h, p.owner(), level(11)));
            final MemberStateHead ownerHead = head(h, p.coop().id(), p.owner(), ownerEpoch);
            final MemberStateHead memberBefore = head(h, p.coop().id(), p.member(), p.epoch());
            assertTrue(save(h, p.member(), level(22)));
            final MemberStateHead memberHead = head(h, p.coop().id(), p.member(), p.epoch());
            assertEquals(memberBefore.revision() + 1L, memberHead.revision());
            assertEquals(ownerHead, head(h, p.coop().id(), p.owner(), ownerEpoch), "the member's save moved the owner's stream");
            assertEquals(11, stateOf(h, ownerHead).level());
            assertEquals(22, stateOf(h, memberHead).level());
            assertNotEquals(ownerHead.snapshotId(), memberHead.snapshotId());

            final ProfileEngine.CaptureTicket memberTicket = h.engine.captureTicket(p.member());
            refusedWith(ProfileErrorCode.CLAIM_LOST, () -> join(h.engine.saveCaptured(p.owner(),
                PlayerStatePort.CaptureResult.of(level(99)), memberTicket)));
            assertEquals(ownerHead, head(h, p.coop().id(), p.owner(), ownerEpoch));

            // One node claim per profile: the member's switch out leaves the owner's claim live.
            switched(h, p.member(), p.kiwi().id());
            assertTrue(join(h.profiles.validate(ownerClaim.claim())), "the member's switch out released the claim the owner plays under");
            assertTrue(save(h, p.owner(), level(12)));
            assertEquals(12, stateOf(h, head(h, p.coop().id(), p.owner(), ownerEpoch)).level());
            assertEquals(22, stateOf(h, memberHead).level());
        }
    }

    // ------------------------------------------------------------------ CoopSwitchExactRollbackPinned

    /**
     * A switch out of the co-op whose target apply fails rolls back to exactly the state captured into the member's own
     * stream: online at once, and, when the player left during the failed apply, at the next join from the snapshot the
     * journal names (still stored, pinned by the pending journal), never a later or profile-wide one.
     */
    @Test
    void CoopSwitchExactRollbackPinned() throws Exception {
        try (CoopEngine h = engine()) {
            final Party p = party(h);
            final PlayingPorts ports = ports(h);
            ports.live.put(p.member(), level(5));
            online(h, p.member());
            switched(h, p.member(), p.coop().id());
            final MemberStateHead kiwiHead = head(h, p.kiwi().id(), p.member(), p.kiwiEpoch());

            // Online: the failed apply is rolled back at once.
            ports.live.put(p.member(), level(30));
            ports.failNextApply.set(new ProfileOperationException(ProfileErrorCode.ADAPTER_REQUIRED_FAILED, "scripted required apply failure"));
            final ProfileSwitchResult failed = join(h.engine.switchProfile(p.member(), p.kiwi().id()));
            assertFalse(failed.success());
            assertEquals(ProfileErrorCode.ADAPTER_REQUIRED_FAILED, failed.errorCode(), failed.message());
            assertEquals(p.coop().id(), h.account(p.member()).activeProfile());
            assertEquals(30, ports.levelOf(p.member()), "the rollback applied something else than the captured state");
            final MemberStateHead captured = head(h, p.coop().id(), p.member(), p.epoch());
            assertEquals(30, stateOf(h, captured).level());
            final List<ProfileTransition> journals = h.file.transitionsFor(p.member());
            assertTrue(journals.stream().anyMatch(journal -> journal.phase() == TransitionPhase.FAILED
                && captured.snapshotId().equals(journal.fromSnapshotId())), "the journal does not name the exact rollback point: " + journals);
            assertEquals(kiwiHead, head(h, p.kiwi().id(), p.member(), p.kiwiEpoch()));

            // Offline during the failed apply: the rollback stays pending on the exact snapshot.
            ports.live.put(p.member(), level(31));
            ports.offlineOnFailedApply.set(true);
            ports.failNextApply.set(new ProfileOperationException(ProfileErrorCode.ADAPTER_REQUIRED_FAILED, "scripted required apply failure"));
            final ProfileSwitchResult pending = join(h.engine.switchProfile(p.member(), p.kiwi().id()));
            assertFalse(pending.success());
            assertEquals(ProfileErrorCode.RECOVERY_REQUIRED, pending.errorCode(), pending.message());
            final MemberStateHead pinned = head(h, p.coop().id(), p.member(), p.epoch());
            assertEquals(captured.revision() + 1L, pinned.revision());
            final ProfileTransition journal = h.file.transitionsFor(p.member()).stream().filter(found -> !found.terminal())
                .findFirst().orElseThrow(() -> new AssertionError("no pending journal"));
            assertEquals(pinned.snapshotId(), journal.fromSnapshotId());
            assertEquals(p.epoch(), journal.coop().fromEpoch());
            refusedWith(ProfileErrorCode.RECOVERY_REQUIRED, () -> join(h.engine.saveCaptured(p.member(),
                PlayerStatePort.CaptureResult.of(level(32)), h.engine.captureTicket(p.member()))));

            // The next join restores exactly that snapshot (the player may hold the target state meanwhile).
            ports.live.put(p.member(), level(77));
            online(h, p.member());
            join(h.engine.recoverPending(p.member()));
            assertEquals(31, ports.levelOf(p.member()));
            assertTrue(h.file.transitionsFor(p.member()).stream().allMatch(ProfileTransition::terminal));
            assertEquals(p.coop().id(), h.account(p.member()).activeProfile());
            assertEquals(31, snapshotState(h, p.coop().id(), p.member(), p.epoch(), journal.fromSnapshotId()).level());
        }
    }

    // ------------------------------------------------------------------ CoopLocalRestartIncarnation

    /**
     * After a local restart the claim providers are a new incarnation: the old session's ticket and claim are refused
     * (nothing is compared numerically across incarnations), the account's session generation keeps counting from the
     * store, the join restores the committed head, and the new session's ticket saves.
     */
    @Test
    void CoopLocalRestartIncarnation() throws Exception {
        final Party p;
        final ProfileEngine.CaptureTicket oldTicket;
        final PlayerClaim oldClaim;
        try (CoopEngine h = engine()) {
            p = party(h);
            online(h, p.member());
            switched(h, p.member(), p.coop().id());
            assertTrue(save(h, p.member(), level(8)));
            oldTicket = h.engine.captureTicket(p.member());
            oldClaim = h.engine.playerSessionClaim(p.member());
            // The process stops without a quit.
        }
        try (CoopEngine h = engine(playSettings(), new PlayingPorts())) {
            final MemberStateHead committed = head(h, p.coop().id(), p.member(), p.epoch());
            ports(h).live.put(p.member(), level(99));
            final UUID gate = rejoin(h, p.member());
            final PlayerClaim newClaim = h.engine.playerSessionClaim(p.member());
            assertNotEquals(oldClaim.incarnation(), newClaim.incarnation());
            assertTrue(h.account(p.member()).sessionGeneration() > oldTicket.sessionGeneration());
            final ProfileEngine.LoginRestore restore = restore(h, p.member(), gate);
            h.engine.releaseLoginGate(p.member(), gate);
            assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, restore.outcome(), restore.detail());
            assertEquals(8, ports(h).levelOf(p.member()));

            refusedWith(ProfileErrorCode.CLAIM_LOST, () -> join(h.engine.saveCaptured(p.member(),
                PlayerStatePort.CaptureResult.of(level(66)), oldTicket)));
            // A writer still holding the old incarnation's claim, with an otherwise current request, is refused by the store.
            final var before = tree(store());
            final AuroraProfile profile = h.profile(p.coop().id());
            final PlayerAccount account = h.account(p.member());
            final CoopOperation save = op(CoopOperation.Kind.SAVE_MEMBER, p.member());
            final ProfileSnapshot snapshot = ProfileSnapshot.forMember(p.coop().id(), p.member(), p.epoch(), committed.revision() + 1L,
                CoopFixtures.state(), save.operationId());
            refusal(() -> h.file.publishMemberSnapshot(committed.published(snapshot), snapshot, CoopFixtures.RETAIN,
                CoopWriteGuard.memberSave(save, oldClaim, p.coop().id(), p.epoch(), account.revision(), profile.revision(),
                    committed.revision())));
            assertEquals(before, tree(store()), "a write under the old incarnation's claim changed the store");
            assertEquals(committed, head(h, p.coop().id(), p.member(), p.epoch()));

            assertTrue(save(h, p.member(), level(9)));
            assertEquals(9, stateOf(h, head(h, p.coop().id(), p.member(), p.epoch())).level());
        }
    }

    // ------------------------------------------------------------------ CoopLoginRestoresCommittedHead

    /**
     * Authoritative login: the join is gated (frozen, saves and switches refused) until the committed member head of the
     * active co-op was applied, replacing what the join loaded; then the gate lifts and saves run again.
     */
    @Test
    void CoopLoginRestoresCommittedHead() throws Exception {
        try (CoopEngine h = engine()) {
            final Party p = party(h);
            final PlayingPorts ports = ports(h);
            online(h, p.member());
            switched(h, p.member(), p.coop().id());
            ports.live.put(p.member(), level(40));
            assertTrue(quit(h, p.member()));
            final MemberStateHead committed = head(h, p.coop().id(), p.member(), p.epoch());
            assertEquals(40, stateOf(h, committed).level());

            // What the join loaded (vanilla playerdata) is something else.
            ports.live.put(p.member(), level(99));
            final UUID gate = rejoin(h, p.member());
            assertTrue(h.engine.frozen(p.member()), "a gated join is not frozen");
            assertTrue(h.engine.switchInProgress(p.member()));
            refusedWith(ProfileErrorCode.SWITCH_IN_PROGRESS, () -> join(h.engine.saveCaptured(p.member(),
                PlayerStatePort.CaptureResult.of(level(99)), h.engine.captureTicket(p.member()))));
            final ProfileSwitchResult gatedSwitch = join(h.engine.switchProfile(p.member(), p.kiwi().id()));
            assertEquals(ProfileErrorCode.SWITCH_IN_PROGRESS, gatedSwitch.errorCode(), gatedSwitch.message());
            assertEquals(committed, head(h, p.coop().id(), p.member(), p.epoch()), "the vanilla data was written under the gate");

            final ProfileEngine.LoginRestore restore = restore(h, p.member(), gate);
            assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, restore.outcome(), restore.detail());
            assertEquals(p.coop().id(), restore.profileId());
            assertNull(restore.code());
            assertEquals(40, ports.levelOf(p.member()), "the join kept the vanilla data instead of the committed head");
            assertEquals(committed, head(h, p.coop().id(), p.member(), p.epoch()), "restoring a READY head publishes nothing");
            h.engine.releaseLoginGate(p.member(), gate);
            assertFalse(h.engine.frozen(p.member()));
            assertTrue(save(h, p.member(), level(41)));
        }
    }

    /**
     * The first entry of a member into a co-op at login (an UNINITIALIZED head) on a node with no vanilla playerdata of
     * the player: the initial state is {@link ProfileState#empty()}, applied and published as the stream's first
     * snapshot. (With vanilla playerdata the join adopts it instead, never applying the empty state over it:
     * {@code firstEntryWithVanillaPlayerdataAdoptsIt}.)
     */
    @Test
    void firstEntryInitialisesEmptyStateAsFirstSnapshot() throws Exception {
        try (CoopEngine h = engine()) {
            final Party p = party(h);
            final PlayingPorts ports = ports(h);
            h.activate(p.member(), p.coop().id());
            final MemberStateHead uninitialized = head(h, p.coop().id(), p.member(), p.epoch());
            assertEquals(MemberStateHead.State.UNINITIALIZED, uninitialized.state());
            ports.live.put(p.member(), level(33));
            final UUID gate = rejoin(h, p.member());
            final ProfileEngine.LoginRestore restore = restore(h, p.member(), gate);
            h.engine.releaseLoginGate(p.member(), gate);
            assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, restore.outcome(), restore.detail());
            assertEquals(0, ports.levelOf(p.member()));
            assertTrue(ports.state(p.member()).isEmpty(), "the join's inventory was kept in the co-op");
            final MemberStateHead first = head(h, p.coop().id(), p.member(), p.epoch());
            assertEquals(MemberStateHead.State.READY, first.state());
            assertEquals(uninitialized.revision() + 1L, first.revision());
            final ProfileState published = stateOf(h, first);
            assertTrue(published.isEmpty(), "the first snapshot is not the empty initial state");
            assertEquals(ProfileState.empty().health(), published.health());
            assertEquals(ProfileState.empty().food(), published.food());
        }
    }

    /**
     * A committed head that cannot be read refuses the join ({@code HEAD_UNREADABLE}); nothing is applied, the vanilla
     * data the join loaded is never saved (the gate is held until the quit), and the quit lifts the gate.
     */
    @Test
    void headUnreadableRefusesJoin() throws Exception {
        try (CoopEngine h = engine()) {
            final Party p = party(h);
            final PlayingPorts ports = ports(h);
            online(h, p.member());
            switched(h, p.member(), p.coop().id());
            ports.live.put(p.member(), level(40));
            assertTrue(quit(h, p.member()));
            final MemberStateHead committed = head(h, p.coop().id(), p.member(), p.epoch());
            // The exact snapshot the head names is gone.
            final String suffix = "-" + committed.snapshotId() + ".awfp";
            final List<Path> files;
            try (var walk = Files.walk(store())) {
                files = walk.filter(Files::isRegularFile).filter(file -> file.getFileName().toString().endsWith(suffix)).toList();
            }
            assertEquals(1, files.size(), "the head's snapshot file: " + files);
            Files.delete(files.getFirst());

            ports.live.put(p.member(), level(99));
            final int appliedBefore = ports.applied.size();
            final UUID gate = rejoin(h, p.member());
            final ProfileEngine.LoginRestore restore = restore(h, p.member(), gate);
            assertEquals(ProfileEngine.LoginRestore.Outcome.REFUSED, restore.outcome());
            assertEquals(ProfileErrorCode.HEAD_UNREADABLE, restore.code(), restore.detail());
            assertEquals(appliedBefore, ports.applied.size(), "a refused join applied something");
            assertEquals(99, ports.levelOf(p.member()));
            h.engine.holdLoginGate(p.member(), gate);
            h.engine.releaseLoginGate(p.member(), gate);
            assertTrue(h.engine.frozen(p.member()), "a held gate was lifted by the join's own exit");
            refusedWith(ProfileErrorCode.SWITCH_IN_PROGRESS, () -> join(h.engine.saveCaptured(p.member(),
                PlayerStatePort.CaptureResult.of(level(99)), h.engine.captureTicket(p.member()))));
            assertEquals(committed, head(h, p.coop().id(), p.member(), p.epoch()));
            // The disconnect's quit lifts it.
            h.engine.releaseSessionAfterSwitch(p.member());
            assertFalse(h.engine.frozen(p.member()));
        }
    }

    // ------------------------------------------------------------------ OD-1 for play

    /** OD-1: while another node holds the co-op, a member's switch and an authoritative login into it are WRONG_NODE. */
    @Test
    void wrongNodeRefusesSwitchAndLogin() throws Exception {
        try (CoopEngine h = engine()) {
            final Party p = party(h);
            final PlayingPorts ports = ports(h);
            online(h, p.member());
            final ClaimResult held = join(h.profiles.claim(p.coop().id(), "node-b", UUID.randomUUID()));
            assertTrue(held.granted());
            final ProfileSwitchResult switched = join(h.engine.switchProfile(p.member(), p.coop().id()));
            assertEquals(ProfileErrorCode.WRONG_NODE, switched.errorCode(), switched.message());
            assertEquals(p.kiwi().id(), h.account(p.member()).activeProfile());

            // The account names the co-op (it was played here before); the login on this node is refused.
            quit(h, p.member());
            h.activate(p.member(), p.coop().id());
            ports.live.put(p.member(), level(99));
            // The runtime refuses this join already at the node claim; the engine refuses it by itself as well.
            final UUID gate = rejoin(h, p.member(), false);
            assertFalse(join(h.engine.claimSession(p.member(), p.coop().id())).granted());
            final ProfileEngine.LoginRestore restore = restore(h, p.member(), gate);
            h.engine.holdLoginGate(p.member(), gate);
            assertEquals(ProfileEngine.LoginRestore.Outcome.REFUSED, restore.outcome());
            assertEquals(ProfileErrorCode.WRONG_NODE, restore.code(), restore.detail());
            assertEquals(99, ports.levelOf(p.member()));
            assertTrue(join(h.profiles.release(held.claim())));
        }
    }

    // ------------------------------------------------------------------ CoopApplyAfterTimeoutCancelled

    /**
     * A login apply that does not finish within {@code switching.timeout-ms} refuses the join ({@code TIMEOUT}) and is
     * cancelled: when the entity thread finally runs it, it sees the cancellation and applies nothing.
     */
    @Test
    void CoopApplyAfterTimeoutCancelled() throws Exception {
        try (CoopEngine h = engine(playSettings(true, 1_000, Set.of()), new PlayingPorts())) {
            final Party p = party(h);
            final PlayingPorts ports = ports(h);
            // The co-op is made active directly, so no switch has to fit the 1 s budget; the quit publishes the head.
            h.activate(p.member(), p.coop().id());
            online(h, p.member());
            ports.live.put(p.member(), level(40));
            assertTrue(quit(h, p.member()));
            final MemberStateHead committed = head(h, p.coop().id(), p.member(), p.epoch());

            ports.live.put(p.member(), level(99));
            final UUID gate = rejoin(h, p.member());
            final CompletableFuture<Void> release = new CompletableFuture<>();
            final CountDownLatch entered = new CountDownLatch(1);
            ports.applyEntered.set(entered);
            ports.heldApply.set(release);
            final CompletableFuture<ProfileEngine.LoginRestore> restoring = h.engine.restoreOnLogin(p.member(), gate, () -> false, false);
            assertTrue(entered.await(CoopFixtures.WAIT_SECONDS, TimeUnit.SECONDS), "the apply never reached the player");
            final ProfileEngine.LoginRestore restore = join(restoring);
            assertEquals(ProfileEngine.LoginRestore.Outcome.REFUSED, restore.outcome());
            assertEquals(ProfileErrorCode.TIMEOUT, restore.code(), restore.detail());
            h.engine.holdLoginGate(p.member(), gate);

            // The entity thread runs the late apply now: cancelled, nothing applied.
            release.complete(null);
            assertEquals(1, ports.cancelledApplies.get());
            assertEquals(99, ports.levelOf(p.member()), "a timed-out apply changed the player");
            assertTrue(h.engine.frozen(p.member()));
            assertEquals(committed, head(h, p.coop().id(), p.member(), p.epoch()));
        }
    }

    // ------------------------------------------------------------------ CoopQuitRejoinBarrier

    /**
     * A rejoin is not admitted while a switch of the previous session still runs ({@code SWITCH_IN_PROGRESS}); a quit save
     * queued before the rejoin's admission is published first (the I/O lane is FIFO), and its ticket is stale after it.
     */
    @Test
    void CoopQuitRejoinBarrier() throws Exception {
        try (CoopEngine h = engine()) {
            final Party p = party(h);
            final PlayingPorts ports = ports(h);
            ports.live.put(p.member(), level(3));
            online(h, p.member());

            final CompletableFuture<PlayerStatePort.CaptureResult> capture = new CompletableFuture<>();
            final CountDownLatch entered = new CountDownLatch(1);
            ports.captureEntered.set(entered);
            ports.heldCapture.set(capture);
            final CompletableFuture<ProfileSwitchResult> switching = h.engine.switchProfile(p.member(), p.coop().id());
            assertTrue(entered.await(CoopFixtures.WAIT_SECONDS, TimeUnit.SECONDS), "the switch never captured");
            final long generation = h.account(p.member()).sessionGeneration();
            refusedWith(ProfileErrorCode.SWITCH_IN_PROGRESS, () -> join(h.engine.admitSession(p.member())));
            assertEquals(generation, h.account(p.member()).sessionGeneration(), "a refused rejoin advanced the session");
            capture.complete(PlayerStatePort.CaptureResult.of(level(3)));
            final ProfileSwitchResult result = join(switching);
            assertTrue(result.success(), result.message());

            // Quit save and rejoin queued back to back: the save is published before the new session exists.
            final ProfileEngine.CaptureTicket ticket = h.engine.captureTicket(p.member());
            final MemberStateHead before = head(h, p.coop().id(), p.member(), p.epoch());
            final CompletableFuture<Boolean> saved = h.engine.saveCaptured(p.member(), PlayerStatePort.CaptureResult.of(level(44)), ticket);
            final CompletableFuture<ProfileEngine.Admission> admitted = h.engine.admitSession(p.member());
            assertTrue(join(saved), "the quit save queued before the rejoin was refused");
            assertTrue(join(admitted).admitted());
            final MemberStateHead after = head(h, p.coop().id(), p.member(), p.epoch());
            assertEquals(before.revision() + 1L, after.revision());
            assertEquals(44, stateOf(h, after).level());
            assertTrue(h.account(p.member()).sessionGeneration() > ticket.sessionGeneration());
            refusedWith(ProfileErrorCode.CLAIM_LOST, () -> join(h.engine.saveCaptured(p.member(),
                PlayerStatePort.CaptureResult.of(level(45)), ticket)));
        }
    }

    // ------------------------------------------------------------------ CoopRequiredAdapterMissingBlocks

    /**
     * Data of a REQUIRED adapter that is not registered on this node blocks both a login into the profile and a switch
     * into it ({@code ADAPTER_MISSING}), before anything of the target reaches the player; with the adapter registered
     * the same switch runs. The data itself is kept while the adapter is missing.
     */
    @Test
    void CoopRequiredAdapterMissingBlocks() throws Exception {
        final ProfileStateAdapters registry = new ProfileStateAdapters(Set.of("req.adapter"));
        try (CoopEngine h = engine(playSettings(), new PlayingPorts(registry))) {
            final Party p = party(h);
            final PlayingPorts ports = ports(h);
            final byte[] blob = bytes(16, 7);
            final Map<String, byte[]> required = Map.of("req.adapter:1", blob);

            // The owner's state in the co-op holds the required adapter's data.
            h.activate(p.owner(), p.coop().id());
            online(h, p.owner());
            assertTrue(join(h.engine.saveCaptured(p.owner(), new PlayerStatePort.CaptureResult(level(20, required),
                new ProfileStateAdapters.Capture(required, Set.of(), Set.of())), h.engine.captureTicket(p.owner()))));
            assertTrue(quit(h, p.owner()));
            final UUID ownerEpoch = p.coop().member(p.owner()).epoch();
            assertArrayEquals(blob, stateOf(h, head(h, p.coop().id(), p.owner(), ownerEpoch)).adapters().get("req.adapter:1"),
                "the quit dropped the data of the missing adapter");
            ports.live.put(p.owner(), level(99));
            final UUID gate = rejoin(h, p.owner());
            final ProfileEngine.LoginRestore login = restore(h, p.owner(), gate);
            h.engine.holdLoginGate(p.owner(), gate);
            assertEquals(ProfileEngine.LoginRestore.Outcome.REFUSED, login.outcome());
            assertEquals(ProfileErrorCode.ADAPTER_MISSING, login.code(), login.detail());
            assertEquals(99, ports.levelOf(p.owner()));

            // The member's co-op state holds it too; the member plays the solo profile.
            ports.live.put(p.member(), level(1));
            online(h, p.member());
            switched(h, p.member(), p.coop().id());
            assertTrue(join(h.engine.saveCaptured(p.member(), new PlayerStatePort.CaptureResult(level(50, required),
                new ProfileStateAdapters.Capture(required, Set.of(), Set.of())), h.engine.captureTicket(p.member()))));
            ports.live.put(p.member(), level(50));
            switched(h, p.member(), p.kiwi().id());
            assertEquals(1, ports.levelOf(p.member()));
            final MemberStateHead coopHead = head(h, p.coop().id(), p.member(), p.epoch());
            assertArrayEquals(blob, stateOf(h, coopHead).adapters().get("req.adapter:1"), "the switch out dropped the required data");

            final int applied = ports.applied.size();
            final ProfileSwitchResult refused = join(h.engine.switchProfile(p.member(), p.coop().id()));
            assertFalse(refused.success());
            assertEquals(ProfileErrorCode.ADAPTER_MISSING, refused.errorCode(), refused.message());
            assertEquals(p.kiwi().id(), h.account(p.member()).activeProfile());
            assertEquals(applied, ports.applied.size(), "something of the target reached the player");
            assertEquals(1, ports.levelOf(p.member()));
            assertEquals(coopHead, head(h, p.coop().id(), p.member(), p.epoch()));

            // With the adapter registered, the same switch runs and the player gets the data.
            final org.bukkit.plugin.Plugin plugin = MemberPlayFixtures.plugin("RequiredPlugin");
            registry.register(plugin, TestAdapter.required("req.adapter", plugin));
            switched(h, p.member(), p.coop().id());
            assertEquals(50, ports.levelOf(p.member()));
            assertArrayEquals(blob, ports.state(p.member()).adapters().get("req.adapter:1"));
        }
    }

    // ------------------------------------------------------------------ CoopUnknownAdapterBytesPreserved

    /**
     * Opaque adapter bytes through the save path: data of an adapter no registration accounts for is carried forward
     * unchanged; a BEST_EFFORT adapter that fails keeps the bytes the stream already stores for it; one that returns
     * nothing (null or empty) clears them.
     */
    @Test
    void CoopUnknownAdapterBytesPreserved() throws Exception {
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        final org.bukkit.plugin.Plugin plugin = MemberPlayFixtures.plugin("SoftPlugin");
        final byte[] gone = bytes(32, 1);
        final byte[] first = bytes(8, 2);
        final byte[] second = bytes(8, 3);
        final TestAdapter soft = TestAdapter.bestEffort("soft", plugin).returning(second);
        registry.register(plugin, soft);
        try (CoopEngine h = engine(playSettings(), new PlayingPorts(registry))) {
            final Party p = party(h);
            final UUID ownerEpoch = p.coop().member(p.owner()).epoch();
            h.activate(p.owner(), p.coop().id());
            online(h, p.owner());
            final Map<String, byte[]> initial = new java.util.LinkedHashMap<>();
            initial.put("gone.plugin:3", gone);
            initial.put("soft:1", first);
            assertTrue(join(h.engine.saveCaptured(p.owner(), new PlayerStatePort.CaptureResult(level(1, initial),
                new ProfileStateAdapters.Capture(initial, Set.of(), Set.of())), h.engine.captureTicket(p.owner()))));

            // A live capture: the registered adapter stores its new bytes, the unknown entry is carried forward.
            join(h.engine.save(p.coop().id()));
            Map<String, byte[]> stored = stateOf(h, head(h, p.coop().id(), p.owner(), ownerEpoch)).adapters();
            assertArrayEquals(second, stored.get("soft:1"));
            assertArrayEquals(gone, stored.get("gone.plugin:3"), "unknown adapter bytes were not preserved");

            // BEST_EFFORT failure: skipped, the stored bytes stay.
            soft.throwing();
            join(h.engine.save(p.coop().id()));
            stored = stateOf(h, head(h, p.coop().id(), p.owner(), ownerEpoch)).adapters();
            assertArrayEquals(second, stored.get("soft:1"), "a failed best-effort capture dropped the bytes it stored before");
            assertArrayEquals(gone, stored.get("gone.plugin:3"));

            // Nothing to store: null clears, and so does an empty array.
            soft.returning(null);
            join(h.engine.save(p.coop().id()));
            stored = stateOf(h, head(h, p.coop().id(), p.owner(), ownerEpoch)).adapters();
            assertFalse(stored.containsKey("soft:1"), "a null capture did not clear the stored bytes");
            assertArrayEquals(gone, stored.get("gone.plugin:3"));
            soft.returning(first);
            join(h.engine.save(p.coop().id()));
            assertArrayEquals(first, stateOf(h, head(h, p.coop().id(), p.owner(), ownerEpoch)).adapters().get("soft:1"));
            soft.returning(new byte[0]);
            join(h.engine.save(p.coop().id()));
            stored = stateOf(h, head(h, p.coop().id(), p.owner(), ownerEpoch)).adapters();
            assertFalse(stored.containsKey("soft:1"), "an empty capture did not clear the stored bytes");
            assertArrayEquals(gone, stored.get("gone.plugin:3"));
        }
    }

    // ------------------------------------------------------------------ CoopAggregateBudgetRefusesLoss

    /**
     * The 6 MiB adapter budget is never met by dropping: a capture whose new bytes do not fit beside the carried ones is
     * refused ({@code STATE_TOO_LARGE}) and the stream keeps its head with every byte it held.
     */
    @Test
    void CoopAggregateBudgetRefusesLoss() throws Exception {
        try (CoopEngine h = engine(playSettings(), new PlayingPorts(new ProfileStateAdapters()))) {
            final Party p = party(h);
            final UUID ownerEpoch = p.coop().member(p.owner()).epoch();
            h.activate(p.owner(), p.coop().id());
            online(h, p.owner());
            final int mib = 1024 * 1024;
            final Map<String, byte[]> carried = new java.util.LinkedHashMap<>();
            carried.put("gone.a:1", bytes(2 * mib, 1));
            carried.put("gone.b:1", bytes(2 * mib, 2));
            carried.put("gone.c:1", bytes(mib, 3));
            assertTrue(join(h.engine.saveCaptured(p.owner(), new PlayerStatePort.CaptureResult(level(1, carried),
                new ProfileStateAdapters.Capture(carried, Set.of(), Set.of())), h.engine.captureTicket(p.owner()))));
            final MemberStateHead before = head(h, p.coop().id(), p.owner(), ownerEpoch);

            final Map<String, byte[]> fresh = Map.of("soft:1", bytes(3 * mib / 2, 4));
            refusedWith(ProfileErrorCode.STATE_TOO_LARGE, () -> join(h.engine.saveCaptured(p.owner(),
                new PlayerStatePort.CaptureResult(level(2, fresh), new ProfileStateAdapters.Capture(fresh, Set.of(), Set.of())),
                h.engine.captureTicket(p.owner()))));
            assertEquals(before, head(h, p.coop().id(), p.owner(), ownerEpoch), "an over-budget capture was published");
            final Map<String, byte[]> kept = stateOf(h, before).adapters();
            assertEquals(carried.keySet(), kept.keySet());
            for (final Map.Entry<String, byte[]> entry : carried.entrySet()) assertArrayEquals(entry.getValue(), kept.get(entry.getKey()));
        }
    }

    // ------------------------------------------------------------------ REQUIRED capture failure

    /**
     * A REQUIRED adapter whose capture fails refuses the switch ({@code ADAPTER_REQUIRED_FAILED}) before anything is
     * published or applied, and refuses a save the same way; the operator's {@code adapters.required} raises a
     * BEST_EFFORT adapter to that rule.
     */
    @Test
    void requiredCaptureFailureRefusesWithNothingPartial() throws Exception {
        final ProfileStateAdapters registry = new ProfileStateAdapters(Set.of("forced"));
        final org.bukkit.plugin.Plugin plugin = MemberPlayFixtures.plugin("ForcedPlugin");
        final TestAdapter forced = TestAdapter.bestEffort("forced", plugin).returning(bytes(4, 1));
        registry.register(plugin, forced);
        assertTrue(registry.required("forced"), "adapters.required did not raise the adapter");
        try (CoopEngine h = engine(playSettings(true, 15_000, Set.of("forced")), new PlayingPorts(registry))) {
            final Party p = party(h);
            final PlayingPorts ports = ports(h);
            ports.live.put(p.member(), level(6));
            online(h, p.member());
            switched(h, p.member(), p.coop().id());
            ports.live.put(p.member(), level(7));
            final MemberStateHead coopHead = head(h, p.coop().id(), p.member(), p.epoch());
            final MemberStateHead kiwiHead = head(h, p.kiwi().id(), p.member(), p.kiwiEpoch());
            final int applied = ports.applied.size();

            forced.throwing();
            final ProfileSwitchResult result = join(h.engine.switchProfile(p.member(), p.kiwi().id()));
            assertFalse(result.success());
            assertEquals(ProfileErrorCode.ADAPTER_REQUIRED_FAILED, result.errorCode(), result.message());
            assertEquals(p.coop().id(), h.account(p.member()).activeProfile());
            assertEquals(coopHead, head(h, p.coop().id(), p.member(), p.epoch()), "a partial capture was published");
            assertEquals(kiwiHead, head(h, p.kiwi().id(), p.member(), p.kiwiEpoch()));
            assertEquals(applied, ports.applied.size(), "something was applied after a required capture failed");
            assertEquals(7, ports.levelOf(p.member()));

            // The engine's own save path (capture on the port) is refused the same way.
            h.activate(p.owner(), p.coop().id());
            online(h, p.owner());
            final Optional<MemberStateHead> ownerHead = h.file.memberHead(p.coop().id(), p.owner(), p.coop().member(p.owner()).epoch());
            refusedWith(ProfileErrorCode.ADAPTER_REQUIRED_FAILED, () -> join(h.engine.save(p.coop().id())));
            assertEquals(ownerHead, h.file.memberHead(p.coop().id(), p.owner(), p.coop().member(p.owner()).epoch()));
        }
    }

    // ------------------------------------------------------------------ the two-phase shutdown capture

    /**
     * The shutdown capture's merge (PM-076): adapter blobs staged while the plugins were enabled are merged by the final
     * capture after the plugins were disabled (their adapters unregistered), exactly like a live capture: fresh bytes
     * stored, a failed best-effort adapter's bytes kept, a cleared one dropped, unknown bytes carried.
     */
    @Test
    void shutdownCaptureMergesStagedAdapterBlobs() throws Exception {
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        final org.bukkit.plugin.Plugin plugin = MemberPlayFixtures.plugin("StagedPlugin");
        final TestAdapter fresh = TestAdapter.bestEffort("fresh", plugin).returning(bytes(4, 9));
        final TestAdapter failing = TestAdapter.bestEffort("failing", plugin).throwing();
        final TestAdapter empty = TestAdapter.bestEffort("empty", plugin).returning(null);
        registry.register(plugin, fresh);
        registry.register(plugin, failing);
        registry.register(plugin, empty);
        try (CoopEngine h = engine(playSettings(), new PlayingPorts(registry))) {
            final Party p = party(h);
            online(h, p.member());
            switched(h, p.member(), p.coop().id());
            final Map<String, byte[]> before = new java.util.LinkedHashMap<>();
            before.put("fresh:1", bytes(4, 1));
            before.put("failing:1", bytes(4, 2));
            before.put("empty:1", bytes(4, 3));
            before.put("gone:2", bytes(4, 4));
            assertTrue(join(h.engine.saveCaptured(p.member(), new PlayerStatePort.CaptureResult(level(1, before),
                new ProfileStateAdapters.Capture(before, Set.of(), Set.of())), h.engine.captureTicket(p.member()))));

            // Phase one, plugins enabled: stage the adapters. Then the plugins are disabled.
            final ProfileEngine.CaptureTicket ticket = h.engine.captureTicket(p.member());
            final ProfileStateAdapters.Capture staged = registry.capture(MemberPlayFixtures.player(p.member()));
            assertEquals(Set.of("failing:1"), staged.preserved());
            assertEquals(Set.of("empty:1"), staged.cleared());
            assertEquals(3, registry.unregisterAll(plugin));
            assertEquals(0, registry.size());

            // Phase two: the core state with the staged blobs, saved under the ticket minted before.
            final ProfileState core = level(2);
            assertTrue(join(h.engine.saveCaptured(p.member(), new PlayerStatePort.CaptureResult(core.withAdapters(staged.data()), staged),
                ticket)));
            final ProfileState saved = stateOf(h, head(h, p.coop().id(), p.member(), p.epoch()));
            assertEquals(2, saved.level());
            final Map<String, byte[]> adapters = saved.adapters();
            assertEquals(Set.of("fresh:1", "failing:1", "gone:2"), adapters.keySet());
            assertArrayEquals(bytes(4, 9), adapters.get("fresh:1"));
            assertArrayEquals(bytes(4, 2), adapters.get("failing:1"));
            assertArrayEquals(bytes(4, 4), adapters.get("gone:2"));
        }
    }

    // ------------------------------------------------------------------ online removal: SWITCH_OUT

    /**
     * A member who plays the co-op here leaves: they are captured and switched out to their next profile first, then
     * removed. The co-op items are only in the salvage source (the consumed stream's last snapshot), the player holds the
     * next profile's state: no item in two places, none lost. The leave event fires and the player is told.
     */
    @Test
    void onlineLeaveSwitchesOutFirst() throws Exception {
        try (CoopEngine h = engine()) {
            final Party p = party(h);
            final PlayingPorts ports = ports(h);
            final List<String> notices = new CopyOnWriteArrayList<>();
            h.engine.onPlayerNotice((player, text) -> {
                if (player.equals(p.member())) notices.add(text);
            });
            ports.live.put(p.member(), level(2));
            online(h, p.member());
            switched(h, p.member(), p.coop().id());
            ports.live.put(p.member(), level(60));

            join(h.engine.leave(p.member(), p.coop().id()));
            final PlayerAccount account = h.account(p.member());
            assertEquals(p.kiwi().id(), account.activeProfile());
            assertEquals(p.kiwiEpoch(), account.activeEpoch());
            assertFalse(account.owns(p.coop().id()));
            assertNull(h.profile(p.coop().id()).member(p.member()));
            assertEquals(2, ports.levelOf(p.member()), "the player kept the co-op state after leaving");
            assertEquals(MemberStateHead.State.CONSUMED, head(h, p.coop().id(), p.member(), p.epoch()).state());
            final List<ProfileSalvage> salvage = CoopMembership.salvageByProfile(h.file, p.coop().id(), null).items();
            assertEquals(1, salvage.size());
            assertEquals(ProfileSalvage.State.UNAVAILABLE, salvage.getFirst().state());
            assertNotEquals(ProfileSalvage.NIL, salvage.getFirst().sourceSnapshotId());
            assertEquals(60, snapshotState(h, p.coop().id(), p.member(), p.epoch(), salvage.getFirst().sourceSnapshotId()).level(),
                "the salvage source is not the state the member left with");
            h.drainUntil(event -> event instanceof CoopMemberLeaveEvent leave && leave.getPlayerId().equals(p.member())
                && leave.getReason() == CoopMemberLeaveEvent.Reason.LEFT);
            assertEquals(1, notices.size(), "notices: " + notices);
            // Saves go to the next profile now.
            assertTrue(save(h, p.member(), level(3)));
            assertEquals(3, stateOf(h, head(h, p.kiwi().id(), p.member(), p.kiwiEpoch())).level());
        }
    }

    /** A kick by vote of a member who plays the co-op here switches them out first, the same way. */
    @Test
    void onlineKickSwitchesOutFirst() throws Exception {
        try (CoopEngine h = engine()) {
            final Party p = party(h);
            final PlayingPorts ports = ports(h);
            ports.live.put(p.member(), level(2));
            online(h, p.member());
            switched(h, p.member(), p.coop().id());
            ports.live.put(p.member(), level(61));
            h.clockOffset.set(INACTIVE_SHIFT);

            final CoopKickVoteInfo kicked = join(h.engine.startKickVote(p.owner(), p.coop().id(), p.member()));
            assertEquals(CoopKickVoteState.EXECUTED, kicked.state());
            assertNull(h.profile(p.coop().id()).member(p.member()));
            assertEquals(p.kiwi().id(), h.account(p.member()).activeProfile());
            assertEquals(2, ports.levelOf(p.member()));
            final List<ProfileSalvage> salvage = CoopMembership.salvageByProfile(h.file, p.coop().id(), null).items();
            assertEquals(1, salvage.size());
            assertEquals(61, snapshotState(h, p.coop().id(), p.member(), p.epoch(), salvage.getFirst().sourceSnapshotId()).level());
            h.drainUntil(event -> event instanceof CoopMemberLeaveEvent leave && leave.getPlayerId().equals(p.member())
                && leave.getReason() == CoopMemberLeaveEvent.Reason.KICKED);
        }
    }

    /**
     * A member whose only profile is the co-op is switched out to a fresh solo profile created for them, which takes
     * the slot the membership frees.
     */
    @Test
    void onlineLeaveWithoutAnotherProfileGetsAFreshOne() throws Exception {
        try (CoopEngine h = engine()) {
            final UUID owner = UUID.randomUUID();
            final UUID member = UUID.randomUUID();
            final AuroraProfile coop = h.coop(owner, "Apple");
            h.admit(owner, coop.id(), member);
            h.activate(member, coop.id());
            final PlayingPorts ports = ports(h);
            ports.live.put(member, level(62));
            online(h, member);

            join(h.engine.leave(member, coop.id()));
            final PlayerAccount account = h.account(member);
            assertFalse(account.owns(coop.id()));
            assertEquals(1, account.profileIds().size(), "profiles: " + account.profileIds());
            final UUID fresh = account.profileIds().getFirst();
            assertEquals(fresh, account.activeProfile());
            final AuroraProfile created = h.profile(fresh);
            assertEquals(member, created.accountId());
            assertFalse(created.coop());
            assertTrue(ports.state(member).isEmpty(), "a fresh profile starts empty");
            final List<ProfileSalvage> salvage = CoopMembership.salvageByProfile(h.file, coop.id(), null).items();
            assertEquals(1, salvage.size());
            assertEquals(62, snapshotState(h, coop.id(), member, salvage.getFirst().removedEpoch(),
                salvage.getFirst().sourceSnapshotId()).level());
        }
    }

    // ------------------------------------------------------------------ offline removal: REPOINT

    /**
     * A member who quit with the co-op active leaves while offline: the removal intent moves their account to the next
     * profile, so the next authoritative login restores that profile's committed head and never the co-op items the
     * player left with.
     */
    @Test
    void offlineLeaveRepointsTheAccount() throws Exception {
        try (CoopEngine h = engine()) {
            final Party p = party(h);
            final PlayingPorts ports = ports(h);
            ports.live.put(p.member(), level(2));
            online(h, p.member());
            switched(h, p.member(), p.coop().id());
            ports.live.put(p.member(), level(63));
            assertTrue(quit(h, p.member()));

            join(h.engine.leave(p.member(), p.coop().id()));
            final PlayerAccount account = h.account(p.member());
            assertEquals(p.kiwi().id(), account.activeProfile());
            assertEquals(p.kiwiEpoch(), account.activeEpoch());
            assertFalse(account.owns(p.coop().id()));
            assertNull(account.pendingIntent());
            final List<ProfileSalvage> salvage = CoopMembership.salvageByProfile(h.file, p.coop().id(), null).items();
            assertEquals(1, salvage.size());
            assertEquals(63, snapshotState(h, p.coop().id(), p.member(), p.epoch(), salvage.getFirst().sourceSnapshotId()).level());

            // The vanilla playerdata still holds the co-op items; the login restores the solo head instead.
            ports.live.put(p.member(), level(63));
            final UUID gate = rejoin(h, p.member());
            final ProfileEngine.LoginRestore restore = restore(h, p.member(), gate);
            h.engine.releaseLoginGate(p.member(), gate);
            assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, restore.outcome(), restore.detail());
            assertEquals(p.kiwi().id(), restore.profileId());
            assertEquals(2, ports.levelOf(p.member()), "the co-op items came back onto the removed member");
        }
    }

    // ------------------------------------------------------------------ MEMBER_ONLINE

    /**
     * {@code MEMBER_ONLINE} remains for a member who cannot be moved safely, and nothing is removed: online here but not
     * admitted, a switch out that is refused, a session live on another node (the removed member's {@code Q} is held
     * there), and offline without {@code login.authoritative}.
     */
    @Test
    void memberOnlineWhenTheMemberCannotBeMovedSafely() throws Exception {
        final Party p;
        try (CoopEngine h = engine()) {
            p = party(h);
            final PlayingPorts ports = ports(h);
            final AuroraProfile coop = h.profile(p.coop().id());

            // Online here, session not admitted.
            h.activate(p.member(), p.coop().id());
            ports.online.add(p.member());
            final var before = tree(store());
            refusedWith(ProfileErrorCode.MEMBER_ONLINE, () -> join(h.engine.leave(p.member(), p.coop().id())));
            assertEquals(before, tree(store()), "a MEMBER_ONLINE refusal wrote something");
            ports.online.remove(p.member());
            h.activate(p.member(), p.kiwi().id());

            // The switch out is refused: the member stays, in the co-op.
            ports.live.put(p.member(), level(2));
            online(h, p.member());
            switched(h, p.member(), p.coop().id());
            ports.live.put(p.member(), level(64));
            ports.failNextApply.set(new ProfileOperationException(ProfileErrorCode.ADAPTER_REQUIRED_FAILED, "scripted"));
            refusedWith(ProfileErrorCode.MEMBER_ONLINE, () -> join(h.engine.leave(p.member(), p.coop().id())));
            assertNotNull(h.profile(p.coop().id()).member(p.member()));
            assertTrue(h.account(p.member()).owns(p.coop().id()));
            assertEquals(p.coop().id(), h.account(p.member()).activeProfile());
            assertEquals(64, ports.levelOf(p.member()));
            assertTrue(CoopMembership.salvageByProfile(h.file, p.coop().id(), null).items().isEmpty());

            // Offline, but the member's Q is held by a session on another node: the kick is refused, the vote stays PASSED.
            assertTrue(quit(h, p.member()));
            final PlayerSessionOwnership.Grant elsewhere = join(h.players.claim(p.member(), UUID.randomUUID()));
            assertTrue(elsewhere.granted());
            h.clockOffset.set(INACTIVE_SHIFT);
            refusedWith(ProfileErrorCode.MEMBER_ONLINE, () -> join(h.engine.startKickVote(p.owner(), p.coop().id(), p.member())));
            assertNotNull(h.profile(p.coop().id()).member(p.member()));
            assertEquals(p.coop().id(), h.account(p.member()).activeProfile());
            assertEquals(CoopKickVoteState.PASSED, join(h.engine.openKickVote(p.coop().id())).orElseThrow().state());
            join(h.players.release(elsewhere.claim()));
            assertEquals(coop.id(), h.profile(p.coop().id()).id());
        }
        // Offline without authoritative login: the next join would load the co-op items from vanilla playerdata.
        try (CoopEngine h = engine(playSettings(false, 15_000, Set.of()), new PlayingPorts())) {
            final var before = tree(store());
            refusedWith(ProfileErrorCode.MEMBER_ONLINE, () -> join(h.engine.leave(p.member(), p.coop().id())));
            assertEquals(before, tree(store()));
            assertNotNull(h.profile(p.coop().id()).member(p.member()));
        }
    }

    // ------------------------------------------------------------------ CoopShutdownPendingIntentRetained

    /**
     * An intent in flight when the process stops is retained, not aborted by the shutdown: after the restart it is still
     * PREPARED with its reservations; while the player's session claim is held elsewhere the start leaves it pending and
     * lists it; the player's next admission resolves it (standalone: aborted, its own reservations cleared).
     */
    @Test
    void CoopShutdownPendingIntentRetained() throws Exception {
        final Party p;
        final UUID operation;
        try (CoopEngine h = engine()) {
            p = party(h);
            final ProfileClaim claim = join(h.profiles.claimTransaction(p.coop().id(), UUID.randomUUID())).claim();
            final PlayerClaim q = join(h.players.claim(p.member(), UUID.randomUUID())).claim();
            try {
                final AuroraProfile current = h.profile(p.coop().id());
                final MemberStateHead head = h.file.memberHead(p.coop().id(), p.member(), p.epoch()).orElse(null);
                final CoopIntentPlan plan = CoopMembership.leave(op(CoopOperation.Kind.MEMBER_REMOVE, p.member()), current,
                    h.account(p.member()), head, List.of(), List.of(), h.file.storeClockMillis(), 0L);
                CoopMembershipFixtures.preparePlan(h.file, plan, claim, q, null);
                operation = plan.op().operationId();
            } finally {
                join(h.profiles.releaseTransaction(claim));
                join(h.players.release(q));
            }
            // The process stops with the intent PREPARED.
        }
        try (CoopEngine h = new CoopEngine(store(), playSettings(), true, new PlayingPorts())) {
            assertEquals(ProfileIntent.Phase.PREPARED, h.file.intent(operation).orElseThrow().phase(), "the shutdown dropped the intent");
            assertEquals(operation, h.account(p.member()).pendingIntent());
            final PlayerSessionOwnership.Grant elsewhere = join(h.players.claim(p.member(), UUID.randomUUID()));
            assertTrue(elsewhere.granted());
            final ProfileEngine.StoreStatus status = h.open();
            assertEquals(StoreFormat.Gate.FORMAT_2, status.gate());
            assertTrue(status.unresolvedIntents().contains(operation), "the start did not list the retained intent: " + status);
            assertEquals(ProfileIntent.Phase.PREPARED, h.file.intent(operation).orElseThrow().phase());
            ports(h).online.add(p.member());
            assertFalse(join(h.engine.admitSession(p.member())).admitted());

            join(h.players.release(elsewhere.claim()));
            final ProfileEngine.Admission admitted = join(h.engine.admitSession(p.member()));
            assertTrue(admitted.admitted(), admitted.reason());
            final ProfileIntent after = h.file.intent(operation).orElseThrow();
            assertEquals(ProfileIntent.Phase.ABORTED, after.phase());
            assertNotNull(h.profile(p.coop().id()).member(p.member()), "the aborted leave removed the member");
            assertTrue(h.account(p.member()).owns(p.coop().id()));
            assertNull(h.account(p.member()).pendingIntent());
            assertNull(h.profile(p.coop().id()).pendingIntent());
        }
    }

    // ------------------------------------------------------------------ D3: session marker and vanilla adoption

    private static void deleteMarker(final Path store, final UUID player) throws Exception {
        Files.deleteIfExists(store.resolve("sessions").resolve(player + ".awfp"));
    }

    private static SessionMarker marker(final CoopEngine h, final UUID player) throws Exception {
        return h.file.sessionMarker(player).orElseThrow(() -> new AssertionError("no session marker of " + player));
    }

    /** A member who played the co-op, whose last committed head is level 40 (published and closed by the quit). */
    private Party playedAndQuit(final CoopEngine h) throws Exception {
        final Party p = party(h);
        online(h, p.member());
        switched(h, p.member(), p.coop().id());
        ports(h).live.put(p.member(), level(40));
        assertTrue(quit(h, p.member()));
        assertEquals(40, stateOf(h, head(h, p.coop().id(), p.member(), p.epoch())).level());
        return p;
    }

    /**
     * The session marker over one session: the login opens it for the new generation with the provenance cleared, a
     * lockstep save records that the vanilla playerdata holds the active profile, the committed quit closes it, and a
     * final save that is refused leaves it open (so the next login re-examines the vanilla playerdata).
     */
    @Test
    void sessionMarkerOpensSyncsAndCloses() throws Exception {
        try (CoopEngine h = engine()) {
            final Party p = playedAndQuit(h);
            final SessionMarker afterQuit = marker(h, p.member());
            assertFalse(afterQuit.open(), "the committed quit did not close the marker");
            assertTrue(afterQuit.provenanceIs(p.coop().id(), p.epoch(), "node-a"));

            final UUID gate = rejoin(h, p.member());
            assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, restore(h, p.member(), gate).outcome());
            h.engine.releaseLoginGate(p.member(), gate);
            final SessionMarker opened = marker(h, p.member());
            assertTrue(opened.open());
            assertEquals(h.account(p.member()).sessionGeneration(), opened.generation());
            assertEquals(afterQuit.generation() + 1L, opened.generation());
            assertNull(opened.vanillaProfile(), "the login kept a provenance it cannot vouch for");

            assertTrue(save(h, p.member(), level(41)));
            final SessionMarker synced = marker(h, p.member());
            assertTrue(synced.open());
            assertTrue(synced.provenanceIs(p.coop().id(), p.epoch(), "node-a"));

            assertTrue(quit(h, p.member()));
            assertFalse(marker(h, p.member()).open());

            // A final save that is refused leaves the marker open.
            final UUID again = rejoin(h, p.member());
            restore(h, p.member(), again);
            h.engine.releaseLoginGate(p.member(), again);
            refusedWith(ProfileErrorCode.CLAIM_LOST, () -> join(h.engine.saveCapturedAndClose(p.member(),
                PlayerStatePort.CaptureResult.of(level(42)), null)));
            assertTrue(marker(h, p.member()).open(), "a refused final save closed the marker");
        }
    }

    /**
     * The 52 -> 53 upgrade: the store holds a committed head but no session marker (Build 52 never wrote one) and this
     * node's vanilla playerdata is newer than the head: the join adopts the vanilla state once into the head (nothing is
     * applied to the player) and records the provenance. With vanilla playerdata older than the head, or after a journal
     * was recovered at this login, the committed head is restored instead.
     */
    @Test
    void upgradeWithoutMarkerAdoptsNewerVanillaOnce() throws Exception {
        try (CoopEngine h = engine()) {
            final Party p = playedAndQuit(h);
            final PlayingPorts ports = ports(h);
            final MemberStateHead committed = head(h, p.coop().id(), p.member(), p.epoch());

            // Vanilla older than the head: restored.
            deleteMarker(store(), p.member());
            ports.live.put(p.member(), level(55));
            ports.vanillaModified.put(p.member(), 1L);
            UUID gate = rejoin(h, p.member());
            ProfileEngine.LoginRestore restore = restore(h, p.member(), gate);
            assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, restore.outcome(), restore.detail());
            assertEquals(40, ports.levelOf(p.member()));
            h.engine.releaseLoginGate(p.member(), gate);
            assertTrue(quit(h, p.member()));

            // A journal recovered at this login decided the state: never adopted.
            deleteMarker(store(), p.member());
            ports.live.put(p.member(), level(55));
            ports.vanillaModified.put(p.member(), System.currentTimeMillis() + 60_000L);
            gate = rejoin(h, p.member());
            restore = join(h.engine.restoreOnLogin(p.member(), gate, () -> false, true));
            assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, restore.outcome(), restore.detail());
            assertEquals(40, ports.levelOf(p.member()));
            h.engine.releaseLoginGate(p.member(), gate);
            assertTrue(quit(h, p.member()));
            final MemberStateHead beforeAdoption = head(h, p.coop().id(), p.member(), p.epoch());
            assertEquals(committed.revision() + 2L, beforeAdoption.revision(), "each quit published once");

            // Vanilla newer than the head and no marker: adopted.
            deleteMarker(store(), p.member());
            ports.live.put(p.member(), level(55));
            ports.vanillaModified.put(p.member(), System.currentTimeMillis() + 60_000L);
            final int applied = ports.applied.size();
            gate = rejoin(h, p.member());
            restore = restore(h, p.member(), gate);
            assertEquals(ProfileEngine.LoginRestore.Outcome.ADOPTED, restore.outcome(), restore.detail());
            assertEquals(p.coop().id(), restore.profileId());
            assertNull(restore.code());
            assertEquals(applied, ports.applied.size(), "an adoption applied something to the player");
            assertEquals(55, ports.levelOf(p.member()));
            final MemberStateHead adopted = head(h, p.coop().id(), p.member(), p.epoch());
            assertEquals(beforeAdoption.revision() + 1L, adopted.revision());
            assertEquals(55, stateOf(h, adopted).level());
            final SessionMarker opened = marker(h, p.member());
            assertTrue(opened.open());
            assertEquals(h.account(p.member()).sessionGeneration(), opened.generation());
            assertTrue(opened.provenanceIs(p.coop().id(), p.epoch(), "node-a"));
            h.engine.releaseLoginGate(p.member(), gate);
            assertTrue(save(h, p.member(), level(56)));
        }
    }

    /**
     * The first entry into a co-op (UNINITIALIZED head) on a node that has vanilla playerdata of the player: the vanilla
     * state is adopted as the stream's first snapshot; {@link ProfileState#empty()} is never applied over it.
     */
    @Test
    void firstEntryWithVanillaPlayerdataAdoptsIt() throws Exception {
        try (CoopEngine h = engine()) {
            final Party p = party(h);
            final PlayingPorts ports = ports(h);
            h.activate(p.member(), p.coop().id());
            final MemberStateHead uninitialized = head(h, p.coop().id(), p.member(), p.epoch());
            ports.live.put(p.member(), level(33));
            ports.vanillaModified.put(p.member(), System.currentTimeMillis());
            final UUID gate = rejoin(h, p.member());
            final ProfileEngine.LoginRestore restore = restore(h, p.member(), gate);
            h.engine.releaseLoginGate(p.member(), gate);
            assertEquals(ProfileEngine.LoginRestore.Outcome.ADOPTED, restore.outcome(), restore.detail());
            assertTrue(ports.applied.isEmpty(), "the empty initial state was applied over the vanilla playerdata");
            assertEquals(33, ports.levelOf(p.member()));
            final MemberStateHead first = head(h, p.coop().id(), p.member(), p.epoch());
            assertEquals(uninitialized.revision() + 1L, first.revision());
            assertEquals(33, stateOf(h, first).level());
        }
    }

    /**
     * An unclean previous session (the marker is still open: a crash after a lockstep save) with vanilla playerdata newer
     * than the head adopts it when the marker's provenance names the active profile, and restores the committed head
     * when the provenance names another profile (the vanilla file holds that one's state). A clean (closed) previous
     * session never adopts.
     */
    @Test
    void uncleanSessionAdoptsOnlyWithMatchingProvenance() throws Exception {
        try (CoopEngine h = engine()) {
            final Party p = playedAndQuit(h);
            final PlayingPorts ports = ports(h);

            // Clean previous session, vanilla newer: restored.
            ports.live.put(p.member(), level(77));
            ports.vanillaModified.put(p.member(), System.currentTimeMillis() + 60_000L);
            UUID gate = rejoin(h, p.member());
            ProfileEngine.LoginRestore restore = restore(h, p.member(), gate);
            assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, restore.outcome(), restore.detail());
            assertEquals(40, ports.levelOf(p.member()));
            h.engine.releaseLoginGate(p.member(), gate);

            // A lockstep save, then the process dies (no quit): the next login adopts the newer vanilla state.
            assertTrue(save(h, p.member(), level(41)));
            ports.live.put(p.member(), level(42));
            ports.vanillaModified.put(p.member(), System.currentTimeMillis() + 120_000L);
            gate = rejoin(h, p.member());
            restore = restore(h, p.member(), gate);
            assertEquals(ProfileEngine.LoginRestore.Outcome.ADOPTED, restore.outcome(), restore.detail());
            assertEquals(42, stateOf(h, head(h, p.coop().id(), p.member(), p.epoch())).level());
            h.engine.releaseLoginGate(p.member(), gate);

            // The provenance names the co-op, the account names the solo profile now: the solo head is restored.
            assertTrue(save(h, p.member(), level(43)));
            ports.live.put(p.member(), level(44));
            switched(h, p.member(), p.kiwi().id());
            final int kiwiLevel = stateOf(h, head(h, p.kiwi().id(), p.member(), p.kiwiEpoch())).level();
            assertEquals(kiwiLevel, ports.levelOf(p.member()));
            ports.live.put(p.member(), level(45));
            ports.vanillaModified.put(p.member(), System.currentTimeMillis() + 180_000L);
            gate = rejoin(h, p.member());
            restore = restore(h, p.member(), gate);
            assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, restore.outcome(), restore.detail());
            assertEquals(p.kiwi().id(), restore.profileId());
            assertEquals(kiwiLevel, ports.levelOf(p.member()), "vanilla data of another profile was adopted into the solo head");
        }
    }

    /** An adoption that cannot be published refuses the join with that code; neither state is changed or applied. */
    @Test
    void adoptionThatCannotPublishRefusesTheJoin() throws Exception {
        final ProfileStateAdapters registry = new ProfileStateAdapters();
        final org.bukkit.plugin.Plugin plugin = MemberPlayFixtures.plugin("HardPlugin");
        final TestAdapter hard = TestAdapter.required("hard", plugin).returning(bytes(2, 1));
        registry.register(plugin, hard);
        try (CoopEngine h = engine(playSettings(), new PlayingPorts(registry))) {
            final Party p = playedAndQuit(h);
            final PlayingPorts ports = ports(h);
            final MemberStateHead committed = head(h, p.coop().id(), p.member(), p.epoch());
            deleteMarker(store(), p.member());
            ports.live.put(p.member(), level(55));
            ports.vanillaModified.put(p.member(), System.currentTimeMillis() + 60_000L);
            hard.throwing();
            final int applied = ports.applied.size();
            final UUID gate = rejoin(h, p.member());
            final ProfileEngine.LoginRestore restore = restore(h, p.member(), gate);
            assertEquals(ProfileEngine.LoginRestore.Outcome.REFUSED, restore.outcome());
            assertEquals(ProfileErrorCode.ADAPTER_REQUIRED_FAILED, restore.code(), restore.detail());
            assertEquals(committed, head(h, p.coop().id(), p.member(), p.epoch()));
            assertEquals(applied, ports.applied.size());
            assertEquals(55, ports.levelOf(p.member()));
        }
    }

    // ------------------------------------------------------------------ D1: co-op off under auto

    /**
     * {@code login.authoritative: auto} with co-op off is the Build 52 solo behaviour: no login gate, nothing restored
     * (the join keeps the vanilla playerdata), saves publish as before and no session marker is written. With
     * {@code true} the same solo join is authoritative.
     */
    @Test
    void soloParityWithCoopOffUnderAuto() throws Exception {
        final UUID player = UUID.randomUUID();
        final ProfileSettings off = CoopFixtures.settings(ProfileSettings.DEFAULT_TYPES);
        assertEquals(ProfileSettings.Authoritative.AUTO, off.login().authoritative());
        assertFalse(off.authoritativeLogin());
        final AuroraProfile solo;
        try (CoopEngine h = engine(off, new PlayingPorts())) {
            final PlayingPorts ports = ports(h);
            solo = h.create(player, "Kiwi");
            h.activate(player, solo.id());
            ports.live.put(player, level(9));
            ports.vanillaModified.put(player, System.currentTimeMillis());
            ports.online.add(player);
            assertNull(h.engine.gateLogin(player), "a gate with co-op off under auto");
            assertTrue(join(h.engine.admitSession(player)).admitted());
            final ProfileEngine.LoginRestore restore = join(h.engine.restoreOnLogin(player));
            assertEquals(ProfileEngine.LoginRestore.Outcome.NONE, restore.outcome());
            assertTrue(ports.applied.isEmpty());
            assertEquals(9, ports.levelOf(player));
            assertFalse(h.engine.frozen(player));
            assertTrue(save(h, player, level(9)));
            assertTrue(join(h.engine.saveCapturedAndClose(player, PlayerStatePort.CaptureResult.of(level(10)),
                h.engine.captureTicket(player))));
            assertTrue(h.file.sessionMarker(player).isEmpty(), "a session marker was written while authoritative login is off");
        }
        final ProfileSettings strict = new ProfileSettings(off.enabled(), off.mode(), off.defaultSlots(), off.maxSlots(), off.switching(),
            off.isolation(), off.worlds(), off.coordination(), off.directory(), off.repository(), off.types(), off.names(), off.migration(),
            off.coop(), off.shutdown(), new ProfileSettings.Login(ProfileSettings.Authoritative.TRUE), off.adapters());
        assertTrue(strict.authoritativeLogin());
        try (CoopEngine h = engine(strict, new PlayingPorts())) {
            final PlayingPorts ports = ports(h);
            ports.live.put(player, level(99));
            final UUID gate = rejoin(h, player);
            final ProfileEngine.LoginRestore restore = restore(h, player, gate);
            assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, restore.outcome(), restore.detail());
            assertEquals(10, ports.levelOf(player));
            assertTrue(marker(h, player).open());
        }
    }

    // ------------------------------------------------------------------ D4: fail closed

    /**
     * A save that cannot succeed (reported by the runtime's lockstep or quit path) freezes the player: the handler that
     * disconnects them fires once, switches are refused, {@code save(profileId)} skips them; the next admission clears it.
     */
    @Test
    void refusedSavesFreezeUntilTheNextSession() throws Exception {
        assertTrue(ProfileEngine.fatalSaveCode(ProfileErrorCode.STATE_TOO_LARGE));
        assertTrue(ProfileEngine.fatalSaveCode(ProfileErrorCode.ADAPTER_REQUIRED_FAILED));
        assertFalse(ProfileEngine.fatalSaveCode(ProfileErrorCode.STALE_STATE));
        assertFalse(ProfileEngine.fatalSaveCode(ProfileErrorCode.CLAIM_LOST));
        try (CoopEngine h = engine()) {
            final Party p = party(h);
            online(h, p.member());
            switched(h, p.member(), p.coop().id());
            final List<UUID> disconnected = new CopyOnWriteArrayList<>();
            h.engine.onSaveRefused((player, reason) -> disconnected.add(player));
            h.engine.refuseSaves(p.member(), "STATE_TOO_LARGE: scripted");
            h.engine.refuseSaves(p.member(), "STATE_TOO_LARGE: scripted again");
            assertEquals(List.of(p.member()), disconnected, "the disconnect fired " + disconnected.size() + " times");
            assertTrue(h.engine.savesRefused(p.member()));
            assertTrue(h.engine.frozen(p.member()));
            final ProfileSwitchResult refused = join(h.engine.switchProfile(p.member(), p.kiwi().id()));
            assertEquals(ProfileErrorCode.STATE_TOO_LARGE, refused.errorCode(), refused.message());
            assertEquals(p.coop().id(), h.account(p.member()).activeProfile());
            refusedWith(ProfileErrorCode.PLAYER_OFFLINE, () -> join(h.engine.save(p.coop().id())));

            online(h, p.member());
            assertFalse(h.engine.savesRefused(p.member()));
            assertFalse(h.engine.frozen(p.member()));
            switched(h, p.member(), p.kiwi().id());
        }
    }

    /** A pending journal that the login's recovery could not resolve refuses the join (RECOVERY_REQUIRED): never vanilla. */
    @Test
    void pendingJournalAfterFailedRecoveryRefusesTheJoin() throws Exception {
        try (CoopEngine h = engine()) {
            final Party p = party(h);
            final PlayingPorts ports = ports(h);
            online(h, p.member());
            switched(h, p.member(), p.coop().id());
            ports.live.put(p.member(), level(31));
            ports.offlineOnFailedApply.set(true);
            ports.failNextApply.set(new ProfileOperationException(ProfileErrorCode.ADAPTER_REQUIRED_FAILED, "scripted"));
            assertEquals(ProfileErrorCode.RECOVERY_REQUIRED, join(h.engine.switchProfile(p.member(), p.kiwi().id())).errorCode());

            // The rejoin's recovery cannot run (the player left again before it): the journal stays pending.
            ports.live.put(p.member(), level(99));
            ports.online.add(p.member());
            final UUID gate = h.engine.gateLogin(p.member());
            assertTrue(join(h.engine.admitSession(p.member())).admitted());
            ports.online.remove(p.member());
            join(h.engine.recoverPending(p.member()));
            assertTrue(h.file.transitionsFor(p.member()).stream().anyMatch(journal -> !journal.terminal()));
            ports.online.add(p.member());
            final int applied = ports.applied.size();
            final ProfileEngine.LoginRestore restore = restore(h, p.member(), gate);
            assertEquals(ProfileEngine.LoginRestore.Outcome.REFUSED, restore.outcome());
            assertEquals(ProfileErrorCode.RECOVERY_REQUIRED, restore.code(), restore.detail());
            assertEquals(applied, ports.applied.size());
            assertEquals(99, ports.levelOf(p.member()));
        }
    }

    /** A restore whose join is no longer current (its gate was replaced, or the runtime reports it stale) does nothing. */
    @Test
    void staleLoginChainIsRefused() throws Exception {
        try (CoopEngine h = engine()) {
            final Party p = playedAndQuit(h);
            final PlayingPorts ports = ports(h);
            ports.live.put(p.member(), level(99));
            final UUID first = rejoin(h, p.member());
            final UUID second = h.engine.gateLogin(p.member());
            assertFalse(h.engine.gateHeld(p.member(), first));
            assertTrue(h.engine.gateHeld(p.member(), second));
            final int applied = ports.applied.size();
            ProfileEngine.LoginRestore restore = restore(h, p.member(), first);
            assertEquals(ProfileEngine.LoginRestore.Outcome.REFUSED, restore.outcome());
            assertEquals(ProfileErrorCode.STALE_STATE, restore.code(), restore.detail());
            restore = join(h.engine.restoreOnLogin(p.member(), second, () -> true, false));
            assertEquals(ProfileEngine.LoginRestore.Outcome.REFUSED, restore.outcome());
            assertEquals(ProfileErrorCode.STALE_STATE, restore.code(), restore.detail());
            assertEquals(applied, ports.applied.size(), "a stale login chain applied something");
            assertEquals(99, ports.levelOf(p.member()));
            restore = restore(h, p.member(), second);
            assertEquals(ProfileEngine.LoginRestore.Outcome.RESTORED, restore.outcome(), restore.detail());
            assertEquals(40, ports.levelOf(p.member()));
        }
    }

    // ------------------------------------------------------------------ E1, E3

    /** E1: {@code save(profileId)} saves every member playing the profile here; with none it is PLAYER_OFFLINE. */
    @Test
    void saveSavesEveryMemberPlayingTheProfile() throws Exception {
        try (CoopEngine h = engine()) {
            final Party p = party(h);
            final PlayingPorts ports = ports(h);
            final UUID ownerEpoch = p.coop().member(p.owner()).epoch();
            refusedWith(ProfileErrorCode.PLAYER_OFFLINE, () -> join(h.engine.save(p.coop().id())));
            h.activate(p.owner(), p.coop().id());
            online(h, p.owner());
            online(h, p.member());
            switched(h, p.member(), p.coop().id());
            ports.live.put(p.owner(), level(11));
            ports.live.put(p.member(), level(22));
            join(h.engine.save(p.coop().id()));
            assertEquals(11, stateOf(h, head(h, p.coop().id(), p.owner(), ownerEpoch)).level());
            assertEquals(22, stateOf(h, head(h, p.coop().id(), p.member(), p.epoch())).level());
            quit(h, p.owner());
            quit(h, p.member());
            refusedWith(ProfileErrorCode.PLAYER_OFFLINE, () -> join(h.engine.save(p.coop().id())));
        }
    }

    /** E3: the fresh solo profile created for a member whose switch out then fails is deleted again. */
    @Test
    void failedRemovalDeletesTheFreshProfile() throws Exception {
        try (CoopEngine h = engine()) {
            final UUID owner = UUID.randomUUID();
            final UUID member = UUID.randomUUID();
            final AuroraProfile coop = h.coop(owner, "Apple");
            h.admit(owner, coop.id(), member);
            h.activate(member, coop.id());
            final PlayingPorts ports = ports(h);
            ports.live.put(member, level(62));
            online(h, member);
            ports.failNextApply.set(new ProfileOperationException(ProfileErrorCode.ADAPTER_REQUIRED_FAILED, "scripted"));
            refusedWith(ProfileErrorCode.MEMBER_ONLINE, () -> join(h.engine.leave(member, coop.id())));
            final PlayerAccount account = h.account(member);
            assertEquals(List.of(coop.id()), account.profileIds(), "the fresh profile of a failed removal was left in the account");
            assertEquals(coop.id(), account.activeProfile());
            assertNotNull(h.profile(coop.id()).member(member));
            assertEquals(62, ports.levelOf(member));
        }
    }
}
