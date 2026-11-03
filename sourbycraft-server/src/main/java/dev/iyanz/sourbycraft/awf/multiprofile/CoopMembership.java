package dev.iyanz.sourbycraft.awf.multiprofile;

import dev.iyanz.sourbycraft.api.profile.ProfileErrorCode;
import dev.iyanz.sourbycraft.api.profile.ProfileOperationException;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopWriteGuard.Expected;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopIntentPlan.Image;
import dev.iyanz.sourbycraft.awf.multiprofile.CoopIntentPlan.Result;
import dev.iyanz.sourbycraft.awf.multiprofile.ProfileIntent.SnapshotRef;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The co-op membership operations of Build 52 (COOP-DESIGN §5 and §10 slice 4) as {@link CoopIntentPlan}s, plus
 * the bounded listings the engine and commands read.
 *
 * <p>Every builder takes the records the caller read on the I/O lane (profile, accounts, invite, vote, head), the
 * store clock ({@link ProfileRepository#storeClockMillis}) and the policy, checks the membership rules against
 * those records and returns a plan whose vector names every record it read at the revision it read. The
 * repository compares that vector at prepare and at commit, so a rule checked here holds at commit or the commit
 * is {@code STALE_STATE}: the co-op size cap is checked against the member rows of the {@code M} the plan reserves
 * (two accepts racing for the last seat cannot both commit), an invite's expiry is the accept's commit deadline
 * (both backends refuse a PREPARED intent past its deadline by the store clock), a kick's electorate is compared
 * with the member rows of the same {@code M}. Refusals are {@link ProfileOperationException}s with the Build 52
 * codes; nothing is written by this class.</p>
 *
 * <p><b>Every plan reserves {@code M}.</b> Each membership operation, including those that write no profile
 * after-image (invite create/close, vote create/cast/cancel), lists the profile's metadata record at {@code N}
 * before and {@code N + 1} after: PREPARE installs the operation's reservation on it, COMMIT or ABORT clears it. So
 * every plan reserves at least one record, the abort rules are the same for both backends (an abort compares the
 * intent's own revision and the records it reserved, never a record it only compared), and two membership
 * operations on one profile never interleave between prepare and commit: a committed-but-unfinished one holds the
 * reservation until it is finished, so a conflicting plan is refused at prepare instead of leaving the first one
 * {@code RECOVERY_REQUIRED}. The invite, vote and salvage records (no reservation field) stay compare-only.</p>
 *
 * <p>A membership change closes what it invalidates in the same intent: an accept or a removal cancels the
 * profile's undecided kick votes ({@code openVotes}, {@link KickVote.Status#CANCELLED}: their frozen electorate no
 * longer matches), a removal revokes the open invitations the removed member issued ({@code issuedInvites}, at most
 * {@value #MAX_CLOSED_PER_REMOVAL} of them; any beyond that stay listed until they expire and are refused at accept,
 * which requires the inviter to still be a member at the epoch that issued the invite).</p>
 *
 * <p>Checks the <em>caller</em> owns, because they need runtime knowledge: co-op enabled ({@code COOP_DISABLED}),
 * the node holding the profile's bound world (OD-1, {@code WRONG_NODE}), whether the member to remove plays the
 * profile on another node ({@code MEMBER_ONLINE}), and the claims ({@code P} on the profile, {@code Q} on the actor
 * and on a kicked member's account). A removal of a membership that is the removed account's <em>active</em>
 * profile is refused here ({@code MEMBER_ONLINE}) unless the engine passes the member's {@link NextActive next
 * profile} (Build 53): an online member was switched out before the plan is built, an offline member's account
 * pointer is moved by the same intent.</p>
 *
 * <p>Refused in this port, with a reason: owner leave, owner kick and ownership transfer
 * ({@code OWNER_ACTION_REFUSED}; a sole owner deletes the profile through the existing delete intent), role changes
 * (no builder), any operation on a solo profile but conversion ({@code NOT_COOP}), conversion of a co-op profile
 * ({@code ALREADY_COOP}). Removal writes a salvage record in state {@link ProfileSalvage.State#UNAVAILABLE}:
 * nothing is extracted or delivered before Build 54.</p>
 */
public final class CoopMembership {

    private CoopMembership() {}

    /**
     * The most invitations a removal closes in its own intent (COOP-DESIGN §9 keeps the vector under
     * {@link CoopWriteGuard#MAX_VECTOR}); the inviter check at accept covers the rest.
     */
    public static final int MAX_CLOSED_PER_REMOVAL = 16;

    /**
     * The policy values the operations apply; the engine builds it from its settings (every value RESTART_REQUIRED).
     *
     * @param maxMembers members per co-op, owner included (the settings cap it at 8; the record holds up to 64)
     * @param inviteLifetimeMillis how long an invite stays open
     * @param voteLifetimeMillis how long a kick vote stays open
     * @param inactiveMillis in a two-member co-op, how long the target must have been inactive before a kick vote
     */
    public record Policy(int maxMembers, long inviteLifetimeMillis, long voteLifetimeMillis, long inactiveMillis) {

        public Policy {
            if (maxMembers < 2 || maxMembers > AuroraProfile.MAX_MEMBERS) throw new IllegalArgumentException("maxMembers " + maxMembers);
            if (inviteLifetimeMillis <= 0 || voteLifetimeMillis <= 0 || inactiveMillis <= 0) throw new IllegalArgumentException("policy times");
        }
    }

    // ================================================================== conversion and label

    /**
     * The one-way conversion of {@code profile} to co-op by its OWNER. Vector: actor {@code A} compared, {@code M}
     * reserved; after-image: the profile with {@link ProfileKind#COOP}.
     */
    public static CoopIntentPlan convertToCoop(final CoopOperation op, final AuroraProfile profile, final PlayerAccount actorAccount,
                                               final long deadlineMillis) throws IOException {
        kind(op, CoopOperation.Kind.CONVERT_COOP);
        requireUsable(profile);
        if (profile.legacy()) throw refuse(ProfileErrorCode.MIGRATION_REQUIRED, "profile " + profile.id() + " has no member rows; migrate the store first");
        if (profile.coop()) throw refuse(ProfileErrorCode.ALREADY_COOP, "profile " + profile.id() + " is already a co-op profile");
        requireRole(profile, op.actor(), ProfileRole.Permission.CONVERT_TO_COOP);
        requireAccount(actorAccount, op.actor());
        final AuroraProfile converted = profile.convertedToCoop();
        return new CoopIntentPlan(op, profile.id(), null,
            List.of(Expected.account(op.actor(), actorAccount.revision()), Expected.metadata(profile.id(), profile.revision())),
            List.of(Expected.account(op.actor(), actorAccount.revision()), Expected.metadata(profile.id(), profile.revision() + 1L)),
            List.of(profileImage(converted.atRevision(profile.revision() + 2L))), List.of(), deadlineMillis, Result.NONE);
    }

    /**
     * A new shared display label ({@code null} clears it) by the OWNER. Changes the profile record only: no member's
     * account-local alias is touched. Vector: actor {@code A} compared, {@code M} reserved.
     */
    public static CoopIntentPlan renameLabel(final CoopOperation op, final AuroraProfile profile, final PlayerAccount actorAccount,
                                             final String label, final long deadlineMillis) throws IOException {
        kind(op, CoopOperation.Kind.RENAME_DISPLAY);
        requireUsable(profile);
        requireRole(profile, op.actor(), ProfileRole.Permission.CHANGE_LABEL);
        requireAccount(actorAccount, op.actor());
        if (label != null && !AuroraProfile.validDisplayLabel(label)) {
            throw refuse(ProfileErrorCode.INVALID_REQUEST, "the display label must be 1-" + AuroraProfile.MAX_DISPLAY_LABEL
                + " printable characters without surrounding whitespace");
        }
        final AuroraProfile renamed = profile.withDisplayLabel(label);
        return new CoopIntentPlan(op, profile.id(), null,
            List.of(Expected.account(op.actor(), actorAccount.revision()), Expected.metadata(profile.id(), profile.revision())),
            List.of(Expected.account(op.actor(), actorAccount.revision()), Expected.metadata(profile.id(), profile.revision() + 1L)),
            List.of(profileImage(renamed.atRevision(profile.revision() + 2L))), List.of(), deadlineMillis, Result.NONE);
    }

    // ================================================================== invites

    /**
     * A new invitation of {@code invitee} by the actor (OWNER or MANAGER). Refuses a solo profile, a non-member or
     * MEMBER actor, an invitee that is a member, a full co-op (members, not open invites, count), an open invite of
     * the same invitee ({@code openInvites}: the profile's open invites, read through the index) and, when the
     * invitee's account is given, a full account. Vector: actor {@code A} compared, {@code M} reserved (no profile
     * after-image: the commit clears the reservation), the invite absent; after-image: the OPEN invite at revision 0.
     */
    public static CoopIntentPlan createInvite(final CoopOperation op, final AuroraProfile profile, final PlayerAccount actorAccount,
                                              final UUID invitee, final PlayerAccount inviteeAccount, final List<ProfileInvite> openInvites,
                                              final Policy policy, final long storeNow, final long deadlineMillis) throws IOException {
        kind(op, CoopOperation.Kind.INVITE_CREATE);
        requireCoop(profile);
        requireUsable(profile);
        final ProfileMember inviter = requireRole(profile, op.actor(), ProfileRole.Permission.INVITE);
        requireAccount(actorAccount, op.actor());
        Objects.requireNonNull(invitee, "invitee");
        if (invitee.equals(op.actor())) throw refuse(ProfileErrorCode.INVALID_REQUEST, "a member cannot invite itself");
        if (profile.member(invitee) != null) throw refuse(ProfileErrorCode.ALREADY_MEMBER, invitee + " is already a member of " + profile.id());
        requireSeat(profile, policy);
        for (final ProfileInvite open : openInvites) {
            if (open.openFor(invitee, profile.id(), storeNow)) {
                throw refuse(ProfileErrorCode.INVITE_EXISTS, "invite " + open.id() + " to " + invitee + " is already open");
            }
        }
        if (inviteeAccount != null) {
            requireAccount(inviteeAccount, invitee);
            if (inviteeAccount.owns(profile.id())) throw refuse(ProfileErrorCode.ALREADY_MEMBER, invitee + " already accesses " + profile.id());
            if (inviteeAccount.full()) {
                throw refuse(ProfileErrorCode.SLOT_LIMIT, invitee + " has no free profile slot (" + inviteeAccount.slotLimit() + ")");
            }
        }
        final ProfileInvite invite = ProfileInvite.open(profile.id(), inviter, invitee, storeNow, policy.inviteLifetimeMillis(), op.operationId());
        final List<Expected> before = List.of(Expected.account(op.actor(), actorAccount.revision()),
            Expected.metadata(profile.id(), profile.revision()), Expected.record(invite.id(), CoopWriteGuard.ABSENT));
        final List<Expected> after = List.of(Expected.account(op.actor(), actorAccount.revision()),
            Expected.metadata(profile.id(), profile.revision() + 1L), Expected.record(invite.id(), CoopWriteGuard.ABSENT));
        return new CoopIntentPlan(op, profile.id(), null, before, after, List.of(inviteImage(invite)), List.of(), deadlineMillis,
            new Result(invite.id(), null, null, null));
    }

    /**
     * Closes an open invite: {@link ProfileInvite.Status#DECLINED} by the invitee, {@link ProfileInvite.Status#REVOKED}
     * by the inviter, by the OWNER (any invitation of the profile) or by a MANAGER (only the invitations it issued,
     * {@code ROLE_FORBIDDEN} otherwise), {@link ProfileInvite.Status#EXPIRED} by anyone once {@code storeNow} passed
     * its expiry (a cleanup). Vector: actor {@code A} compared, {@code M} reserved, the invite at its revision;
     * after-image at revision + 1.
     */
    public static CoopIntentPlan closeInvite(final CoopOperation op, final AuroraProfile profile, final ProfileInvite invite,
                                             final PlayerAccount actorAccount, final ProfileInvite.Status next, final long storeNow,
                                             final long deadlineMillis) throws IOException {
        kind(op, CoopOperation.Kind.INVITE_REVOKE);
        requireInvite(invite, profile);
        requireAccount(actorAccount, op.actor());
        if (!invite.open()) throw refuse(ProfileErrorCode.INVITE_NOT_FOUND, "invite " + invite.id() + " is " + invite.status());
        switch (next) {
            case DECLINED -> {
                if (!invite.invitee().equals(op.actor())) throw refuse(ProfileErrorCode.ROLE_FORBIDDEN, "only the invitee declines an invite");
            }
            case REVOKED -> {
                if (!invite.inviter().equals(op.actor())) {
                    final ProfileMember row = requireRole(profile, op.actor(), ProfileRole.Permission.CANCEL_INVITE);
                    if (row.role() != ProfileRole.OWNER) {
                        throw refuse(ProfileErrorCode.ROLE_FORBIDDEN, "a " + row.role() + " cancels only the invitations it issued;"
                            + " invite " + invite.id() + " was issued by " + invite.inviter());
                    }
                }
            }
            case EXPIRED -> {
                if (!invite.expired(storeNow)) throw refuse(ProfileErrorCode.INVALID_REQUEST, "invite " + invite.id() + " has not expired");
            }
            default -> throw refuse(ProfileErrorCode.INVALID_REQUEST, "an invite closes as DECLINED, REVOKED or EXPIRED");
        }
        final ProfileInvite closed = invite.closed(next, op.operationId());
        final List<Expected> before = List.of(Expected.account(op.actor(), actorAccount.revision()),
            Expected.metadata(profile.id(), profile.revision()), Expected.record(invite.id(), invite.revision()));
        final List<Expected> after = List.of(Expected.account(op.actor(), actorAccount.revision()),
            Expected.metadata(profile.id(), profile.revision() + 1L), Expected.record(invite.id(), invite.revision()));
        return new CoopIntentPlan(op, profile.id(), null, before, after, List.of(inviteImage(closed)), List.of(), deadlineMillis,
            new Result(invite.id(), null, null, null));
    }

    /**
     * The invitee (the operation's actor) accepts: one commit consumes the invite, adds the member row with a fresh
     * epoch, creates the member's UNINITIALIZED head and gives the account access under an account-unique alias
     * ({@link CuteNames#allocateAlias}: the profile name's alias form, suffixed when the account already uses it).
     * The co-op cap and the account's slot limit are checked here against the records whose revisions the plan
     * compares; the invite's expiry becomes the commit deadline. The inviter must still be a member at the epoch
     * that issued the invite, with the INVITE permission ({@code INVITE_NOT_FOUND} otherwise: an invitation of a
     * removed or rejoined member admits nobody). The profile's undecided kick votes ({@code openVotes}, read through
     * the index) are cancelled in the same intent, since the new row changes their electorate. Vector: invitee
     * {@code A} reserved, {@code M} reserved, the invite and the votes compared, the new head absent.
     */
    public static CoopIntentPlan acceptInvite(final CoopOperation op, final AuroraProfile profile, final ProfileInvite invite,
                                              final PlayerAccount inviteeAccount, final Policy policy, final List<KickVote> openVotes,
                                              final long storeNow, final long deadlineMillis) throws IOException {
        kind(op, CoopOperation.Kind.INVITE_ACCEPT);
        requireInvite(invite, profile);
        requireCoop(profile);
        requireUsable(profile);
        final UUID invitee = op.actor();
        requireAccount(inviteeAccount, invitee);
        if (!invite.invitee().equals(invitee) || !invite.open()) {
            throw refuse(ProfileErrorCode.INVITE_NOT_FOUND, "invite " + invite.id() + " is not an open invitation of " + invitee);
        }
        if (invite.expired(storeNow)) throw refuse(ProfileErrorCode.INVITE_EXPIRED, "invite " + invite.id() + " expired at " + invite.expiresAtMillis());
        final ProfileMember inviter = profile.member(invite.inviter());
        if (inviter == null || !inviter.epoch().equals(invite.inviterEpoch()) || !inviter.role().allows(ProfileRole.Permission.INVITE)) {
            throw refuse(ProfileErrorCode.INVITE_NOT_FOUND, "invite " + invite.id() + " was issued by " + invite.inviter()
                + ", who is no longer a member of " + profile.id() + " with the INVITE permission at that epoch; ask for a new invitation");
        }
        if (profile.member(invitee) != null || inviteeAccount.owns(profile.id())) {
            throw refuse(ProfileErrorCode.ALREADY_MEMBER, invitee + " is already a member of " + profile.id());
        }
        requireSeat(profile, policy);
        if (inviteeAccount.full()) throw refuse(ProfileErrorCode.SLOT_LIMIT, invitee + " has no free profile slot (" + inviteeAccount.slotLimit() + ")");
        final ProfileMember row = ProfileMember.admitted(invitee, ProfileRole.MEMBER, storeNow);
        final String alias = CuteNames.allocateAlias(CuteNames.alias(profile.name()), inviteeAccount.takenAliases());
        final PlayerAccount accountAfter = inviteeAccount.withProfile(profile.id(), alias).atRevision(inviteeAccount.revision() + 2L);
        final AuroraProfile profileAfter = profile.withMember(row).atRevision(profile.revision() + 2L);
        final ProfileInvite accepted = invite.accepted(row.epoch(), op.operationId());
        final MemberStateHead head = MemberStateHead.uninitialized(profile.id(), invitee, row.epoch());
        final List<Expected> before = new ArrayList<>(List.of(Expected.account(invitee, inviteeAccount.revision()),
            Expected.metadata(profile.id(), profile.revision()), Expected.record(invite.id(), invite.revision()),
            Expected.head(profile.id(), invitee, row.epoch(), CoopWriteGuard.ABSENT)));
        final List<Expected> after = new ArrayList<>(List.of(Expected.account(invitee, inviteeAccount.revision() + 1L),
            Expected.metadata(profile.id(), profile.revision() + 1L), Expected.record(invite.id(), invite.revision()),
            Expected.head(profile.id(), invitee, row.epoch(), CoopWriteGuard.ABSENT)));
        final List<Image> images = new ArrayList<>(List.of(accountImage(accountAfter), profileImage(profileAfter), inviteImage(accepted),
            headImage(head)));
        cancelVotes(profile, openVotes, null, before, after, images);
        return new CoopIntentPlan(op, profile.id(), null, before, after, images, List.of(),
            earliest(deadlineMillis, invite.expiresAtMillis()), new Result(invite.id(), row.epoch(), alias, null));
    }

    // ================================================================== leave and kick

    /**
     * The actor leaves {@code profile}: its row is dropped, its head (if any) becomes CONSUMED, an UNAVAILABLE salvage
     * record pins what the head named, and the account loses access and alias. Refused for the OWNER
     * ({@code OWNER_ACTION_REFUSED}; a sole owner deletes the profile) and while the profile is the account's active
     * one ({@code MEMBER_ONLINE}: the engine switches an online member out first, Build 53, or hands an offline
     * member's pointer to {@link #leave(CoopOperation, AuroraProfile, PlayerAccount, MemberStateHead, List, List, NextActive, long, long)}).
     * The open invitations the member issued ({@code issuedInvites}, the profile's open invites read through the
     * index; other inviters' are ignored) are revoked and the profile's undecided kick votes ({@code openVotes})
     * cancelled in the same intent. Vector: actor {@code A} reserved, {@code M} reserved, the head reserved when it
     * exists, the salvage record absent, the closed invites and votes compared.
     */
    public static CoopIntentPlan leave(final CoopOperation op, final AuroraProfile profile, final PlayerAccount memberAccount,
                                       final MemberStateHead head, final List<ProfileInvite> issuedInvites, final List<KickVote> openVotes,
                                       final long storeNow, final long deadlineMillis) throws IOException {
        return leave(op, profile, memberAccount, head, issuedInvites, openVotes, null, storeNow, deadlineMillis);
    }

    /**
     * {@link #leave(CoopOperation, AuroraProfile, PlayerAccount, MemberStateHead, List, List, long, long)} for a member
     * whose active profile is the one being left and who is offline (Build 53): the same intent moves the account's
     * active pointer to {@code next} (another profile the member has access to, or a fresh solo profile the engine
     * created first), whose {@code M} and the member's head there are compared, so the next authoritative login
     * restores that profile's committed head and never the co-op items the player left with. {@code next == null}
     * keeps the Build 52 rule (the profile must not be active).
     */
    public static CoopIntentPlan leave(final CoopOperation op, final AuroraProfile profile, final PlayerAccount memberAccount,
                                       final MemberStateHead head, final List<ProfileInvite> issuedInvites, final List<KickVote> openVotes,
                                       final NextActive next, final long storeNow, final long deadlineMillis) throws IOException {
        kind(op, CoopOperation.Kind.MEMBER_REMOVE);
        requireCoop(profile);
        final ProfileMember row = requireMember(profile, op.actor());
        if (!row.role().allows(ProfileRole.Permission.LEAVE)) {
            throw refuse(ProfileErrorCode.OWNER_ACTION_REFUSED, "the OWNER cannot leave " + profile.id() + "; delete the profile instead");
        }
        return removal(op, profile, row, memberAccount, head, null, issuedInvites, openVotes, next, storeNow, deadlineMillis);
    }

    /**
     * Where a removed member's account points afterwards when the removed membership was its active profile (Build
     * 53): the profile (the member has a membership row in it, {@code epoch}), its metadata revision and the member's
     * head there as read ({@code null} head = no stream yet), all compared by the removal's commit.
     */
    public record NextActive(AuroraProfile profile, UUID epoch, MemberStateHead head) {

        public NextActive {
            Objects.requireNonNull(profile, "profile");
            Objects.requireNonNull(epoch, "epoch");
        }
    }

    /**
     * Executes a passed kick vote: the target's row is dropped, its head CONSUMED, an UNAVAILABLE salvage record
     * written, the account loses access, the vote becomes EXECUTED naming this operation. The actor must be an
     * eligible voter of the vote. Refuses a vote that has not passed, an electorate that is no longer the profile's
     * current non-target membership ({@code ELECTORATE_CHANGED}: run {@link #cancelVote} with CANCELLED), the OWNER
     * as target, a two-member co-op whose target is not inactive long enough ({@code TARGET_NOT_INACTIVE}) and a
     * target whose active profile this is ({@code MEMBER_ONLINE}). The open invitations the target issued
     * ({@code issuedInvites}) are revoked in the same intent; the vote's expiry bounds the commit deadline. The plan's
     * {@link CoopIntentPlan#affectedAccount} is the target: the caller holds a {@code Q} on it (an offline-account
     * claim in Build 52).
     */
    public static CoopIntentPlan kick(final CoopOperation op, final AuroraProfile profile, final KickVote vote, final PlayerAccount targetAccount,
                                      final MemberStateHead targetHead, final List<ProfileInvite> issuedInvites, final Policy policy,
                                      final long storeNow, final long deadlineMillis) throws IOException {
        return kick(op, profile, vote, targetAccount, targetHead, issuedInvites, policy, null, storeNow, deadlineMillis);
    }

    /** {@link #kick} with the offline target's next active profile ({@link NextActive}; see the leave overload). */
    public static CoopIntentPlan kick(final CoopOperation op, final AuroraProfile profile, final KickVote vote, final PlayerAccount targetAccount,
                                      final MemberStateHead targetHead, final List<ProfileInvite> issuedInvites, final Policy policy,
                                      final NextActive next, final long storeNow, final long deadlineMillis) throws IOException {
        kind(op, CoopOperation.Kind.MEMBER_REMOVE);
        requireCoop(profile);
        requireVote(vote, profile);
        if (!vote.undecided()) throw refuse(ProfileErrorCode.VOTE_NOT_FOUND, "vote " + vote.id() + " is " + vote.status());
        if (vote.expired(storeNow)) throw refuse(ProfileErrorCode.TIMEOUT, "vote " + vote.id() + " expired at " + vote.expiresAtMillis() + "; cancel it");
        if (!vote.electorateCurrent(profile.members())) {
            throw refuse(ProfileErrorCode.ELECTORATE_CHANGED, "the members of " + profile.id() + " changed since vote " + vote.id() + " opened");
        }
        if (!vote.passed()) throw refuse(ProfileErrorCode.INVALID_REQUEST, "vote " + vote.id() + " has not passed (" + vote.approvals().size()
            + " of " + vote.electorate().size() + " approvals)");
        final ProfileMember actor = requireMember(profile, op.actor());
        if (!vote.eligible(actor.playerId(), actor.epoch())) {
            throw refuse(ProfileErrorCode.ROLE_FORBIDDEN, op.actor() + " is not an eligible voter of vote " + vote.id());
        }
        final ProfileMember target = requireMember(profile, vote.target());
        if (target.role() == ProfileRole.OWNER) throw refuse(ProfileErrorCode.OWNER_ACTION_REFUSED, "the OWNER cannot be kicked");
        requireInactivityRule(vote.electorate().size(), target, policy, storeNow);
        return removal(op, profile, target, targetAccount, targetHead, vote, issuedInvites, List.of(), next, storeNow,
            earliest(deadlineMillis, vote.expiresAtMillis()));
    }

    private static CoopIntentPlan removal(final CoopOperation op, final AuroraProfile profile, final ProfileMember removed,
                                          final PlayerAccount removedAccount, final MemberStateHead head, final KickVote vote,
                                          final List<ProfileInvite> issuedInvites, final List<KickVote> openVotes, final NextActive next,
                                          final long storeNow, final long deadlineMillis) throws IOException {
        requireAccount(removedAccount, removed.playerId());
        final boolean active = profile.id().equals(removedAccount.activeProfile());
        if (active && next == null) {
            throw refuse(ProfileErrorCode.MEMBER_ONLINE, removed.playerId() + " has " + profile.id() + " active; an online member is"
                + " captured and switched out first, an offline one needs a next profile to point at. Nothing was written");
        }
        if (next != null) {
            if (next.profile().id().equals(profile.id())) throw new IllegalArgumentException("the next profile is the one being left");
            if (!removedAccount.owns(next.profile().id()) || !next.profile().hasMember(removed.playerId(), next.epoch())) {
                throw refuse(ProfileErrorCode.STALE_STATE, removed.playerId() + " has no membership epoch " + next.epoch() + " in "
                    + next.profile().id() + " to move to; nothing was written");
            }
            if (next.profile().status() != ProfileStatus.ACTIVE || !next.profile().usable()) {
                throw refuse(ProfileErrorCode.PROFILE_LOCKED, "the next profile " + next.profile().id() + " is " + next.profile().status());
            }
        }
        if (head != null && (!head.profileId().equals(profile.id()) || !head.memberId().equals(removed.playerId())
            || !head.epoch().equals(removed.epoch()))) {
            throw refuse(ProfileErrorCode.INVALID_REQUEST, "head " + head.describe() + " is not the stream of the removed membership");
        }
        if (head != null && head.state() == MemberStateHead.State.CONSUMED) {
            throw refuse(ProfileErrorCode.STALE_STATE, "stream " + head.describe() + " is already consumed by " + head.consumingOperationId());
        }
        final UUID member = removed.playerId();
        final UUID epoch = removed.epoch();
        final ProfileSalvage salvage = ProfileSalvage.unavailable(profile.id(), member, epoch, op.operationId(), head, storeNow);
        final PlayerAccount withoutMembership = removedAccount.withoutProfile(profile.id());
        final PlayerAccount accountAfter = (active && next != null ? withoutMembership.withActive(next.profile().id(), next.epoch())
            : withoutMembership).atRevision(removedAccount.revision() + 2L);
        final AuroraProfile profileAfter = profile.withoutMember(member, epoch).atRevision(profile.revision() + 2L);
        final List<Expected> before = new ArrayList<>(8);
        final List<Expected> after = new ArrayList<>(8);
        final List<Image> images = new ArrayList<>(8);
        before.add(Expected.account(member, removedAccount.revision()));
        after.add(Expected.account(member, removedAccount.revision() + 1L));
        images.add(accountImage(accountAfter));
        before.add(Expected.metadata(profile.id(), profile.revision()));
        after.add(Expected.metadata(profile.id(), profile.revision() + 1L));
        images.add(profileImage(profileAfter));
        if (head != null) {
            before.add(Expected.head(profile.id(), member, epoch, head.revision()));
            after.add(Expected.head(profile.id(), member, epoch, head.revision() + 1L));
            images.add(headImage(head.consumed(op.operationId()).atRevision(head.revision() + 2L)));
        }
        before.add(Expected.record(salvage.id(), CoopWriteGuard.ABSENT));
        after.add(Expected.record(salvage.id(), CoopWriteGuard.ABSENT));
        images.add(salvageImage(salvage));
        if (vote != null) {
            before.add(Expected.record(vote.id(), vote.revision()));
            after.add(Expected.record(vote.id(), vote.revision()));
            images.add(voteImage(vote.executed(op.operationId())));
        }
        if (active && next != null) {
            // The pointer moves only onto the profile and stream the engine read: both are compared, not advanced.
            before.add(Expected.metadata(next.profile().id(), next.profile().revision()));
            after.add(Expected.metadata(next.profile().id(), next.profile().revision()));
            final long nextHead = next.head() == null ? CoopWriteGuard.ABSENT : next.head().revision();
            before.add(Expected.head(next.profile().id(), member, next.epoch(), nextHead));
            after.add(Expected.head(next.profile().id(), member, next.epoch(), nextHead));
        }
        revokeInvites(profile, removed.playerId(), issuedInvites, op.operationId(), before, after, images);
        cancelVotes(profile, openVotes, vote, before, after, images);
        final List<SnapshotRef> pins = head != null && head.restorable()
            ? List.of(new SnapshotRef(profile.id(), member, epoch, head.snapshotId())) : List.of();
        return new CoopIntentPlan(op, profile.id(), op.actor().equals(member) ? null : member, before, after, images, pins, deadlineMillis,
            new Result(vote == null ? salvage.id() : vote.id(), epoch, null, salvage.id()));
    }

    // ================================================================== kick votes

    /**
     * Opens a kick vote against {@code target} by the actor (OWNER or MANAGER), who approves implicitly. The electorate
     * is every current member but the target; in a two-member co-op (electorate of one) the target must have been
     * inactive for the policy's period ({@code TARGET_NOT_INACTIVE}) and the vote is PASSED at once. One undecided vote
     * per profile ({@code openVotes}: the profile's undecided votes read through the index; {@code VOTE_OPEN}).
     * Vector: actor {@code A} compared, {@code M} reserved, the vote absent.
     */
    public static CoopIntentPlan createVote(final CoopOperation op, final AuroraProfile profile, final PlayerAccount actorAccount,
                                            final UUID target, final List<KickVote> openVotes, final Policy policy, final long storeNow,
                                            final long deadlineMillis) throws IOException {
        kind(op, CoopOperation.Kind.VOTE_CREATE);
        requireCoop(profile);
        requireUsable(profile);
        final ProfileMember initiator = requireRole(profile, op.actor(), ProfileRole.Permission.START_KICK_VOTE);
        requireAccount(actorAccount, op.actor());
        Objects.requireNonNull(target, "target");
        if (target.equals(op.actor())) throw refuse(ProfileErrorCode.INVALID_REQUEST, "a member cannot open a vote against itself; leave instead");
        final ProfileMember row = requireMember(profile, target);
        if (row.role() == ProfileRole.OWNER) throw refuse(ProfileErrorCode.OWNER_ACTION_REFUSED, "the OWNER cannot be kicked");
        for (final KickVote open : openVotes) {
            if (open.profileId().equals(profile.id()) && open.undecided() && !open.expired(storeNow)) {
                throw refuse(ProfileErrorCode.VOTE_OPEN, "vote " + open.id() + " against " + open.target() + " is already open");
            }
        }
        requireInactivityRule(profile.members().size() - 1, row, policy, storeNow);
        final KickVote vote = KickVote.open(profile, row, initiator, storeNow, policy.voteLifetimeMillis());
        final List<Expected> before = List.of(Expected.account(op.actor(), actorAccount.revision()),
            Expected.metadata(profile.id(), profile.revision()), Expected.record(vote.id(), CoopWriteGuard.ABSENT));
        final List<Expected> after = List.of(Expected.account(op.actor(), actorAccount.revision()),
            Expected.metadata(profile.id(), profile.revision() + 1L), Expected.record(vote.id(), CoopWriteGuard.ABSENT));
        return new CoopIntentPlan(op, profile.id(), null, before, after, List.of(voteImage(vote)), List.of(), deadlineMillis,
            new Result(vote.id(), null, null, null));
    }

    /**
     * The actor, an eligible voter, approves ({@code approve}) or declines a vote. An approval that completes the
     * electorate makes the vote PASSED (then {@link #kick}); a decline closes it DECLINED. Refuses an expired vote
     * ({@code TIMEOUT}) and a changed electorate ({@code ELECTORATE_CHANGED}): run {@link #cancelVote} for those.
     * Vector: actor {@code A} compared, {@code M} reserved, the vote at its revision; the vote's expiry is an approval's
     * commit deadline.
     */
    public static CoopIntentPlan castVote(final CoopOperation op, final AuroraProfile profile, final KickVote vote,
                                          final PlayerAccount actorAccount, final boolean approve, final long storeNow,
                                          final long deadlineMillis) throws IOException {
        kind(op, CoopOperation.Kind.VOTE_CAST);
        requireCoop(profile);
        requireVote(vote, profile);
        requireAccount(actorAccount, op.actor());
        if (!vote.open()) throw refuse(ProfileErrorCode.VOTE_NOT_FOUND, "vote " + vote.id() + " is " + vote.status());
        if (vote.expired(storeNow)) throw refuse(ProfileErrorCode.TIMEOUT, "vote " + vote.id() + " expired at " + vote.expiresAtMillis() + "; cancel it");
        if (!vote.electorateCurrent(profile.members())) {
            throw refuse(ProfileErrorCode.ELECTORATE_CHANGED, "the members of " + profile.id() + " changed since vote " + vote.id() + " opened");
        }
        final ProfileMember voter = requireMember(profile, op.actor());
        if (!vote.eligible(voter.playerId(), voter.epoch())) {
            throw refuse(ProfileErrorCode.ROLE_FORBIDDEN, op.actor() + " is not an eligible voter of vote " + vote.id());
        }
        final KickVote next = approve ? vote.approved(voter.playerId(), voter.epoch(), storeNow) : vote.closed(KickVote.Status.DECLINED);
        final List<Expected> before = List.of(Expected.account(op.actor(), actorAccount.revision()),
            Expected.metadata(profile.id(), profile.revision()), Expected.record(vote.id(), vote.revision()));
        final List<Expected> after = List.of(Expected.account(op.actor(), actorAccount.revision()),
            Expected.metadata(profile.id(), profile.revision() + 1L), Expected.record(vote.id(), vote.revision()));
        return new CoopIntentPlan(op, profile.id(), null, before, after, List.of(voteImage(next)), List.of(),
            approve ? earliest(deadlineMillis, vote.expiresAtMillis()) : deadlineMillis, new Result(vote.id(), null, null, null));
    }

    /**
     * Closes an undecided vote as {@link KickVote.Status#CANCELLED} (the electorate changed, or an OWNER/MANAGER
     * withdraws it) or {@link KickVote.Status#EXPIRED} (its expiry passed by the store clock). Any current member may
     * record a cancellation the electorate change forces. Vector: actor {@code A} compared, {@code M} reserved, the vote
     * at its revision.
     */
    public static CoopIntentPlan cancelVote(final CoopOperation op, final AuroraProfile profile, final KickVote vote,
                                            final PlayerAccount actorAccount, final KickVote.Status next, final long storeNow,
                                            final long deadlineMillis) throws IOException {
        kind(op, CoopOperation.Kind.VOTE_CANCEL);
        requireVote(vote, profile);
        requireAccount(actorAccount, op.actor());
        requireMember(profile, op.actor());
        if (!vote.undecided()) throw refuse(ProfileErrorCode.VOTE_NOT_FOUND, "vote " + vote.id() + " is " + vote.status());
        switch (next) {
            case EXPIRED -> {
                if (!vote.expired(storeNow)) throw refuse(ProfileErrorCode.INVALID_REQUEST, "vote " + vote.id() + " has not expired");
            }
            case CANCELLED -> {
                if (vote.electorateCurrent(profile.members())) requireRole(profile, op.actor(), ProfileRole.Permission.START_KICK_VOTE);
            }
            default -> throw refuse(ProfileErrorCode.INVALID_REQUEST, "a vote is cancelled as CANCELLED or EXPIRED");
        }
        final KickVote closed = vote.closed(next);
        final List<Expected> before = List.of(Expected.account(op.actor(), actorAccount.revision()),
            Expected.metadata(profile.id(), profile.revision()), Expected.record(vote.id(), vote.revision()));
        final List<Expected> after = List.of(Expected.account(op.actor(), actorAccount.revision()),
            Expected.metadata(profile.id(), profile.revision() + 1L), Expected.record(vote.id(), vote.revision()));
        return new CoopIntentPlan(op, profile.id(), null, before, after, List.of(voteImage(closed)), List.of(), deadlineMillis,
            new Result(vote.id(), null, null, null));
    }

    // ================================================================== bounded listings

    /**
     * One page of a listing: at most {@link ProfileIndexPage#LISTING_PAGE} records and the cursor of the next page
     * ({@code null} when this was the last). A record the index names but the store does not hold is an
     * {@link IOException} (the index and the records disagree; nothing is skipped silently, since a skipped entry
     * would shorten the page and end the listing early).
     */
    public record Page<T>(List<T> items, ProfileIndexPage.Cursor next) {

        public Page {
            items = List.copyOf(items);
        }
    }

    /** The open invites of a profile, oldest first. */
    public static Page<ProfileInvite> invitesByProfile(final ProfileRepository repository, final UUID profileId,
                                                       final ProfileIndexPage.Cursor cursor) throws IOException {
        final ProfileIndexPage page = repository.index(ProfileIndexPage.Kind.INVITES_BY_PROFILE, profileId, null, null, cursor);
        final List<ProfileInvite> items = new ArrayList<>(page.entries().size());
        for (final ProfileIndexPage.Entry entry : page.entries()) items.add(indexed(repository.invite(entry.id()), "invite", entry, page));
        return new Page<>(items, page.cursorAfterLast());
    }

    /** The open invitations of an account, oldest first. */
    public static Page<ProfileInvite> invitesByInvitee(final ProfileRepository repository, final UUID invitee,
                                                       final ProfileIndexPage.Cursor cursor) throws IOException {
        final ProfileIndexPage page = repository.index(ProfileIndexPage.Kind.INVITES_BY_INVITEE, invitee, null, null, cursor);
        final List<ProfileInvite> items = new ArrayList<>(page.entries().size());
        for (final ProfileIndexPage.Entry entry : page.entries()) items.add(indexed(repository.invite(entry.id()), "invite", entry, page));
        return new Page<>(items, page.cursorAfterLast());
    }

    /** The undecided (OPEN or PASSED) kick votes of a profile; at most one by the index bound. */
    public static Page<KickVote> votesByProfile(final ProfileRepository repository, final UUID profileId,
                                                final ProfileIndexPage.Cursor cursor) throws IOException {
        final ProfileIndexPage page = repository.index(ProfileIndexPage.Kind.VOTES_BY_PROFILE, profileId, null, null, cursor);
        final List<KickVote> items = new ArrayList<>(page.entries().size());
        for (final ProfileIndexPage.Entry entry : page.entries()) items.add(indexed(repository.vote(entry.id()), "vote", entry, page));
        return new Page<>(items, page.cursorAfterLast());
    }

    /** The outstanding (not DELIVERED) salvage records of a profile, oldest first; summaries only, no item blobs. */
    public static Page<ProfileSalvage> salvageByProfile(final ProfileRepository repository, final UUID profileId,
                                                        final ProfileIndexPage.Cursor cursor) throws IOException {
        final ProfileIndexPage page = repository.index(ProfileIndexPage.Kind.SALVAGE_BY_PROFILE, profileId, null, null, cursor);
        final List<ProfileSalvage> items = new ArrayList<>(page.entries().size());
        for (final ProfileIndexPage.Entry entry : page.entries()) items.add(indexed(repository.salvage(entry.id()), "salvage record", entry, page));
        return new Page<>(items, page.cursorAfterLast());
    }

    /**
     * The members of a profile with their heads, owner first: the member rows are in the metadata record (at most
     * {@value AuroraProfile#MAX_MEMBERS}), so no index is needed; {@code fromIndex} pages through them in
     * {@link ProfileIndexPage#LISTING_PAGE}s. A member whose stream has no head yet has an empty head.
     */
    public static List<MemberView> members(final ProfileRepository repository, final AuroraProfile profile, final int fromIndex) throws IOException {
        final List<ProfileMember> rows = profile.members();
        final List<MemberView> views = new ArrayList<>();
        for (int i = Math.max(0, fromIndex); i < rows.size() && views.size() < ProfileIndexPage.LISTING_PAGE; i++) {
            final ProfileMember row = rows.get(i);
            views.add(new MemberView(row, repository.memberHead(profile.id(), row.playerId(), row.epoch())));
        }
        return List.copyOf(views);
    }

    /** A member row with its stream head (empty while nothing was published for this epoch). */
    public record MemberView(ProfileMember member, Optional<MemberStateHead> head) {}

    private static <T> T indexed(final Optional<T> record, final String what, final ProfileIndexPage.Entry entry,
                                 final ProfileIndexPage page) throws IOException {
        return record.orElseThrow(() -> new IOException("the " + page.kind() + " index of " + page.resource() + " names " + what + " "
            + entry.id() + " but the store holds no such record; the index is inconsistent and the listing is refused"));
    }

    /**
     * Adds the open invitations {@code inviter} issued (from {@code issuedInvites}, at most
     * {@value #MAX_CLOSED_PER_REMOVAL}) to a removal plan as compare entries with REVOKED after-images.
     */
    private static void revokeInvites(final AuroraProfile profile, final UUID inviter, final List<ProfileInvite> issuedInvites,
                                      final UUID operationId, final List<Expected> before, final List<Expected> after,
                                      final List<Image> images) throws IOException {
        int closed = 0;
        for (final ProfileInvite invite : issuedInvites == null ? List.<ProfileInvite>of() : issuedInvites) {
            requireInvite(invite, profile);
            if (!invite.open() || !invite.inviter().equals(inviter) || closed >= MAX_CLOSED_PER_REMOVAL) continue;
            if (named(before, Expected.record(invite.id(), invite.revision()))) continue;
            before.add(Expected.record(invite.id(), invite.revision()));
            after.add(Expected.record(invite.id(), invite.revision()));
            images.add(inviteImage(invite.closed(ProfileInvite.Status.REVOKED, operationId)));
            closed++;
        }
    }

    /**
     * Adds the profile's undecided kick votes (from {@code openVotes}; {@code executing}, the vote the plan executes,
     * is left out) as compare entries with CANCELLED after-images: the membership change invalidates their electorate.
     */
    private static void cancelVotes(final AuroraProfile profile, final List<KickVote> openVotes, final KickVote executing,
                                    final List<Expected> before, final List<Expected> after, final List<Image> images) throws IOException {
        for (final KickVote vote : openVotes == null ? List.<KickVote>of() : openVotes) {
            requireVote(vote, profile);
            if (!vote.undecided() || (executing != null && executing.id().equals(vote.id()))) continue;
            if (named(before, Expected.record(vote.id(), vote.revision()))) continue;
            before.add(Expected.record(vote.id(), vote.revision()));
            after.add(Expected.record(vote.id(), vote.revision()));
            images.add(voteImage(vote.closed(KickVote.Status.CANCELLED)));
        }
    }

    private static boolean named(final List<Expected> vector, final Expected entry) {
        for (final Expected named : vector) {
            if (named.sameRecord(entry)) return true;
        }
        return false;
    }

    // ================================================================== checks

    private static void kind(final CoopOperation op, final CoopOperation.Kind expected) {
        Objects.requireNonNull(op, "op");
        if (op.kind() != expected) throw new IllegalArgumentException("operation " + op.operationId() + " is " + op.kind() + ", not " + expected);
    }

    private static void requireCoop(final AuroraProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (!profile.coop()) throw refuse(ProfileErrorCode.NOT_COOP, "profile " + profile.id() + " is a solo profile; convert it to co-op first");
    }

    private static void requireUsable(final AuroraProfile profile) {
        Objects.requireNonNull(profile, "profile");
        if (!profile.usable()) throw refuse(ProfileErrorCode.PROFILE_LOCKED, "profile " + profile.id() + " is " + profile.status());
    }

    private static ProfileMember requireMember(final AuroraProfile profile, final UUID player) {
        final ProfileMember row = profile.member(player);
        if (row == null) throw refuse(ProfileErrorCode.NOT_A_MEMBER, player + " is not a member of " + profile.id());
        return row;
    }

    private static ProfileMember requireRole(final AuroraProfile profile, final UUID actor, final ProfileRole.Permission permission) {
        final ProfileMember row = requireMember(profile, actor);
        if (!row.role().allows(permission)) {
            throw refuse(ProfileErrorCode.ROLE_FORBIDDEN, actor + " is " + row.role() + " of " + profile.id() + " and may not " + permission);
        }
        return row;
    }

    private static void requireAccount(final PlayerAccount account, final UUID player) {
        Objects.requireNonNull(account, "account");
        if (!account.playerId().equals(player)) throw new IllegalArgumentException("account " + account.playerId() + " is not " + player + "'s");
    }

    private static void requireInvite(final ProfileInvite invite, final AuroraProfile profile) {
        Objects.requireNonNull(invite, "invite");
        Objects.requireNonNull(profile, "profile");
        if (!invite.profileId().equals(profile.id())) throw refuse(ProfileErrorCode.INVITE_NOT_FOUND, "invite " + invite.id() + " is not for " + profile.id());
    }

    private static void requireVote(final KickVote vote, final AuroraProfile profile) {
        Objects.requireNonNull(vote, "vote");
        Objects.requireNonNull(profile, "profile");
        if (!vote.profileId().equals(profile.id())) throw refuse(ProfileErrorCode.VOTE_NOT_FOUND, "vote " + vote.id() + " is not of " + profile.id());
    }

    /** The co-op cap: members, not open invites, count; checked against the rows of the {@code M} the plan compares. */
    private static void requireSeat(final AuroraProfile profile, final Policy policy) {
        Objects.requireNonNull(policy, "policy");
        if (profile.members().size() >= policy.maxMembers()) {
            throw refuse(ProfileErrorCode.COOP_FULL, "profile " + profile.id() + " has " + profile.members().size() + " of " + policy.maxMembers() + " members");
        }
    }

    /** The two-member rule: with a single eligible voter the target must have been inactive for the policy's period. */
    private static void requireInactivityRule(final int electorate, final ProfileMember target, final Policy policy, final long storeNow) {
        Objects.requireNonNull(policy, "policy");
        if (electorate <= 1 && !target.inactiveFor(policy.inactiveMillis(), storeNow)) {
            throw refuse(ProfileErrorCode.TARGET_NOT_INACTIVE, target.playerId() + " was last seen at " + target.lastSeenAtMillis()
                + "; a two-member co-op removes a member only after " + policy.inactiveMillis() + " ms of inactivity");
        }
    }

    private static long earliest(final long deadline, final long expiry) {
        return deadline <= 0 ? expiry : Math.min(deadline, expiry);
    }

    private static ProfileOperationException refuse(final ProfileErrorCode code, final String message) {
        return new ProfileOperationException(code, message + ". Nothing was written");
    }

    // ================================================================== after-images

    private static Image accountImage(final PlayerAccount account) throws IOException {
        return new Image(ProfileRecords.TYPE_ACCOUNT, account.playerId(), null, null, account.revision(), ProfileRecords.accountPayload2(account));
    }

    private static Image profileImage(final AuroraProfile profile) throws IOException {
        return new Image(ProfileRecords.TYPE_PROFILE, profile.id(), null, null, profile.revision(), ProfileRecords.profilePayload2(profile));
    }

    private static Image inviteImage(final ProfileInvite invite) throws IOException {
        return new Image(ProfileRecords.TYPE_INVITE, invite.id(), null, null, invite.revision(), ProfileRecords.invitePayload2(invite));
    }

    private static Image voteImage(final KickVote vote) throws IOException {
        return new Image(ProfileRecords.TYPE_VOTE, vote.id(), null, null, vote.revision(), ProfileRecords.votePayload2(vote));
    }

    private static Image salvageImage(final ProfileSalvage salvage) throws IOException {
        return new Image(ProfileRecords.TYPE_SALVAGE, salvage.id(), null, null, salvage.revision(), ProfileRecords.salvagePayload2(salvage));
    }

    private static Image headImage(final MemberStateHead head) throws IOException {
        return new Image(ProfileRecords.TYPE_MEMBER_HEAD, head.profileId(), head.memberId(), head.epoch(), head.revision(),
            ProfileRecords.memberHeadPayload2(head));
    }
}
