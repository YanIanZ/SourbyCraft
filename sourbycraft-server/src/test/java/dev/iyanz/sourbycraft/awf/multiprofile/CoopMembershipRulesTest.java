package dev.iyanz.sourbycraft.awf.multiprofile;

import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.CLASSIC;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.SLOTS;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.op;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopFixtures.refusedWith;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.DAY;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.MINUTE;
import static dev.iyanz.sourbycraft.awf.multiprofile.CoopMembershipFixtures.POLICY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.Expected;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileRole.Permission;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The Build 52 membership rules as the {@link CoopMembership} builders decide them, without a store: the role
 * permission matrix, the one-way conversion, the owner protections, the co-op cap, the invite and vote rules and
 * the two-member inactivity rule. The builders write nothing; each refusal here is a {@code ProfileOperationException}
 * with the Build 52 code. Store-level enforcement of the same rules is covered by the repository and engine cases.
 */
class CoopMembershipRulesTest {

    private static final long NOW = 10 * DAY;

    private final UUID owner = UUID.randomUUID();
    private final UUID manager = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final UUID outsider = UUID.randomUUID();

    /** A co-op of {@link #owner} (revision 1 after the conversion) with a MANAGER and a MEMBER row admitted at {@link #NOW}. */
    private AuroraProfile coop() {
        return AuroraProfile.create(this.owner, "skyblock", CLASSIC, "Apple", Map.of(), NOW).convertedToCoop()
            .withMember(ProfileMember.admitted(this.manager, ProfileRole.MANAGER, NOW))
            .withMember(ProfileMember.admitted(this.member, ProfileRole.MEMBER, NOW));
    }

    private static PlayerAccount account(final UUID player) {
        return PlayerAccount.empty(player, SLOTS);
    }

    // ------------------------------------------------------------------ role permission matrix

    @Test
    void rolePermissionMatrix() throws Exception {
        // The expected matrix, written out (not derived from the code under test).
        final Map<ProfileRole, Set<Permission>> expected = new EnumMap<>(ProfileRole.class);
        expected.put(ProfileRole.OWNER, EnumSet.complementOf(EnumSet.of(Permission.LEAVE)));
        expected.put(ProfileRole.MANAGER, EnumSet.of(Permission.INVITE, Permission.CANCEL_INVITE, Permission.ACCEPT_REQUEST,
            Permission.START_KICK_VOTE, Permission.CAST_KICK_VOTE, Permission.MANAGE_BANK, Permission.LEAVE));
        expected.put(ProfileRole.MEMBER, EnumSet.of(Permission.CAST_KICK_VOTE, Permission.LEAVE));
        for (final ProfileRole role : ProfileRole.values()) {
            for (final Permission permission : Permission.values()) {
                assertEquals(expected.get(role).contains(permission), role.allows(permission), role + " / " + permission);
            }
        }
        assertTrue(ProfileRole.MANAGER.canInvite() && ProfileRole.MANAGER.canVote() && ProfileRole.MANAGER.canLeave());
        assertTrue(!ProfileRole.MANAGER.canChangeLabel() && !ProfileRole.MANAGER.canChangeRoles() && !ProfileRole.MANAGER.canArchive());
        assertTrue(!ProfileRole.OWNER.canLeave() && ProfileRole.OWNER.canChangeLabel() && ProfileRole.OWNER.canTransferOwnership());
        assertTrue(!ProfileRole.MEMBER.canInvite() && !ProfileRole.MEMBER.canVote() && ProfileRole.MEMBER.canLeave());

        // The builders apply it against the member rows of the M they compare.
        final AuroraProfile coop = coop();
        CoopMembership.createInvite(op(CoopOperation.Kind.INVITE_CREATE, this.owner), coop, account(this.owner), this.outsider,
            account(this.outsider), List.of(), POLICY, NOW, 0L);
        CoopMembership.createInvite(op(CoopOperation.Kind.INVITE_CREATE, this.manager), coop, account(this.manager), this.outsider,
            account(this.outsider), List.of(), POLICY, NOW, 0L);
        refusedWith(ProfileErrorCode.ROLE_FORBIDDEN, () -> CoopMembership.createInvite(op(CoopOperation.Kind.INVITE_CREATE, this.member),
            coop, account(this.member), this.outsider, account(this.outsider), List.of(), POLICY, NOW, 0L));
        refusedWith(ProfileErrorCode.NOT_A_MEMBER, () -> CoopMembership.createInvite(op(CoopOperation.Kind.INVITE_CREATE, this.outsider),
            coop, account(this.outsider), UUID.randomUUID(), null, List.of(), POLICY, NOW, 0L));

        CoopMembership.renameLabel(op(CoopOperation.Kind.RENAME_DISPLAY, this.owner), coop, account(this.owner), "Team", 0L);
        refusedWith(ProfileErrorCode.ROLE_FORBIDDEN, () -> CoopMembership.renameLabel(op(CoopOperation.Kind.RENAME_DISPLAY, this.manager),
            coop, account(this.manager), "Team", 0L));
        refusedWith(ProfileErrorCode.ROLE_FORBIDDEN, () -> CoopMembership.renameLabel(op(CoopOperation.Kind.RENAME_DISPLAY, this.member),
            coop, account(this.member), "Team", 0L));

        CoopMembership.createVote(op(CoopOperation.Kind.VOTE_CREATE, this.manager), coop, account(this.manager), this.member, List.of(),
            POLICY, NOW, 0L);
        refusedWith(ProfileErrorCode.ROLE_FORBIDDEN, () -> CoopMembership.createVote(op(CoopOperation.Kind.VOTE_CREATE, this.member),
            coop, account(this.member), this.manager, List.of(), POLICY, NOW, 0L));

        // Leave: MANAGER and MEMBER may, the OWNER may not.
        CoopMembership.leave(op(CoopOperation.Kind.MEMBER_REMOVE, this.manager), coop, account(this.manager), null, List.of(), List.of(), NOW, 0L);
        CoopMembership.leave(op(CoopOperation.Kind.MEMBER_REMOVE, this.member), coop, account(this.member), null, List.of(), List.of(), NOW, 0L);
        refusedWith(ProfileErrorCode.OWNER_ACTION_REFUSED, () -> CoopMembership.leave(op(CoopOperation.Kind.MEMBER_REMOVE, this.owner),
            coop, account(this.owner), null, List.of(), List.of(), NOW, 0L));
        refusedWith(ProfileErrorCode.NOT_A_MEMBER, () -> CoopMembership.leave(op(CoopOperation.Kind.MEMBER_REMOVE, this.outsider),
            coop, account(this.outsider), null, List.of(), List.of(), NOW, 0L));

        // Revoke: the inviter, the OWNER (any invitation), a MANAGER only its own; a MEMBER that did not issue the invite may not.
        final ProfileInvite issued = ProfileInvite.open(coop.id(), coop.member(this.manager), this.outsider, NOW, 5 * MINUTE,
            UUID.randomUUID());
        CoopMembership.closeInvite(op(CoopOperation.Kind.INVITE_REVOKE, this.manager), coop, issued, account(this.manager),
            ProfileInvite.Status.REVOKED, NOW, 0L);
        CoopMembership.closeInvite(op(CoopOperation.Kind.INVITE_REVOKE, this.owner), coop, issued, account(this.owner),
            ProfileInvite.Status.REVOKED, NOW, 0L);
        refusedWith(ProfileErrorCode.ROLE_FORBIDDEN, () -> CoopMembership.closeInvite(op(CoopOperation.Kind.INVITE_REVOKE, this.member),
            coop, issued, account(this.member), ProfileInvite.Status.REVOKED, NOW, 0L));
        final ProfileInvite byOwner = ProfileInvite.open(coop.id(), coop.member(this.owner), this.outsider, NOW, 5 * MINUTE, UUID.randomUUID());
        refusedWith(ProfileErrorCode.ROLE_FORBIDDEN, () -> CoopMembership.closeInvite(op(CoopOperation.Kind.INVITE_REVOKE, this.manager),
            coop, byOwner, account(this.manager), ProfileInvite.Status.REVOKED, NOW, 0L));
        // Decline: only the invitee.
        CoopMembership.closeInvite(op(CoopOperation.Kind.INVITE_REVOKE, this.outsider), coop, issued, account(this.outsider),
            ProfileInvite.Status.DECLINED, NOW, 0L);
        refusedWith(ProfileErrorCode.ROLE_FORBIDDEN, () -> CoopMembership.closeInvite(op(CoopOperation.Kind.INVITE_REVOKE, this.owner),
            coop, issued, account(this.owner), ProfileInvite.Status.DECLINED, NOW, 0L));
        // Expire: anyone, but only after the expiry by the store clock the caller read.
        refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> CoopMembership.closeInvite(op(CoopOperation.Kind.INVITE_REVOKE, this.member),
            coop, issued, account(this.member), ProfileInvite.Status.EXPIRED, NOW, 0L));
        CoopMembership.closeInvite(op(CoopOperation.Kind.INVITE_REVOKE, this.member), coop, issued, account(this.member),
            ProfileInvite.Status.EXPIRED, NOW + 5 * MINUTE, 0L);

        // An operation of the wrong kind is a programming error, not a refusal.
        assertThrows(IllegalArgumentException.class, () -> CoopMembership.createInvite(op(CoopOperation.Kind.INVITE_ACCEPT, this.owner),
            coop, account(this.owner), this.outsider, null, List.of(), POLICY, NOW, 0L));
    }

    // ------------------------------------------------------------------ conversion is one way

    @Test
    void convertIsOneWay() throws Exception {
        final AuroraProfile solo = AuroraProfile.create(this.owner, "skyblock", CLASSIC, "Apple", Map.of(), NOW);
        assertEquals(ProfileKind.SOLO, solo.kind());
        final CoopIntentPlan plan = CoopMembership.convertToCoop(op(CoopOperation.Kind.CONVERT_COOP, this.owner), solo,
            account(this.owner), 0L);
        // Vector: A compared, M reserved; the after-image is the COOP profile at M + 2 with the same identity and rows.
        assertEquals(List.of(CoopWriteGuard.Expected.account(this.owner, 0L), CoopWriteGuard.Expected.metadata(solo.id(), 0L)),
            plan.before());
        assertEquals(List.of(CoopWriteGuard.Expected.account(this.owner, 0L), CoopWriteGuard.Expected.metadata(solo.id(), 1L)),
            plan.after());
        assertEquals(1, plan.images().size());
        assertEquals(2L, plan.images().getFirst().revision());
        final AuroraProfile converted = solo.convertedToCoop();
        assertTrue(converted.coop());
        assertTrue(converted.sameIdentity(solo));
        assertEquals(solo.members(), converted.members());

        // No way back: a co-op is ALREADY_COOP, the record refuses a second conversion, and no builder makes it solo.
        refusedWith(ProfileErrorCode.ALREADY_COOP, () -> CoopMembership.convertToCoop(op(CoopOperation.Kind.CONVERT_COOP, this.owner),
            converted, account(this.owner), 0L));
        assertThrows(IllegalStateException.class, converted::convertedToCoop);
        // A solo profile holds the owner row only.
        assertThrows(IllegalArgumentException.class, () -> new AuroraProfile(solo.id(), this.owner, "skyblock", CLASSIC, "Apple",
            Map.of(), NOW, 0L, ProfileStatus.ACTIVE, coop().members(), 0L, null, ProfileKind.SOLO, null));
        // Only the owner converts; a format-1 record (no member rows) needs the migration first.
        refusedWith(ProfileErrorCode.NOT_A_MEMBER, () -> CoopMembership.convertToCoop(op(CoopOperation.Kind.CONVERT_COOP, this.outsider),
            solo, account(this.outsider), 0L));
        final AuroraProfile legacy = new AuroraProfile(UUID.randomUUID(), this.owner, "skyblock", Map.of(), NOW, 0L, ProfileStatus.ACTIVE);
        refusedWith(ProfileErrorCode.MIGRATION_REQUIRED, () -> CoopMembership.convertToCoop(op(CoopOperation.Kind.CONVERT_COOP,
            this.owner), legacy, account(this.owner), 0L));
        // Membership operations other than the conversion refuse a solo profile.
        refusedWith(ProfileErrorCode.NOT_COOP, () -> CoopMembership.createInvite(op(CoopOperation.Kind.INVITE_CREATE, this.owner), solo,
            account(this.owner), this.outsider, null, List.of(), POLICY, NOW, 0L));
        refusedWith(ProfileErrorCode.NOT_COOP, () -> CoopMembership.createVote(op(CoopOperation.Kind.VOTE_CREATE, this.owner), solo,
            account(this.owner), this.outsider, List.of(), POLICY, NOW, 0L));
    }

    // ------------------------------------------------------------------ owner protections

    @Test
    void CoopOwnerKickRefused() throws Exception {
        final AuroraProfile coop = coop();
        // Neither a MANAGER nor the OWNER itself opens a vote against the owner.
        refusedWith(ProfileErrorCode.OWNER_ACTION_REFUSED, () -> CoopMembership.createVote(op(CoopOperation.Kind.VOTE_CREATE,
            this.manager), coop, account(this.manager), this.owner, List.of(), POLICY, NOW, 0L));
        refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> CoopMembership.createVote(op(CoopOperation.Kind.VOTE_CREATE, this.owner),
            coop, account(this.owner), this.owner, List.of(), POLICY, NOW, 0L));

        // A vote record against the owner that passed anyway (forged: a two-member co-op of owner and manager) does not
        // remove the owner either.
        final AuroraProfile pair = AuroraProfile.create(this.owner, "skyblock", CLASSIC, "Apple", Map.of(), NOW).convertedToCoop()
            .withMember(ProfileMember.admitted(this.manager, ProfileRole.MANAGER, NOW));
        final KickVote forged = KickVote.open(pair, pair.member(this.owner), pair.member(this.manager), NOW, 10 * MINUTE);
        assertEquals(KickVote.Status.PASSED, forged.status());
        refusedWith(ProfileErrorCode.OWNER_ACTION_REFUSED, () -> CoopMembership.kick(op(CoopOperation.Kind.MEMBER_REMOVE, this.manager),
            pair, forged, account(this.owner), null, List.of(), POLICY, NOW, 0L));
        // The records refuse it too: the owner row is never removed, and no admission creates a second OWNER.
        assertThrows(IllegalArgumentException.class, () -> pair.withoutMember(this.owner, pair.member(this.owner).epoch()));
        assertThrows(IllegalArgumentException.class, () -> ProfileMember.admitted(this.outsider, ProfileRole.OWNER, NOW));
        assertThrows(IllegalArgumentException.class, () -> pair.withMember(new ProfileMember(this.outsider, UUID.randomUUID(),
            ProfileRole.OWNER, NOW, NOW)));
        // Owner leave is refused, ownership transfer has no builder.
        refusedWith(ProfileErrorCode.OWNER_ACTION_REFUSED, () -> CoopMembership.leave(op(CoopOperation.Kind.MEMBER_REMOVE, this.owner),
            pair, account(this.owner), null, List.of(), List.of(), NOW, 0L));
    }

    // ------------------------------------------------------------------ invites, cap, slots

    @Test
    void inviteAndAcceptRules() throws Exception {
        final AuroraProfile coop = coop();
        refusedWith(ProfileErrorCode.ALREADY_MEMBER, () -> CoopMembership.createInvite(op(CoopOperation.Kind.INVITE_CREATE, this.owner),
            coop, account(this.owner), this.member, account(this.member), List.of(), POLICY, NOW, 0L));
        refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> CoopMembership.createInvite(op(CoopOperation.Kind.INVITE_CREATE, this.owner),
            coop, account(this.owner), this.owner, account(this.owner), List.of(), POLICY, NOW, 0L));
        final ProfileInvite open = ProfileInvite.open(coop.id(), coop.member(this.owner), this.outsider, NOW, 5 * MINUTE, UUID.randomUUID());
        refusedWith(ProfileErrorCode.INVITE_EXISTS, () -> CoopMembership.createInvite(op(CoopOperation.Kind.INVITE_CREATE, this.manager),
            coop, account(this.manager), this.outsider, account(this.outsider), List.of(open), POLICY, NOW, 0L));
        // An expired open invite does not block a new one.
        CoopMembership.createInvite(op(CoopOperation.Kind.INVITE_CREATE, this.manager), coop, account(this.manager), this.outsider,
            account(this.outsider), List.of(open), POLICY, NOW + 5 * MINUTE, 0L);
        // The invitee's slots are the per-account membership limit.
        final PlayerAccount full = PlayerAccount.empty(this.outsider, 1).withProfile(UUID.randomUUID(), "kiwi");
        refusedWith(ProfileErrorCode.SLOT_LIMIT, () -> CoopMembership.createInvite(op(CoopOperation.Kind.INVITE_CREATE, this.owner),
            coop, account(this.owner), this.outsider, full, List.of(), POLICY, NOW, 0L));
        refusedWith(ProfileErrorCode.SLOT_LIMIT, () -> CoopMembership.acceptInvite(op(CoopOperation.Kind.INVITE_ACCEPT, this.outsider),
            coop, open, full, POLICY, List.of(), NOW, 0L));
        // The cap counts members (three here), not open invites.
        final CoopMembership.Policy three = new CoopMembership.Policy(3, 5 * MINUTE, 10 * MINUTE, 35 * DAY);
        refusedWith(ProfileErrorCode.COOP_FULL, () -> CoopMembership.createInvite(op(CoopOperation.Kind.INVITE_CREATE, this.owner),
            coop, account(this.owner), this.outsider, account(this.outsider), List.of(), three, NOW, 0L));
        refusedWith(ProfileErrorCode.COOP_FULL, () -> CoopMembership.acceptInvite(op(CoopOperation.Kind.INVITE_ACCEPT, this.outsider),
            coop, open, account(this.outsider), three, List.of(), NOW, 0L));
        // Accept: only the invitee, only while open and unexpired by the caller's store clock.
        refusedWith(ProfileErrorCode.INVITE_NOT_FOUND, () -> CoopMembership.acceptInvite(op(CoopOperation.Kind.INVITE_ACCEPT, this.member),
            coop, open, account(this.member), POLICY, List.of(), NOW, 0L));
        refusedWith(ProfileErrorCode.INVITE_EXPIRED, () -> CoopMembership.acceptInvite(op(CoopOperation.Kind.INVITE_ACCEPT, this.outsider),
            coop, open, account(this.outsider), POLICY, List.of(), open.expiresAtMillis(), 0L));

        // A valid accept: invitee A and M reserved, invite compared, the new head absent; the deadline is the invite's
        // expiry when it is earlier than the caller's; the row is a MEMBER with a fresh epoch, never the inviter's.
        final CoopIntentPlan accept = CoopMembership.acceptInvite(op(CoopOperation.Kind.INVITE_ACCEPT, this.outsider), coop, open,
            account(this.outsider), POLICY, List.of(), NOW, open.expiresAtMillis() + MINUTE);
        assertEquals(open.expiresAtMillis(), accept.deadlineMillis());
        assertEquals(4, accept.before().size());
        assertEquals(CoopWriteGuard.ABSENT, accept.before().get(3).revision());
        assertEquals(accept.before().get(2), accept.after().get(2), "the invite is compared, not reserved");
        assertNotEquals(coop.member(this.owner).epoch(), accept.result().epoch());
        assertEquals("apple", accept.result().alias());
        assertNull(accept.affectedAccount(), "an accept holds the invitee's own Q only");
        // The alias follows the account: an account that already uses "apple" gets "apple-2".
        final PlayerAccount apple = PlayerAccount.empty(this.outsider, SLOTS).withProfile(UUID.randomUUID(), "apple");
        assertEquals("apple-2", CoopMembership.acceptInvite(op(CoopOperation.Kind.INVITE_ACCEPT, this.outsider), coop, open, apple,
            POLICY, List.of(), NOW, 0L).result().alias());
    }

    // ------------------------------------------------------------------ votes

    @Test
    void voteRules() throws Exception {
        final AuroraProfile coop = coop();
        final KickVote open = KickVote.open(coop, coop.member(this.member), coop.member(this.owner), NOW, 10 * MINUTE);
        assertEquals(KickVote.Status.OPEN, open.status(), "two eligible voters: the owner's implicit approval is not enough");
        assertEquals(Set.of(this.owner, this.manager), open.electorate().keySet());
        // One undecided vote per profile.
        refusedWith(ProfileErrorCode.VOTE_OPEN, () -> CoopMembership.createVote(op(CoopOperation.Kind.VOTE_CREATE, this.manager), coop,
            account(this.manager), this.member, List.of(open), POLICY, NOW, 0L));
        // The target and outsiders do not vote; an expired vote and a changed electorate refuse the ballot.
        refusedWith(ProfileErrorCode.NOT_A_MEMBER, () -> CoopMembership.castVote(op(CoopOperation.Kind.VOTE_CAST, this.outsider), coop,
            open, account(this.outsider), true, NOW, 0L));
        refusedWith(ProfileErrorCode.ROLE_FORBIDDEN, () -> CoopMembership.castVote(op(CoopOperation.Kind.VOTE_CAST, this.member), coop,
            open, account(this.member), true, NOW, 0L));
        refusedWith(ProfileErrorCode.TIMEOUT, () -> CoopMembership.castVote(op(CoopOperation.Kind.VOTE_CAST, this.manager), coop, open,
            account(this.manager), true, open.expiresAtMillis(), 0L));
        final AuroraProfile grown = coop.withMember(ProfileMember.admitted(this.outsider, ProfileRole.MEMBER, NOW));
        refusedWith(ProfileErrorCode.ELECTORATE_CHANGED, () -> CoopMembership.castVote(op(CoopOperation.Kind.VOTE_CAST, this.manager),
            grown, open, account(this.manager), true, NOW, 0L));
        // The manager's approval completes the electorate: PASSED, and its deadline is the vote's expiry.
        final CoopIntentPlan cast = CoopMembership.castVote(op(CoopOperation.Kind.VOTE_CAST, this.manager), coop, open,
            account(this.manager), true, NOW, 0L);
        assertEquals(open.expiresAtMillis(), cast.deadlineMillis());
        final KickVote passed = open.approved(this.manager, coop.member(this.manager).epoch(), NOW);
        assertEquals(KickVote.Status.PASSED, passed.status());
        // The removal needs a passed vote, a current electorate and an eligible actor.
        refusedWith(ProfileErrorCode.INVALID_REQUEST, () -> CoopMembership.kick(op(CoopOperation.Kind.MEMBER_REMOVE, this.owner), coop,
            open, account(this.member), null, List.of(), POLICY, NOW, 0L));
        refusedWith(ProfileErrorCode.ELECTORATE_CHANGED, () -> CoopMembership.kick(op(CoopOperation.Kind.MEMBER_REMOVE, this.owner),
            grown, passed, account(this.member), null, List.of(), POLICY, NOW, 0L));
        final CoopIntentPlan kick = CoopMembership.kick(op(CoopOperation.Kind.MEMBER_REMOVE, this.owner), coop, passed,
            account(this.member), null, List.of(), POLICY, NOW, 0L);
        assertEquals(this.member, kick.affectedAccount(), "a kick holds the removed member's Q too");
        assertEquals(coop.member(this.member).epoch(), kick.result().epoch());
        assertEquals(passed.id(), kick.result().recordId());
        // A removal of the account's active profile needs the Build 53 gated capture.
        final PlayerAccount active = PlayerAccount.empty(this.member, SLOTS).withProfile(coop.id(), "apple")
            .withActive(coop.id(), coop.member(this.member).epoch());
        refusedWith(ProfileErrorCode.MEMBER_ONLINE, () -> CoopMembership.kick(op(CoopOperation.Kind.MEMBER_REMOVE, this.owner), coop,
            passed, active, null, List.of(), POLICY, NOW, 0L));
        refusedWith(ProfileErrorCode.MEMBER_ONLINE, () -> CoopMembership.leave(op(CoopOperation.Kind.MEMBER_REMOVE, this.member), coop,
            active, null, List.of(), List.of(), NOW, 0L));
    }

    @Test
    void twoMemberInactivityRule() throws Exception {
        final AuroraProfile pair = AuroraProfile.create(this.owner, "skyblock", CLASSIC, "Apple", Map.of(), NOW).convertedToCoop()
            .withMember(ProfileMember.admitted(this.member, ProfileRole.MEMBER, NOW));
        refusedWith(ProfileErrorCode.TARGET_NOT_INACTIVE, () -> CoopMembership.createVote(op(CoopOperation.Kind.VOTE_CREATE, this.owner),
            pair, account(this.owner), this.member, List.of(), POLICY, NOW + POLICY.inactiveMillis() - 1L, 0L));
        final CoopIntentPlan plan = CoopMembership.createVote(op(CoopOperation.Kind.VOTE_CREATE, this.owner), pair, account(this.owner),
            this.member, List.of(), POLICY, NOW + POLICY.inactiveMillis(), 0L);
        assertEquals(1, plan.images().size());
        // In a larger co-op the rule does not apply: the vote opens and waits for the other voters.
        CoopMembership.createVote(op(CoopOperation.Kind.VOTE_CREATE, this.owner), coop(), account(this.owner), this.member, List.of(),
            POLICY, NOW, 0L);
    }

    // ------------------------------------------------------------------ a removed inviter's invitation admits nobody

    /**
     * An accept requires the inviter to still be a member at the epoch that issued the invitation, with the INVITE
     * permission; a removal revokes the open invitations the removed member issued and cancels the undecided vote in
     * its own vector, and an accept cancels the vote as well.
     */
    @Test
    void CoopRemovedInviterInviteRefused() throws Exception {
        final AuroraProfile coop = coop();
        final ProfileInvite byManager = ProfileInvite.open(coop.id(), coop.member(this.manager), this.outsider, NOW, 5 * MINUTE,
            UUID.randomUUID());
        // Still a member at the issuing epoch: admitted.
        CoopMembership.acceptInvite(op(CoopOperation.Kind.INVITE_ACCEPT, this.outsider), coop, byManager, account(this.outsider), POLICY,
            List.of(), NOW, 0L);
        // The manager left: refused. Rejoined under a new epoch: still refused (the invitation names the old epoch).
        final AuroraProfile left = coop.withoutMember(this.manager, coop.member(this.manager).epoch());
        refusedWith(ProfileErrorCode.INVITE_NOT_FOUND, () -> CoopMembership.acceptInvite(op(CoopOperation.Kind.INVITE_ACCEPT, this.outsider),
            left, byManager, account(this.outsider), POLICY, List.of(), NOW, 0L));
        final AuroraProfile rejoined = left.withMember(ProfileMember.admitted(this.manager, ProfileRole.MANAGER, NOW));
        refusedWith(ProfileErrorCode.INVITE_NOT_FOUND, () -> CoopMembership.acceptInvite(op(CoopOperation.Kind.INVITE_ACCEPT, this.outsider),
            rejoined, byManager, account(this.outsider), POLICY, List.of(), NOW, 0L));
        // An invitation a MEMBER issued (no INVITE permission) admits nobody either.
        final ProfileInvite byMember = ProfileInvite.open(coop.id(), coop.member(this.member), this.outsider, NOW, 5 * MINUTE,
            UUID.randomUUID());
        refusedWith(ProfileErrorCode.INVITE_NOT_FOUND, () -> CoopMembership.acceptInvite(op(CoopOperation.Kind.INVITE_ACCEPT, this.outsider),
            coop, byMember, account(this.outsider), POLICY, List.of(), NOW, 0L));

        // A leave revokes the leaver's open invitations (not another member's) and cancels the undecided vote, in one vector.
        final KickVote open = KickVote.open(coop, coop.member(this.member), coop.member(this.owner), NOW, 10 * MINUTE);
        final ProfileInvite byOwner = ProfileInvite.open(coop.id(), coop.member(this.owner), UUID.randomUUID(), NOW, 5 * MINUTE,
            UUID.randomUUID());
        final CoopIntentPlan leave = CoopMembership.leave(op(CoopOperation.Kind.MEMBER_REMOVE, this.manager), coop, account(this.manager),
            null, List.of(byManager, byOwner), List.of(open), NOW, 0L);
        assertTrue(leave.before().contains(Expected.record(byManager.id(), byManager.revision())), "the leaver's invitation is closed");
        assertFalse(leave.before().contains(Expected.record(byOwner.id(), byOwner.revision())), "another member's invitation is left alone");
        assertTrue(leave.before().contains(Expected.record(open.id(), open.revision())), "the undecided vote is cancelled");
        assertEquals(5, leave.images().size(), "account, profile, salvage, revoked invitation, cancelled vote");
        // An accept cancels the vote too (its electorate changes).
        final CoopIntentPlan accept = CoopMembership.acceptInvite(op(CoopOperation.Kind.INVITE_ACCEPT, this.outsider), coop, byManager,
            account(this.outsider), POLICY, List.of(open), NOW, 0L);
        assertTrue(accept.before().contains(Expected.record(open.id(), open.revision())));
        assertEquals(5, accept.images().size(), "account, profile, invitation, head, cancelled vote");
    }
}
