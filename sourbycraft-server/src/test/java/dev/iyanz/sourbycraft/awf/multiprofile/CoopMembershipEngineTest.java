package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.CLASSIC;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.IRONMAN;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.RETAIN;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.SLOTS;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.join;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.op;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusal;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusedWith;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.tree;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.INACTIVE_SHIFT;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.MINUTE;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.coopSettings;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.indexed;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.CoopDetails;
import dev.iyanz.sourbycraft.api.profile.CoopInviteEvent;
import dev.iyanz.sourbycraft.api.profile.CoopInviteInfo;
import dev.iyanz.sourbycraft.api.profile.CoopInviteState;
import dev.iyanz.sourbycraft.api.profile.CoopKickVoteInfo;
import dev.iyanz.sourbycraft.api.profile.CoopKickVoteState;
import dev.iyanz.sourbycraft.api.profile.CoopMemberInfo;
import dev.iyanz.sourbycraft.api.profile.CoopMemberJoinEvent;
import dev.iyanz.sourbycraft.api.profile.CoopMemberLeaveEvent;
import dev.iyanz.sourbycraft.api.profile.CoopPage;
import dev.iyanz.sourbycraft.api.profile.CoopRole;
import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.api.profile.ProfileRequest;
import dev.iyanz.sourbycraft.api.profile.ProfileSwitchResult;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.CoopEngine;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.PlayerClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.ProfileClaim;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileRepository.CommitResult;
import dev.iyanz.sourbycraft.awf.redis.fabric.ClaimResult;
import dev.iyanz.sourbycraft.update.BuildVersion;
import dev.iyanz.sourbycraft.update.StoreDowngradeGuard;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Build 52 co-op membership owner cases through the engine (COOP-DESIGN §10 slice 4) over a FILE store:
 * names and aliases, the shared label, accept replay, the cap, an invite that expires at commit, kick votes and
 * their electorate, owner protections, removal and rejoin epochs, the salvage bound, and the refusals Build 52
 * defines ({@code COOP_DISABLED}, {@code MIGRATION_REQUIRED}, {@code TYPE_NOT_ALLOWED}, {@code WRONG_NODE},
 * {@code MEMBER_ONLINE}, {@code COOP_SWITCH_UNAVAILABLE}, caps) as Build 53 narrows them (members play a co-op;
 * {@code COOP_SWITCH_UNAVAILABLE} only without {@code login.authoritative}; {@code MEMBER_ONLINE} only for a member who
 * cannot be moved out safely; member play itself is covered by {@link CoopMemberPlayTest}), paged listings, the events
 * (after commit only) and the store feature level with the OD-2 downgrade gate.
 *
 * <p>Deterministic: the engine's store clock is shifted by fixed offsets ({@link CoopEngine#clockOffset}), never
 * waited for; events are taken with bounded waits; no sleeps.</p>
 */
class CoopMembershipEngineTest {

    @TempDir
    Path directory;

    private Path store() {
        return this.directory.resolve("store");
    }

    private CoopEngine engine(final ProfileSettings settings) throws Exception {
        final CoopEngine harness = new CoopEngine(store(), settings, true);
        assertEquals(StoreFormat.Gate.FORMAT_2, harness.open().gate());
        return harness;
    }

    private CoopEngine engine() throws Exception {
        return engine(coopSettings());
    }

    private static boolean joined(final org.bukkit.event.Event event, final UUID player) {
        return event instanceof CoopMemberJoinEvent join && join.getPlayerId().equals(player);
    }

    private static boolean invited(final org.bukkit.event.Event event, final UUID invitee, final CoopInviteState state) {
        return event instanceof CoopInviteEvent invite && invite.getInvite().invitee().equals(invitee) && invite.getInvite().state() == state;
    }

    private static boolean left(final org.bukkit.event.Event event, final UUID player, final CoopMemberLeaveEvent.Reason reason) {
        return event instanceof CoopMemberLeaveEvent leave && leave.getPlayerId().equals(player) && leave.getReason() == reason;
    }

    // ------------------------------------------------------------------ CoopNameCollisionReserved

    /**
     * A co-op profile's name is reserved in every member's account: a member cannot create a profile of the same name
     * (names are unique per account ignoring case, and the co-op is one of the member's profiles), a rolled name
     * never picks it, and it is free again once the membership ended.
     */
    @Test
    void CoopNameCollisionReserved() throws Exception {
        try (CoopEngine h = engine()) {
            final UUID owner = UUID.randomUUID();
            final UUID member = UUID.randomUUID();
            final AuroraProfile coop = h.coop(owner, "Apple");
            h.admit(owner, coop.id(), member);
            final var before = tree(store());
            refusedWith(ProfileErrorCode.NAME_TAKEN, () -> join(h.engine.createDetailed(ProfileRequest.of(member, "skyblock")
                .withName("APPLE"))));
            refusedWith(ProfileErrorCode.NAME_TAKEN, () -> join(h.engine.createDetailed(ProfileRequest.of(owner, "skyblock")
                .withName("apple"))));
            assertEquals(before, tree(store()), "a refused name wrote something");
            // The store refuses it too, not only the engine: a create intent naming it is refused at PREPARE.
            final PlayerClaim q = join(h.players.claim(member, UUID.randomUUID())).claim();
            try {
                final CoopFixtures.CreatePlan plan = CoopFixtures.planCreate(h.file, member, "Apple", CLASSIC);
                refusedWith(ProfileErrorCode.NAME_TAKEN, () -> CoopFixtures.prepareCreate(h.file, q, plan));
            } finally {
                join(h.players.release(q));
            }
            // Rolled names avoid it; the member's slots fill up with other names.
            for (int i = 1; i < SLOTS; i++) {
                final String rolled = join(h.engine.createDetailed(ProfileRequest.of(member, "skyblock"))).name();
                assertNotEquals(CuteNames.key("Apple"), CuteNames.key(rolled), "a rolled name collided with the co-op");
            }
            assertEquals(SLOTS, h.account(member).profileIds().size());
            // After the membership ends the name is the member's to use.
            join(h.engine.leave(member, coop.id()));
            assertEquals("Apple", join(h.engine.createDetailed(ProfileRequest.of(member, "skyblock").withName("apple"))).name());
        }
    }

    // ------------------------------------------------------------------ CoopInviteAliasCollisionResolved

    @Test
    void CoopInviteAliasCollisionResolved() throws Exception {
        try (CoopEngine h = engine()) {
            final UUID owner = UUID.randomUUID();
            final UUID member = UUID.randomUUID();
            // The member already owns "Apple" (alias apple) and "Apple 2" (alias apple-2).
            final AuroraProfile own = h.create(member, "Apple");
            final AuroraProfile own2 = h.create(member, "Apple 2");
            assertEquals("apple", h.account(member).alias(own.id()));
            assertEquals("apple-2", h.account(member).alias(own2.id()));
            // Joining another account's "Apple" is allowed (names are per owner); the alias is made unique per account.
            final AuroraProfile coop = h.coop(owner, "Apple");
            h.admit(owner, coop.id(), member);
            final PlayerAccount account = h.account(member);
            assertEquals("apple-3", account.alias(coop.id()));
            assertEquals("apple", account.alias(own.id()), "joining renamed the member's own alias");
            assertEquals("apple-2", account.alias(own2.id()));
            assertEquals(3, new HashSet<>(account.aliases().values()).size());
            // The owner's alias is the owner's own and unchanged.
            assertEquals("apple", h.account(owner).alias(coop.id()));
            // The co-op keeps its name for everyone; the member lists it beside its own profiles.
            assertEquals(List.of(own.id(), own2.id(), coop.id()), join(h.engine.list(member)).stream()
                .map(dev.iyanz.sourbycraft.api.profile.ProfileInfo::id).toList());
            assertEquals("Apple", h.profile(coop.id()).name());
        }
    }

    // ------------------------------------------------------------------ CoopSharedRenameDoesNotChangeAliases

    @Test
    void CoopSharedRenameDoesNotChangeAliases() throws Exception {
        try (CoopEngine h = engine()) {
            final UUID owner = UUID.randomUUID();
            final UUID member = UUID.randomUUID();
            final AuroraProfile coop = h.coop(owner, "Apple");
            h.admit(owner, coop.id(), member);
            final PlayerAccount ownerBefore = h.account(owner);
            final PlayerAccount memberBefore = h.account(member);
            final AuroraProfile profileBefore = h.profile(coop.id());

            join(h.engine.setCoopLabel(owner, coop.id(), "Team Lime"));
            final AuroraProfile labelled = h.profile(coop.id());
            assertEquals("Team Lime", labelled.displayLabel());
            assertEquals("Team Lime", labelled.shownLabel());
            assertEquals("Apple", labelled.name(), "the label changed the immutable name");
            assertEquals(profileBefore.members(), labelled.members());
            assertEquals(profileBefore.revision() + 2L, labelled.revision(), "one intent: reserved once, installed once");
            assertEquals(ownerBefore, h.account(owner), "the label changed the owner's account");
            assertEquals(memberBefore, h.account(member), "the label changed a member's account");
            assertEquals("Team Lime", join(h.engine.coopDetails(coop.id())).orElseThrow().label());

            // Only the owner changes it; an invalid label is refused; null clears it.
            refusedWith(ProfileErrorCode.ROLE_FORBIDDEN, () -> join(h.engine.setCoopLabel(member, coop.id(), "Mine")));
            refusedWith(ProfileErrorCode.NAME_INVALID, () -> join(h.engine.setCoopLabel(owner, coop.id(), " padded ")));
            refusedWith(ProfileErrorCode.NAME_INVALID, () -> join(h.engine.setCoopLabel(owner, coop.id(), "x".repeat(33))));
            assertEquals("Team Lime", h.profile(coop.id()).displayLabel());
            final var unchanged = tree(store());
            join(h.engine.setCoopLabel(owner, coop.id(), "Team Lime"));
            assertEquals(unchanged, tree(store()), "setting the same label again wrote something");
            join(h.engine.setCoopLabel(owner, coop.id(), null));
            assertNull(h.profile(coop.id()).displayLabel());
            assertEquals("", join(h.engine.coopDetails(coop.id())).orElseThrow().label());
            assertEquals(memberBefore.aliases(), h.account(member).aliases());
        }
    }

    // ------------------------------------------------------------------ CoopAcceptInviteReplaySameEpoch

    @Test
    void CoopAcceptInviteReplaySameEpoch() throws Exception {
        try (CoopEngine h = engine()) {
            final UUID owner = UUID.randomUUID();
            final UUID member = UUID.randomUUID();
            final UUID other = UUID.randomUUID();
            final AuroraProfile coop = h.coop(owner, "Apple");
            final CoopInviteInfo invite = join(h.engine.invite(owner, coop.id(), member));
            assertEquals(CoopInviteState.OPEN, invite.state());
            final CoopMemberInfo first = join(h.engine.acceptInvite(member, invite.id()));
            assertEquals(CoopRole.MEMBER, first.role());
            final UUID epoch = h.profile(coop.id()).member(member).epoch();
            h.drainUntil(event -> joined(event, member));

            // A repeated accept of the committed invite returns the same membership and writes nothing.
            final var before = tree(store());
            final CoopMemberInfo again = join(h.engine.acceptInvite(member, invite.id()));
            assertEquals(first, again);
            assertEquals(epoch, h.profile(coop.id()).member(member).epoch(), "a replay allocated a new epoch");
            assertEquals(before, tree(store()), "a replayed accept wrote something");
            assertEquals(2, h.profile(coop.id()).members().size());
            // ... and fires no second join event: the next event is the marker below.
            join(h.engine.invite(owner, coop.id(), other));
            final List<org.bukkit.event.Event> taken = h.drainUntil(event -> invited(event, other, CoopInviteState.OPEN));
            assertEquals(1, taken.size(), "a replayed accept fired an event: " + taken);
            // Somebody else cannot use the invite.
            refusedWith(ProfileErrorCode.INVITE_NOT_FOUND, () -> join(h.engine.acceptInvite(other, invite.id())));
        }
    }

    // ------------------------------------------------------------------ the cap through the engine

    @Test
    void coopCapAppliesToAcceptsAndInvites() throws Exception {
        try (CoopEngine h = engine(coopSettings(true, 2, Set.of(CLASSIC)))) {
            final UUID owner = UUID.randomUUID();
            final UUID b = UUID.randomUUID();
            final UUID c = UUID.randomUUID();
            final AuroraProfile coop = h.coop(owner, "Apple");
            assertEquals(2, join(h.engine.coopDetails(coop.id())).orElseThrow().maxMembers());
            final CoopInviteInfo ib = join(h.engine.invite(owner, coop.id(), b));
            final CoopInviteInfo ic = join(h.engine.invite(owner, coop.id(), c));
            join(h.engine.acceptInvite(b, ib.id()));
            final var before = tree(store());
            refusedWith(ProfileErrorCode.COOP_FULL, () -> join(h.engine.acceptInvite(c, ic.id())));
            refusedWith(ProfileErrorCode.COOP_FULL, () -> join(h.engine.invite(owner, coop.id(), UUID.randomUUID())));
            assertEquals(before, tree(store()), "a refusal at the cap wrote something");
            assertEquals(2, h.profile(coop.id()).members().size());
            assertFalse(h.account(c).owns(coop.id()));
        }
        // The setting is bounded 2..8.
        assertThrows(IllegalArgumentException.class, () -> coopSettings(true, 9, Set.of(CLASSIC)));
        assertThrows(IllegalArgumentException.class, () -> coopSettings(true, 1, Set.of(CLASSIC)));
        assertEquals(5, ProfileSettings.DEFAULT_COOP.maxMembers());
        assertFalse(ProfileSettings.DEFAULT_COOP.enabled(), "co-op must be off by default");
    }

    // ------------------------------------------------------------------ CoopInviteExpiredAtCommit

    /**
     * The engine saw the invite open (its store-clock read is 90 s behind the FILE store's commit clock) and built an
     * accept whose deadline is the invite's expiry; the store refuses the commit past it and the engine reports
     * {@code INVITE_EXPIRED}: no membership, no access, no reservation left, no event.
     */
    @Test
    void CoopInviteExpiredAtCommit() throws Exception {
        try (CoopEngine h = engine()) {
            final UUID owner = UUID.randomUUID();
            final UUID member = UUID.randomUUID();
            final AuroraProfile coop = h.coop(owner, "Apple");
            // An invite issued six minutes ago with a five-minute lifetime (expired one minute ago), through the store.
            final ProfileInvite invite;
            final ProfileClaim p = join(h.profiles.claimTransaction(coop.id(), UUID.randomUUID())).claim();
            final PlayerClaim q = join(h.players.claim(owner, UUID.randomUUID())).claim();
            try {
                invite = CoopMembershipFixtures.invite(h.file, p, q, coop.id(), member, h.file.storeClockMillis() - 6 * MINUTE,
                    new CoopMembership.Policy(5, 5 * MINUTE, 10 * MINUTE, 35 * CoopMembershipFixtures.DAY));
            } finally {
                join(h.profiles.releaseTransaction(p));
                join(h.players.release(q));
            }
            h.clockOffset.set(-90_000L);
            final var account = h.account(member);
            final ProfileOperationException refused = refusal(() -> join(h.engine.acceptInvite(member, invite.id())));
            assertEquals(ProfileErrorCode.INVITE_EXPIRED, refused.code(), refused.getMessage());
            final AuroraProfile after = h.profile(coop.id());
            assertNull(after.member(member));
            assertNull(after.pendingIntent(), "the refused accept left its reservation on M");
            assertFalse(h.account(member).owns(coop.id()));
            assertNull(h.account(member).pendingIntent());
            assertEquals(account.profileIds(), h.account(member).profileIds());
            assertEquals(ProfileInvite.Status.OPEN, h.file.invite(invite.id()).orElseThrow().status());
            // With the clocks agreeing, the engine sees the expiry itself, closes the invite and refuses.
            h.clockOffset.set(0L);
            refusedWith(ProfileErrorCode.INVITE_EXPIRED, () -> join(h.engine.acceptInvite(member, invite.id())));
            assertEquals(ProfileInvite.Status.EXPIRED, h.file.invite(invite.id()).orElseThrow().status());
            assertTrue(join(h.engine.invitesFor(member, null)).items().isEmpty());
            // No join event was fired for any of it.
            final UUID marker = UUID.randomUUID();
            join(h.engine.invite(owner, coop.id(), marker));
            for (final org.bukkit.event.Event event : h.drainUntil(event -> invited(event, marker, CoopInviteState.OPEN))) {
                assertFalse(event instanceof CoopMemberJoinEvent, "an expired accept fired " + event.getEventName());
            }
        }
    }

    // ------------------------------------------------------------------ CoopKickElectorateChangedCancels

    @Test
    void CoopKickElectorateChangedCancels() throws Exception {
        try (CoopEngine h = engine()) {
            final UUID owner = UUID.randomUUID();
            final UUID b = UUID.randomUUID();
            final UUID c = UUID.randomUUID();
            final UUID d = UUID.randomUUID();
            final AuroraProfile coop = h.coop(owner, "Apple");
            h.admit(owner, coop.id(), b);
            h.admit(owner, coop.id(), c);

            // A leave changes the electorate {owner, b} of a vote against c: the leave cancels it in its own intent.
            final CoopKickVoteInfo vote = join(h.engine.startKickVote(owner, coop.id(), c));
            assertEquals(CoopKickVoteState.OPEN, vote.state());
            assertEquals(1, vote.votes());
            assertEquals(2, vote.electorateSize());
            assertEquals(vote.id(), join(h.engine.openKickVote(coop.id())).orElseThrow().id());
            join(h.engine.leave(b, coop.id()));
            assertEquals(KickVote.Status.CANCELLED, h.file.vote(vote.id()).orElseThrow().status());
            refusedWith(ProfileErrorCode.VOTE_NOT_FOUND, () -> join(h.engine.voteKick(owner, vote.id(), true)));
            assertNotNull(h.profile(coop.id()).member(c), "a cancelled vote removed its target");
            assertTrue(join(h.engine.openKickVote(coop.id())).isEmpty());
            assertTrue(indexed(h.file, ProfileIndexPage.Kind.VOTES_BY_PROFILE, coop.id()).isEmpty());

            // A join changes it as well: the accept cancels the undecided vote in its own intent.
            h.admit(owner, coop.id(), d);
            final CoopKickVoteInfo second = join(h.engine.startKickVote(owner, coop.id(), c));
            assertEquals(2, second.electorateSize());
            final UUID e = UUID.randomUUID();
            h.admit(owner, coop.id(), e);
            assertEquals(KickVote.Status.CANCELLED, h.file.vote(second.id()).orElseThrow().status());
            refusedWith(ProfileErrorCode.VOTE_NOT_FOUND, () -> join(h.engine.voteKick(d, second.id(), true)));
            final CoopKickVoteInfo third = join(h.engine.startKickVote(owner, coop.id(), c));
            assertEquals(3, third.electorateSize());
            // The vote passes only with the whole current electorate, then the removal runs in the same call.
            assertEquals(CoopKickVoteState.OPEN, join(h.engine.voteKick(d, third.id(), true)).state());
            final CoopKickVoteInfo executed = join(h.engine.voteKick(e, third.id(), true));
            assertEquals(CoopKickVoteState.EXECUTED, executed.state());
            assertNull(h.profile(coop.id()).member(c));
            assertFalse(h.account(c).owns(coop.id()));
            h.drainUntil(event -> left(event, c, CoopMemberLeaveEvent.Reason.KICKED));
            // A decided vote takes no more ballots.
            refusedWith(ProfileErrorCode.VOTE_NOT_FOUND, () -> join(h.engine.voteKick(owner, third.id(), true)));
        }
    }

    // ------------------------------------------------------------------ CoopOwnerKickRefused

    @Test
    void CoopOwnerKickRefused() throws Exception {
        try (CoopEngine h = engine()) {
            final UUID owner = UUID.randomUUID();
            final UUID member = UUID.randomUUID();
            final AuroraProfile coop = h.coop(owner, "Apple");
            h.admit(owner, coop.id(), member);
            h.clockOffset.set(INACTIVE_SHIFT);
            final var before = tree(store());
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.startKickVote(owner, coop.id(), owner)));
            refusedWith(ProfileErrorCode.ROLE_FORBIDDEN, () -> join(h.engine.startKickVote(member, coop.id(), owner)));
            refusedWith(ProfileErrorCode.OWNER_ACTION_REFUSED, () -> join(h.engine.leave(owner, coop.id())));
            assertEquals(before, tree(store()), "a refused owner removal wrote something");
            final AuroraProfile after = h.profile(coop.id());
            assertEquals(owner, after.accountId());
            assertEquals(ProfileRole.OWNER, after.member(owner).role());
            assertTrue(join(h.engine.openKickVote(coop.id())).isEmpty());
        }
    }

    // ------------------------------------------------------------------ CoopRemovalRejoinCannotRestoreOldEpoch

    @Test
    void CoopRemovalRejoinCannotRestoreOldEpoch() throws Exception {
        try (CoopEngine h = engine()) {
            final UUID owner = UUID.randomUUID();
            final UUID member = UUID.randomUUID();
            final AuroraProfile coop = h.coop(owner, "Apple");
            final AuroraProfile kiwi = h.create(member, "Kiwi");
            final CoopInviteInfo firstInvite = join(h.engine.invite(owner, coop.id(), member));
            join(h.engine.acceptInvite(member, firstInvite.id()));
            final UUID oldEpoch = h.profile(coop.id()).member(member).epoch();

            // The member never played the co-op, so its stream holds the UNINITIALIZED head the accept created and
            // nothing published.
            final MemberStateHead uninitialized = h.file.memberHead(coop.id(), member, oldEpoch).orElseThrow();
            assertEquals(MemberStateHead.State.UNINITIALIZED, uninitialized.state());
            final PlayerAccount staleAccount = h.account(member);
            final AuroraProfile staleProfile = h.profile(coop.id());

            join(h.engine.leave(member, coop.id()));
            final MemberStateHead consumed = h.file.memberHead(coop.id(), member, oldEpoch).orElseThrow();
            assertEquals(MemberStateHead.State.CONSUMED, consumed.state());
            assertFalse(consumed.restorable());
            final List<ProfileSalvage> salvage = CoopMembership.salvageByProfile(h.file, coop.id(), null).items();
            assertEquals(1, salvage.size());
            assertEquals(ProfileSalvage.State.UNAVAILABLE, salvage.getFirst().state());
            assertEquals(oldEpoch, salvage.getFirst().removedEpoch());
            assertEquals(ProfileSalvage.NIL, salvage.getFirst().sourceSnapshotId(), "nothing was published in the consumed stream (NIL by contract)");

            // A writer that still holds the old membership's view cannot publish into the consumed stream.
            final var before = tree(store());
            final PlayerClaim stale = join(h.players.claim(member, UUID.randomUUID())).claim();
            try {
                final CoopOperation save = op(CoopOperation.Kind.SAVE_MEMBER, member);
                final ProfileSnapshot snapshot = ProfileSnapshot.forMember(coop.id(), member, oldEpoch, uninitialized.revision() + 1L,
                    CoopFixtures.state(), save.operationId());
                final MemberStateHead next = uninitialized.published(snapshot);
                refusal(() -> h.file.publishMemberSnapshot(next, snapshot, RETAIN, CoopWriteGuard.memberSave(save, stale, coop.id(),
                    oldEpoch, staleAccount.revision(), staleProfile.revision(), uninitialized.revision())));
            } finally {
                join(h.players.release(stale));
            }
            assertEquals(before, tree(store()), "a stale write into a consumed stream changed the store");
            // The old invite cannot bring the old membership back.
            refusedWith(ProfileErrorCode.INVITE_NOT_FOUND, () -> join(h.engine.acceptInvite(member, firstInvite.id())));

            // A new invitation admits a new epoch with an empty stream; the consumed one stays consumed.
            final CoopInviteInfo again = join(h.engine.invite(owner, coop.id(), member));
            join(h.engine.acceptInvite(member, again.id()));
            final ProfileMember rejoined = h.profile(coop.id()).member(member);
            assertNotEquals(oldEpoch, rejoined.epoch());
            assertFalse(h.profile(coop.id()).hasMember(member, oldEpoch));
            assertEquals(MemberStateHead.State.UNINITIALIZED, h.file.memberHead(coop.id(), member, rejoined.epoch()).orElseThrow().state());
            assertEquals(MemberStateHead.State.CONSUMED, h.file.memberHead(coop.id(), member, oldEpoch).orElseThrow().state());
            assertNull(h.file.memberHead(coop.id(), member, rejoined.epoch()).orElseThrow().snapshotId(),
                "the new epoch's head names the old epoch's snapshot");
            // Build 53: the rejoined member plays the co-op under the new epoch only, never the consumed one.
            h.activate(member, kiwi.id());
            h.ports.online.add(member);
            final ProfileEngine.Admission admission = join(h.engine.admitSession(member));
            assertTrue(admission.admitted(), admission.reason());
            final ProfileSwitchResult switched = join(h.engine.switchProfile(member, coop.id()));
            assertTrue(switched.success(), switched.errorCode() + ": " + switched.message());
            assertEquals(coop.id(), h.account(member).activeProfile());
            assertEquals(rejoined.epoch(), h.account(member).activeEpoch());
            assertEquals(MemberStateHead.State.CONSUMED, h.file.memberHead(coop.id(), member, oldEpoch).orElseThrow().state());
        }
    }

    // ------------------------------------------------------------------ CoopRemovalFullSalvageRefused

    /**
     * Every removal writes an outstanding salvage record; with {@value ProfileSalvage#MAX_OUTSTANDING_PER_PROFILE}
     * outstanding the next removal (leave or kick) is refused {@code CAPACITY_EXCEEDED} and consumes nothing.
     */
    @Test
    void CoopRemovalFullSalvageRefused() throws Exception {
        try (CoopEngine h = engine()) {
            final UUID owner = UUID.randomUUID();
            final UUID member = UUID.randomUUID();
            final AuroraProfile coop = h.coop(owner, "Apple");
            for (int i = 0; i < ProfileSalvage.MAX_OUTSTANDING_PER_PROFILE; i++) {
                h.admit(owner, coop.id(), member);
                join(h.engine.leave(member, coop.id()));
            }
            // The outstanding salvage records, paged: never more than a page at a time, 64 in all.
            final Set<UUID> salvage = new HashSet<>();
            ProfileIndexPage.Cursor cursor = null;
            for (int pages = 0; pages < 4; pages++) {
                final CoopMembership.Page<ProfileSalvage> page = CoopMembership.salvageByProfile(h.file, coop.id(), cursor);
                assertTrue(page.items().size() <= ProfileIndexPage.LISTING_PAGE);
                for (final ProfileSalvage record : page.items()) salvage.add(record.id());
                if (page.next() == null || page.items().isEmpty()) break;
                cursor = page.next();
            }
            assertEquals(ProfileSalvage.MAX_OUTSTANDING_PER_PROFILE, salvage.size());

            h.admit(owner, coop.id(), member);
            final ProfileMember row = h.profile(coop.id()).member(member);
            final var before = tree(store());
            final ProfileOperationException leave = refusal(() -> join(h.engine.leave(member, coop.id())));
            assertEquals(ProfileErrorCode.CAPACITY_EXCEEDED, leave.code(), leave.getMessage());
            assertEquals(row, h.profile(coop.id()).member(member), "a refused removal changed the membership");
            assertTrue(h.account(member).owns(coop.id()));
            assertNotEquals(MemberStateHead.State.CONSUMED, h.file.memberHead(coop.id(), member, row.epoch()).orElseThrow().state());
            assertNull(h.profile(coop.id()).pendingIntent());
            assertNull(h.account(member).pendingIntent());
            assertEquals(before.keySet().stream().filter(path -> path.contains("salvage")).toList(),
                tree(store()).keySet().stream().filter(path -> path.contains("salvage")).toList(), "a salvage record was added");

            // A kick is refused the same way; its PASSED vote stays recorded for a retry.
            h.clockOffset.set(INACTIVE_SHIFT);
            final ProfileOperationException kick = refusal(() -> join(h.engine.startKickVote(owner, coop.id(), member)));
            assertEquals(ProfileErrorCode.CAPACITY_EXCEEDED, kick.code(), kick.getMessage());
            assertEquals(row, h.profile(coop.id()).member(member));
            final Optional<CoopKickVoteInfo> open = join(h.engine.openKickVote(coop.id()));
            assertTrue(open.isPresent());
            assertEquals(CoopKickVoteState.PASSED, open.get().state());
        }
    }

    // ------------------------------------------------------------------ conversion

    @Test
    void convertIsOneWay() throws Exception {
        try (CoopEngine h = engine()) {
            final UUID owner = UUID.randomUUID();
            final UUID other = UUID.randomUUID();
            final AuroraProfile solo = h.create(owner, "Apple");
            assertTrue(join(h.engine.coopDetails(solo.id())).isEmpty(), "a solo profile has no co-op view");
            refusedWith(ProfileErrorCode.NOT_COOP, () -> join(h.engine.invite(owner, solo.id(), other)));
            refusedWith(ProfileErrorCode.NOT_COOP, () -> join(h.engine.members(solo.id(), null)));
            refusedWith(ProfileErrorCode.NOT_OWNED, () -> join(h.engine.convertToCoop(other, solo.id())));
            refusedWith(ProfileErrorCode.PROFILE_NOT_FOUND, () -> join(h.engine.convertToCoop(owner, UUID.randomUUID())));

            final CoopDetails details = join(h.engine.convertToCoop(owner, solo.id()));
            assertEquals(solo.id(), details.profileId());
            assertEquals(List.of(new CoopMemberInfo(owner, CoopRole.OWNER, solo.member(owner).joinedAtMillis(),
                solo.member(owner).lastSeenAtMillis())), details.members());
            final AuroraProfile coop = h.profile(solo.id());
            assertTrue(coop.coop());
            assertTrue(coop.sameIdentity(solo));
            assertEquals(solo.members(), coop.members(), "the conversion changed the owner row or its epoch");
            assertEquals(h.account(owner).alias(solo.id()), "apple");
            // The record on disk is payload schema 2 now.
            try (var walk = Files.walk(store().resolve("profiles"))) {
                final Path record = walk.filter(path -> path.getFileName().toString().equals(solo.id() + ".awfp")).findFirst().orElseThrow();
                final byte[] bytes = Files.readAllBytes(record);
                assertEquals(2, bytes[4], "format byte");
                assertEquals(2, ((bytes[18] & 0xFF) << 8) | (bytes[19] & 0xFF), "payload schema of a co-op profile");
            }
            // There is no way back.
            final var before = tree(store());
            refusedWith(ProfileErrorCode.ALREADY_COOP, () -> join(h.engine.convertToCoop(owner, solo.id())));
            assertEquals(before, tree(store()));
            assertTrue(h.profile(solo.id()).coop());
        }
    }

    // ------------------------------------------------------------------ roles through the engine

    @Test
    void rolePermissionsThroughTheEngine() throws Exception {
        try (CoopEngine h = engine()) {
            final UUID owner = UUID.randomUUID();
            final UUID member = UUID.randomUUID();
            final UUID invitee = UUID.randomUUID();
            final UUID outsider = UUID.randomUUID();
            final AuroraProfile coop = h.coop(owner, "Apple");
            h.admit(owner, coop.id(), member);
            final CoopInviteInfo invite = join(h.engine.invite(owner, coop.id(), invitee));
            final var before = tree(store());
            refusedWith(ProfileErrorCode.ROLE_FORBIDDEN, () -> join(h.engine.invite(member, coop.id(), outsider)));
            refusedWith(ProfileErrorCode.ROLE_FORBIDDEN, () -> join(h.engine.cancelInvite(member, invite.id())));
            refusedWith(ProfileErrorCode.ROLE_FORBIDDEN, () -> join(h.engine.invitesOf(member, coop.id(), null)));
            refusedWith(ProfileErrorCode.ROLE_FORBIDDEN, () -> join(h.engine.setCoopLabel(member, coop.id(), "Mine")));
            refusedWith(ProfileErrorCode.ROLE_FORBIDDEN, () -> join(h.engine.startKickVote(member, coop.id(), owner)));
            refusedWith(ProfileErrorCode.NOT_A_MEMBER, () -> join(h.engine.invitesOf(outsider, coop.id(), null)));
            refusedWith(ProfileErrorCode.NOT_A_MEMBER, () -> join(h.engine.cancelInvite(outsider, invite.id())));
            refusedWith(ProfileErrorCode.NOT_A_MEMBER, () -> join(h.engine.leave(outsider, coop.id())));
            refusedWith(ProfileErrorCode.INVITE_NOT_FOUND, () -> join(h.engine.declineInvite(owner, invite.id())));
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.invite(owner, coop.id(), owner)));
            refusedWith(ProfileErrorCode.ALREADY_MEMBER, () -> join(h.engine.invite(owner, coop.id(), member)));
            refusedWith(ProfileErrorCode.INVITE_EXISTS, () -> join(h.engine.invite(owner, coop.id(), invitee)));
            assertEquals(before, tree(store()), "a refused role check wrote something");
            // The operator's administrative listing has no role check; the owner's own does.
            assertEquals(List.of(invite.id()), join(h.engine.invitesOf(null, coop.id(), null)).items().stream()
                .map(CoopInviteInfo::id).toList());
            assertEquals(List.of(invite.id()), join(h.engine.invitesOf(owner, coop.id(), null)).items().stream()
                .map(CoopInviteInfo::id).toList());
            // The invitee declines; the owner cancels a second one.
            join(h.engine.declineInvite(invitee, invite.id()));
            assertEquals(ProfileInvite.Status.DECLINED, h.file.invite(invite.id()).orElseThrow().status());
            final CoopInviteInfo second = join(h.engine.invite(owner, coop.id(), invitee));
            join(h.engine.cancelInvite(owner, second.id()));
            assertEquals(ProfileInvite.Status.REVOKED, h.file.invite(second.id()).orElseThrow().status());
            refusedWith(ProfileErrorCode.INVITE_NOT_FOUND, () -> join(h.engine.acceptInvite(invitee, second.id())));
            assertTrue(join(h.engine.invitesFor(invitee, null)).items().isEmpty());
        }
    }

    // ------------------------------------------------------------------ MEMBER_ONLINE only when the member cannot be moved

    /**
     * Build 53: a member whose active profile is another one is removed as in Build 52; a member online here with the
     * co-op active but no admitted session cannot be switched out safely and is refused {@code MEMBER_ONLINE} with
     * nothing written (the other {@code MEMBER_ONLINE} cases and the switch-out and repoint flows are in
     * {@link CoopMemberPlayTest}).
     */
    @Test
    void memberOnlineOnlyWhenTheCoopIsActive() throws Exception {
        try (CoopEngine h = engine()) {
            final UUID owner = UUID.randomUUID();
            final UUID b = UUID.randomUUID();
            final UUID c = UUID.randomUUID();
            final AuroraProfile coop = h.coop(owner, "Apple");
            final AuroraProfile ownB = h.create(b, "Kiwi");
            final AuroraProfile ownC = h.create(c, "Lime");
            h.admit(owner, coop.id(), b);
            h.admit(owner, coop.id(), c);
            h.ports.online.add(b);
            h.ports.online.add(c);

            // b online here with the co-op active (the store lets a member make it active since Build 53) but no
            // admitted session: b cannot be captured and switched out first, so the leave is refused.
            h.activate(b, coop.id());
            final var before = tree(store());
            refusedWith(ProfileErrorCode.MEMBER_ONLINE, () -> join(h.engine.leave(b, coop.id())));
            assertEquals(before, tree(store()), "a MEMBER_ONLINE refusal wrote something");
            assertNotNull(h.profile(coop.id()).member(b));
            assertEquals(coop.id(), h.account(b).activeProfile());

            // b online in its own profile: the leave runs.
            h.activate(b, ownB.id());
            join(h.engine.leave(b, coop.id()));
            assertNull(h.profile(coop.id()).member(b));
            assertEquals(ownB.id(), h.account(b).activeProfile(), "the leave changed the active profile of an online player");

            // c online in its own profile: a kick runs (two-member co-op now, target inactive by the shifted store clock).
            h.activate(c, ownC.id());
            h.clockOffset.set(INACTIVE_SHIFT);
            final CoopKickVoteInfo kicked = join(h.engine.startKickVote(owner, coop.id(), c));
            assertEquals(CoopKickVoteState.EXECUTED, kicked.state());
            assertNull(h.profile(coop.id()).member(c));
            assertEquals(ownC.id(), h.account(c).activeProfile());
        }
    }

    // ------------------------------------------------------------------ COOP_DISABLED, DISABLED, MIGRATION_REQUIRED

    @Test
    void coopDisabledRefusesEveryCoopOperation() throws Exception {
        final UUID owner = UUID.randomUUID();
        final UUID other = UUID.randomUUID();
        final UUID profile;
        try (CoopEngine h = engine()) {
            profile = h.coop(owner, "Apple").id();
        }
        final byte[] marker = Files.readAllBytes(store().resolve(StoreFormat.FILE_NAME));
        try (CoopEngine h = engine(coopSettings(false, 5, Set.of(CLASSIC)))) {
            final var before = tree(store());
            final UUID id = UUID.randomUUID();
            refusedWith(ProfileErrorCode.COOP_DISABLED, () -> join(h.engine.convertToCoop(owner, profile)));
            refusedWith(ProfileErrorCode.COOP_DISABLED, () -> join(h.engine.coopDetails(profile)));
            refusedWith(ProfileErrorCode.COOP_DISABLED, () -> join(h.engine.setCoopLabel(owner, profile, "Team")));
            refusedWith(ProfileErrorCode.COOP_DISABLED, () -> join(h.engine.invite(owner, profile, other)));
            refusedWith(ProfileErrorCode.COOP_DISABLED, () -> join(h.engine.acceptInvite(other, id)));
            refusedWith(ProfileErrorCode.COOP_DISABLED, () -> join(h.engine.declineInvite(other, id)));
            refusedWith(ProfileErrorCode.COOP_DISABLED, () -> join(h.engine.cancelInvite(owner, id)));
            refusedWith(ProfileErrorCode.COOP_DISABLED, () -> join(h.engine.invitesFor(other, null)));
            refusedWith(ProfileErrorCode.COOP_DISABLED, () -> join(h.engine.invitesOf(owner, profile, null)));
            refusedWith(ProfileErrorCode.COOP_DISABLED, () -> join(h.engine.leave(other, profile)));
            refusedWith(ProfileErrorCode.COOP_DISABLED, () -> join(h.engine.startKickVote(owner, profile, other)));
            refusedWith(ProfileErrorCode.COOP_DISABLED, () -> join(h.engine.voteKick(owner, id, true)));
            refusedWith(ProfileErrorCode.COOP_DISABLED, () -> join(h.engine.openKickVote(profile)));
            refusedWith(ProfileErrorCode.COOP_DISABLED, () -> join(h.engine.members(profile, null)));
            assertEquals(before, tree(store()), "a co-op operation wrote while co-op is disabled");
            // A store that already holds co-op records stays readable.
            assertEquals(List.of(profile), join(h.engine.list(owner)).stream().map(dev.iyanz.sourbycraft.api.profile.ProfileInfo::id)
                .toList());
            assertTrue(join(h.engine.getDetails(profile)).isPresent());
            assertTrue(h.events.isEmpty(), "an event fired while co-op is disabled");
        }
        assertArrayEquals(marker, Files.readAllBytes(store().resolve(StoreFormat.FILE_NAME)));

        // MultiProfile itself off: DISABLED, whatever coop.enabled says.
        final ProfileSettings on = coopSettings();
        final ProfileSettings off = new ProfileSettings(false, on.mode(), on.defaultSlots(), on.maxSlots(), on.switching(), on.isolation(),
            on.worlds(), on.coordination(), on.directory(), on.repository(), on.types(), on.names(), on.migration(), on.coop());
        assertFalse(off.coopEnabled());
        try (CoopEngine h = new CoopEngine(this.directory.resolve("off"), off, true)) {
            refusedWith(ProfileErrorCode.DISABLED, () -> join(h.engine.convertToCoop(owner, profile)));
            refusedWith(ProfileErrorCode.DISABLED, () -> join(h.engine.invite(owner, profile, other)));
        }
    }

    @Test
    void migrationRequiredOnAFormat1Store() throws Exception {
        try (CoopEngine h = new CoopEngine(store(), coopSettings(), false)) {
            assertEquals(StoreFormat.Gate.LEGACY, h.open().gate());
            final UUID owner = UUID.randomUUID();
            final UUID id = UUID.randomUUID();
            final var before = tree(store());
            refusedWith(ProfileErrorCode.MIGRATION_REQUIRED, () -> join(h.engine.convertToCoop(owner, id)));
            refusedWith(ProfileErrorCode.MIGRATION_REQUIRED, () -> join(h.engine.invite(owner, id, UUID.randomUUID())));
            refusedWith(ProfileErrorCode.MIGRATION_REQUIRED, () -> join(h.engine.acceptInvite(owner, id)));
            refusedWith(ProfileErrorCode.MIGRATION_REQUIRED, () -> join(h.engine.leave(owner, id)));
            refusedWith(ProfileErrorCode.MIGRATION_REQUIRED, () -> join(h.engine.startKickVote(owner, id, UUID.randomUUID())));
            refusedWith(ProfileErrorCode.MIGRATION_REQUIRED, () -> join(h.engine.coopDetails(id)));
            refusedWith(ProfileErrorCode.MIGRATION_REQUIRED, () -> join(h.engine.members(id, null)));
            refusedWith(ProfileErrorCode.MIGRATION_REQUIRED, () -> join(h.engine.invitesFor(owner, null)));
            assertEquals(before, tree(store()), "a co-op operation wrote to a format-1 store");
            assertFalse(Files.exists(store().resolve(StoreFormat.FILE_NAME)), "a format-1 store got a marker");
        }
    }

    @Test
    void typeNotAllowedRefusesConversion() throws Exception {
        try (CoopEngine h = engine(coopSettings(true, 5, Set.of(CLASSIC)))) {
            final UUID owner = UUID.randomUUID();
            final AuroraProfile ironman = h.create(owner, "Apple", IRONMAN);
            final byte[] marker = Files.readAllBytes(store().resolve(StoreFormat.FILE_NAME));
            final var before = tree(store());
            refusedWith(ProfileErrorCode.TYPE_NOT_ALLOWED, () -> join(h.engine.convertToCoop(owner, ironman.id())));
            assertEquals(before, tree(store()));
            assertArrayEquals(marker, Files.readAllBytes(store().resolve(StoreFormat.FILE_NAME)), "a refused conversion raised the marker");
            assertFalse(h.profile(ironman.id()).coop());
            // The classic one converts.
            final AuroraProfile classic = h.create(owner, "Kiwi");
            join(h.engine.convertToCoop(owner, classic.id()));
            assertTrue(h.profile(classic.id()).coop());
        }
        try (CoopEngine h = new CoopEngine(this.directory.resolve("ironman"), coopSettings(true, 5, EnumSet.of(CLASSIC, IRONMAN)), true)) {
            h.open();
            final UUID owner = UUID.randomUUID();
            final AuroraProfile ironman = h.create(owner, "Apple", IRONMAN);
            join(h.engine.convertToCoop(owner, ironman.id()));
            assertTrue(h.profile(ironman.id()).coop());
            assertEquals(IRONMAN, h.profile(ironman.id()).profileType(), "the conversion changed the policy type");
        }
    }

    // ------------------------------------------------------------------ COOP_SWITCH_UNAVAILABLE and OD-1

    /**
     * Build 53: a member can switch into a co-op only with {@code login.authoritative}; with it false the member is
     * refused {@code COOP_SWITCH_UNAVAILABLE}, and a non-member is {@code NOT_A_MEMBER} either way. Nothing is written.
     */
    @Test
    void nonOwnerSwitchIntoCoopUnavailable() throws Exception {
        try (CoopEngine h = engine(MemberPlayFixtures.playSettings(false, 15_000, Set.of()))) {
            final UUID owner = UUID.randomUUID();
            final UUID member = UUID.randomUUID();
            final UUID outsider = UUID.randomUUID();
            final AuroraProfile coop = h.coop(owner, "Apple");
            h.admit(owner, coop.id(), member);
            final var before = tree(store());
            final ProfileSwitchResult byMember = join(h.engine.switchProfile(member, coop.id()));
            assertFalse(byMember.success());
            assertEquals(ProfileErrorCode.COOP_SWITCH_UNAVAILABLE, byMember.errorCode(), byMember.message());
            final ProfileSwitchResult byOutsider = join(h.engine.switchProfile(outsider, coop.id()));
            assertEquals(ProfileErrorCode.NOT_A_MEMBER, byOutsider.errorCode(), byOutsider.message());
            assertEquals(before, tree(store()), "a refused switch wrote something");
            assertNull(h.account(member).activeProfile());
        }
    }

    @Test
    void wrongNodeRefusesMembershipChanges() throws Exception {
        try (CoopEngine h = engine()) {
            final UUID owner = UUID.randomUUID();
            final UUID member = UUID.randomUUID();
            final UUID invitee = UUID.randomUUID();
            final AuroraProfile solo = h.create(owner, "Kiwi");
            final AuroraProfile coop = h.coop(owner, "Apple");
            h.admit(owner, coop.id(), member);
            final CoopInviteInfo invite = join(h.engine.invite(owner, coop.id(), invitee));
            // Another node holds the profiles (their bound world is played there).
            final ClaimResult held = join(h.profiles.claim(coop.id(), "node-b", UUID.randomUUID()));
            final ClaimResult heldSolo = join(h.profiles.claim(solo.id(), "node-b", UUID.randomUUID()));
            assertTrue(held.granted() && heldSolo.granted());
            final var before = tree(store());
            refusedWith(ProfileErrorCode.WRONG_NODE, () -> join(h.engine.convertToCoop(owner, solo.id())));
            refusedWith(ProfileErrorCode.WRONG_NODE, () -> join(h.engine.invite(owner, coop.id(), UUID.randomUUID())));
            refusedWith(ProfileErrorCode.WRONG_NODE, () -> join(h.engine.acceptInvite(invitee, invite.id())));
            refusedWith(ProfileErrorCode.WRONG_NODE, () -> join(h.engine.leave(member, coop.id())));
            refusedWith(ProfileErrorCode.WRONG_NODE, () -> join(h.engine.startKickVote(owner, coop.id(), member)));
            refusedWith(ProfileErrorCode.WRONG_NODE, () -> join(h.engine.setCoopLabel(owner, coop.id(), "Team")));
            assertEquals(before, tree(store()), "a WRONG_NODE refusal wrote something");
            // Once nobody else holds it, the same accept runs here.
            assertTrue(join(h.profiles.release(held.claim())));
            join(h.engine.acceptInvite(invitee, invite.id()));
            assertNotNull(h.profile(coop.id()).member(invitee));
            assertTrue(join(h.profiles.release(heldSolo.claim())));
        }
    }

    // ------------------------------------------------------------------ §9 caps and paged listings

    @Test
    void capsAndPagedListingsBounded() throws Exception {
        try (CoopEngine h = engine()) {
            final UUID owner = UUID.randomUUID();
            final AuroraProfile coop = h.coop(owner, "Apple");
            final List<UUID> invitees = new ArrayList<>();
            for (int i = 0; i < ProfileInvite.MAX_OPEN; i++) {
                final UUID invitee = UUID.randomUUID();
                invitees.add(invitee);
                join(h.engine.invite(owner, coop.id(), invitee));
            }
            // The 65th open invite of the profile is refused by the store's bound; nothing is written.
            final var before = tree(store());
            final ProfileOperationException full = refusal(() -> join(h.engine.invite(owner, coop.id(), UUID.randomUUID())));
            assertEquals(ProfileErrorCode.CAPACITY_EXCEEDED, full.code(), full.getMessage());
            assertEquals(before, tree(store()), "a refused invite past the bound wrote something");

            // Paged: every page at most 32, the cursor walks all 64 exactly once and ends.
            final Set<UUID> seen = new HashSet<>();
            String cursor = null;
            int pages = 0;
            do {
                final CoopPage<CoopInviteInfo> page = join(h.engine.invitesOf(owner, coop.id(), cursor));
                assertTrue(page.items().size() <= CoopPage.MAX_PAGE_SIZE);
                for (final CoopInviteInfo info : page.items()) assertTrue(seen.add(info.id()), "listed twice: " + info.id());
                cursor = page.nextCursor();
                pages++;
            } while (cursor != null && pages < 10);
            assertNull(cursor, "the listing did not end");
            assertTrue(pages <= 3, pages + " pages for 64 invites");
            assertEquals(ProfileInvite.MAX_OPEN, seen.size());
            final String profileCursor = join(h.engine.invitesOf(owner, coop.id(), null)).nextCursor();
            assertNotNull(profileCursor);
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.invitesFor(invitees.getFirst(), profileCursor)));
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.invitesOf(owner, coop.id(), "not-a-cursor")));
            assertEquals(1, join(h.engine.invitesFor(invitees.getFirst(), null)).items().size());

            // Members: one page here (at most 5), no cursor; a foreign or malformed cursor is refused.
            for (final UUID invitee : invitees.subList(0, 2)) {
                join(h.engine.acceptInvite(invitee, join(h.engine.invitesFor(invitee, null)).items().getFirst().id()));
            }
            final CoopPage<CoopMemberInfo> members = join(h.engine.members(coop.id(), null));
            assertEquals(List.of(owner, invitees.get(0), invitees.get(1)), members.items().stream().map(CoopMemberInfo::playerId).toList());
            assertNull(members.nextCursor());
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.members(coop.id(), "members:" + UUID.randomUUID() + ":1")));
            refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> join(h.engine.members(coop.id(), "members:" + coop.id() + ":x")));

            // One undecided kick vote per profile.
            final CoopKickVoteInfo vote = join(h.engine.startKickVote(owner, coop.id(), invitees.get(1)));
            assertEquals(CoopKickVoteState.OPEN, vote.state());
            refusedWith(ProfileErrorCode.VOTE_OPEN, () -> join(h.engine.startKickVote(owner, coop.id(), invitees.get(0))));
            assertEquals(vote.id(), join(h.engine.openKickVote(coop.id())).orElseThrow().id());
        }
    }

    // ------------------------------------------------------------------ events after commit only

    @Test
    void eventsFireOnlyAfterCommit() throws Exception {
        try (CoopEngine h = engine()) {
            final UUID owner = UUID.randomUUID();
            final UUID b = UUID.randomUUID();
            final UUID c = UUID.randomUUID();
            final UUID d = UUID.randomUUID();
            final AuroraProfile coop = h.coop(owner, "Apple");
            // Each event checks, on the event thread, that what it announces is already in the store.
            final List<String> early = new CopyOnWriteArrayList<>();
            final List<String> threads = new CopyOnWriteArrayList<>();
            h.engine.onCoopEvent(event -> {
                threads.add(Thread.currentThread().getName());
                try {
                    if (event instanceof CoopMemberJoinEvent joinEvent) {
                        if (h.file.profile(joinEvent.getProfileId()).orElseThrow().member(joinEvent.getPlayerId()) == null) {
                            early.add("join of " + joinEvent.getPlayerId() + " before its commit");
                        }
                    } else if (event instanceof CoopMemberLeaveEvent leaveEvent) {
                        if (h.file.profile(leaveEvent.getProfileId()).orElseThrow().member(leaveEvent.getPlayerId()) != null) {
                            early.add("leave of " + leaveEvent.getPlayerId() + " before its commit");
                        }
                    } else if (event instanceof CoopInviteEvent inviteEvent) {
                        final ProfileInvite stored = h.file.invite(inviteEvent.getInvite().id()).orElse(null);
                        final CoopInviteState state = inviteEvent.getInvite().state();
                        final boolean matches = stored != null && switch (state) {
                            case OPEN -> stored.status() == ProfileInvite.Status.OPEN;
                            case DECLINED -> stored.status() == ProfileInvite.Status.DECLINED;
                            case CANCELLED -> stored.status() == ProfileInvite.Status.REVOKED;
                            default -> false;
                        };
                        if (!matches) early.add("invite event " + state + " before its commit (" + stored + ")");
                    }
                } catch (final java.io.IOException failed) {
                    early.add("unreadable: " + failed);
                }
                h.events.add(event);
            });

            final CoopInviteInfo ib = join(h.engine.invite(owner, coop.id(), b));
            h.drainUntil(event -> invited(event, b, CoopInviteState.OPEN));
            join(h.engine.acceptInvite(b, ib.id()));
            final CoopMemberJoinEvent joinEvent = (CoopMemberJoinEvent) h.drainUntil(event -> joined(event, b)).getLast();
            assertEquals(coop.id(), joinEvent.getProfileId());
            assertEquals(CoopRole.MEMBER, joinEvent.getRole());
            assertEquals(ib.id(), joinEvent.getInviteId());
            assertTrue(joinEvent.isAsynchronous(), "co-op events are asynchronous");

            final CoopInviteInfo ic = join(h.engine.invite(owner, coop.id(), c));
            h.drainUntil(event -> invited(event, c, CoopInviteState.OPEN));
            join(h.engine.declineInvite(c, ic.id()));
            h.drainUntil(event -> invited(event, c, CoopInviteState.DECLINED));
            final CoopInviteInfo ic2 = join(h.engine.invite(owner, coop.id(), c));
            h.drainUntil(event -> invited(event, c, CoopInviteState.OPEN));
            join(h.engine.cancelInvite(owner, ic2.id()));
            h.drainUntil(event -> invited(event, c, CoopInviteState.CANCELLED));

            // Refusals fire nothing: a WRONG_NODE accept, a ROLE_FORBIDDEN invite, a non-member's switch into the co-op.
            final CoopInviteInfo id = join(h.engine.invite(owner, coop.id(), d));
            h.drainUntil(event -> invited(event, d, CoopInviteState.OPEN));
            final ClaimResult held = join(h.profiles.claim(coop.id(), "node-b", UUID.randomUUID()));
            refusedWith(ProfileErrorCode.WRONG_NODE, () -> join(h.engine.acceptInvite(d, id.id())));
            assertTrue(join(h.profiles.release(held.claim())));
            refusedWith(ProfileErrorCode.ROLE_FORBIDDEN, () -> join(h.engine.invite(b, coop.id(), UUID.randomUUID())));
            assertEquals(ProfileErrorCode.NOT_A_MEMBER, join(h.engine.switchProfile(UUID.randomUUID(), coop.id())).errorCode());

            // An UNKNOWN outcome fires nothing and is not treated as success: the prepare reply is lost before the store
            // saw the request; the accept is reported UNKNOWN_OUTCOME, and d stays without membership.
            h.prepareReply.set(intent -> intent.kind() == CoopOperation.Kind.INVITE_ACCEPT
                ? CommitResult.unknown(intent.operationId(), "simulated lost prepare reply") : null);
            refusedWith(ProfileErrorCode.UNKNOWN_OUTCOME, () -> join(h.engine.acceptInvite(d, id.id())));
            h.prepareReply.set(null);
            assertNull(h.profile(coop.id()).member(d));
            assertFalse(h.account(d).owns(coop.id()));
            // The unresolved operation keeps d's profile changes refused rather than guessing.
            refusedWith(ProfileErrorCode.RECOVERY_REQUIRED, () -> join(h.engine.acceptInvite(d, id.id())));

            // The marker: the next event delivered is the one of the next committed operation.
            final UUID marker = UUID.randomUUID();
            join(h.engine.invite(owner, coop.id(), marker));
            final List<org.bukkit.event.Event> taken = h.drainUntil(event -> invited(event, marker, CoopInviteState.OPEN));
            assertEquals(1, taken.size(), "a refused or unknown operation fired an event: " + taken);

            // Leave and kick events follow their commits.
            h.activate(b, h.create(b, "Kiwi").id());
            join(h.engine.leave(b, coop.id()));
            final CoopMemberLeaveEvent leftEvent = (CoopMemberLeaveEvent) h.drainUntil(
                event -> left(event, b, CoopMemberLeaveEvent.Reason.LEFT)).getLast();
            assertNull(leftEvent.getVoteId());
            final UUID e = UUID.randomUUID();
            h.admit(owner, coop.id(), e);
            h.clockOffset.set(INACTIVE_SHIFT);
            final CoopKickVoteInfo kick = join(h.engine.startKickVote(owner, coop.id(), e));
            final CoopMemberLeaveEvent kickedEvent = (CoopMemberLeaveEvent) h.drainUntil(
                event -> left(event, e, CoopMemberLeaveEvent.Reason.KICKED)).getLast();
            assertEquals(kick.id(), kickedEvent.getVoteId());

            assertEquals(List.of(), early, "an event ran before the store showed its commit");
            for (final String thread : threads) assertEquals("SourbyCraft-AWF-coop-events", thread);
        }
    }

    // ------------------------------------------------------------------ store feature level and the OD-2 gate

    @SuppressWarnings("unchecked")
    private static Optional<String> downgradeRefusal(final String target, final Path serverDir) throws Exception {
        final Method refuse = StoreDowngradeGuard.class.getDeclaredMethod("refuse", BuildVersion.class, Path.class);
        refuse.setAccessible(true);
        return (Optional<String>) refuse.invoke(null, BuildVersion.parse(target), serverDir);
    }

    /**
     * The first co-op commit raises the store marker to schema 2 and {@code minimumBuild} 52 (durably, before the
     * record), never lowers it, and a store without co-op records keeps the Build 51 marker byte for byte. The
     * auto-updater's downgrade gate then refuses Build 51 and allows Build 52 and newer.
     */
    @Test
    void storeMarkerRaisedOnFirstCoopCommit() throws Exception {
        final Path serverDir = this.directory.resolve("server");
        final Path root = serverDir.resolve(ProfileSettings.DIRECTORY_DEFAULT);
        try (CoopEngine h = new CoopEngine(root, coopSettings(), true)) {
            h.open();
            h.engine.storeWriter("Build 52 test");
            final byte[] build51 = Files.readAllBytes(root.resolve(StoreFormat.FILE_NAME));
            assertEquals(StoreFormat.SCHEMA, StoreFormat.decode(build51, "m").schema());
            assertEquals(0, StoreFormat.decode(build51, "m").minimumBuild());
            assertTrue(downgradeRefusal("51", serverDir).isEmpty(), "a Build 51 store refused Build 51");
            assertTrue(downgradeRefusal("50", serverDir).orElseThrow().startsWith(StoreDowngradeGuard.REASON_TAG));

            // Solo operations and refused co-op operations leave the marker exactly as Build 51 wrote it.
            final UUID owner = UUID.randomUUID();
            final AuroraProfile solo = h.create(owner, "Apple");
            final AuroraProfile ironman = h.create(owner, "Kiwi", IRONMAN);
            refusedWith(ProfileErrorCode.TYPE_NOT_ALLOWED, () -> join(h.engine.convertToCoop(owner, ironman.id())));
            refusedWith(ProfileErrorCode.NOT_OWNED, () -> join(h.engine.convertToCoop(UUID.randomUUID(), solo.id())));
            assertTrue(join(h.engine.delete(ironman.id())));
            assertArrayEquals(build51, Files.readAllBytes(root.resolve(StoreFormat.FILE_NAME)), "a solo-only store changed its marker");

            // The first co-op commit raises it.
            join(h.engine.convertToCoop(owner, solo.id()));
            final byte[] raisedBytes = Files.readAllBytes(root.resolve(StoreFormat.FILE_NAME));
            final StoreFormat.Marker raised = StoreFormat.decode(raisedBytes, "m");
            assertEquals(StoreFormat.COOP_SCHEMA, raised.schema());
            assertEquals(StoreFormat.COOP_MINIMUM_BUILD, raised.minimumBuild());
            assertEquals(StoreFormat.COOP_MINIMUM_BUILD, raised.requiredBuild());
            assertEquals("Build 52 test", raised.writtenBy());
            assertEquals(StoreFormat.MigrationState.READY, raised.migration());
            assertEquals(build51.length + 4 + ("Build 52 test".length() - StoreFormat.decode(build51, "m").writtenBy().length()),
                raisedBytes.length, "the raised marker carries the 4-byte minimumBuild trailer");

            // Later co-op commits do not rewrite it.
            final UUID member = UUID.randomUUID();
            h.admit(owner, solo.id(), member);
            assertArrayEquals(raisedBytes, Files.readAllBytes(root.resolve(StoreFormat.FILE_NAME)));

            // OD-2: Build 51 (and anything older or unreadable) is refused, 52 and newer pass.
            final Optional<String> refused51 = downgradeRefusal("51", serverDir);
            assertTrue(refused51.isPresent());
            assertTrue(refused51.get().startsWith(StoreDowngradeGuard.REASON_TAG), refused51.get());
            assertTrue(refused51.get().contains("52"), refused51.get());
            assertTrue(downgradeRefusal("50", serverDir).isPresent());
            assertTrue(downgradeRefusal("", serverDir).isPresent(), "an unreadable target build passed");
            assertTrue(downgradeRefusal("52", serverDir).isEmpty());
            assertTrue(downgradeRefusal("52.1", serverDir).isEmpty());
            assertTrue(downgradeRefusal("53", serverDir).isEmpty());
        }
        // The raised store is opened again by this build (it reads up to Build 52).
        try (CoopEngine h = new CoopEngine(root, coopSettings(), true)) {
            assertEquals(StoreFormat.Gate.FORMAT_2, h.open().gate());
            assertEquals(StoreFormat.COOP_MINIMUM_BUILD, h.file.storeFormat().requiredBuild());
        }
    }
}
