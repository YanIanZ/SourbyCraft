package dev.iyanz.sourbycraft.api.profile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * AWF MultiProfile: durable, isolated player profiles backed by Aurora World Fabric worlds.
 *
 * <p>Obtain it from Bukkit's services manager:</p>
 * <pre>{@code
 * AuroraProfiles profiles = Bukkit.getServicesManager().load(AuroraProfiles.class);
 * }</pre>
 *
 * <p>Every operation that persists or moves a player is asynchronous. Completion callbacks have no
 * guaranteed thread; never join a future on a region, entity or global-tick thread. A switch is
 * rejected while another switch for the same player is running, and it is recoverable: an incomplete
 * transition is recorded and can be finished with an operator command or at the player's next join.</p>
 *
 * <p>Build 51 adds the profile's immutable name and policy type ({@link ProfileDetails},
 * {@link ProfileType}), which are stored but not yet used to restrict any gameplay.</p>
 *
 * <p><b>Co-op (Build 52).</b> The co-op methods at the end of this interface manage membership of a
 * profile shared by several accounts: convert a solo profile, invite, accept, decline, cancel, leave,
 * vote-kick, list and a shared label. They are {@code default} methods that fail with
 * {@link UnsupportedOperationException} on an implementation that does not provide them. They are refused
 * with {@link ProfileErrorCode#COOP_DISABLED} unless {@code aurora.awf.multiprofile.coop.enabled} is true
 * (default false), and with {@link ProfileErrorCode#MIGRATION_REQUIRED} on a store still in format 1.
 * Ownership cannot be transferred and the owner cannot leave or be kicked
 * ({@link ProfileErrorCode#OWNER_ACTION_REFUSED}); a removed member's state is recorded for salvage but
 * nothing is delivered (Build 54). Every membership change is a recoverable intent: a failed or
 * {@link ProfileErrorCode#UNKNOWN_OUTCOME} call must be re-read, not
 * blindly repeated. The {@code actor} and {@code player} arguments identify who asks; like the other
 * mutators, the plugin must authorise player requests itself, and the service checks the actor's role at
 * commit.</p>
 *
 * <p><b>Co-op play (Build 53).</b> A member can {@linkplain #switchProfile switch into} the co-op profile and
 * play it: each member has their own state in it (inventory, ender chest, experience, health, location and the
 * registered adapters), captured and restored per member, never shared between members. The switch is refused
 * with {@link ProfileErrorCode#NOT_A_MEMBER} for a player who is not a member (a removed member included) and
 * {@link ProfileErrorCode#WRONG_NODE} when the
 * profile's bound world is held by another node (OD-1: no cross-node routing). A member who is online with the
 * co-op as their active profile can now leave or be kicked: the server first captures and switches them out
 * (to their next profile, or a fresh solo profile) and only then removes them; if that cannot be done safely
 * the call fails with {@link ProfileErrorCode#MEMBER_ONLINE} and nothing is removed.</p>
 *
 * <p><b>Adapters (Build 53).</b> {@link ProfileStateAdapter#descriptor()} declares an adapter
 * {@link ProfileDataCriticality#REQUIRED} or {@link ProfileDataCriticality#BEST_EFFORT}; a REQUIRED adapter that
 * fails or is missing refuses a switch with {@link ProfileErrorCode#ADAPTER_REQUIRED_FAILED} or
 * {@link ProfileErrorCode#ADAPTER_MISSING}. An adapter may also implement {@link ProfileStateAdapter#migrate} to
 * convert data it stored under an older version; a REQUIRED adapter that cannot migrate refuses the apply.</p>
 *
 * <p><b>Authoritative login (Build 53).</b> {@code aurora.awf.multiprofile.login.authoritative} is {@code auto}
 * (default), {@code true} or {@code false}; {@code auto} means authoritative exactly when {@code coop.enabled} is
 * true. While it applies, a join restores the committed member state of the player's active profile instead of the
 * vanilla playerdata the server loaded, the server publishes a profile snapshot every time it saves that player's
 * vanilla playerdata (autosave) and at quit, and a join whose committed state cannot be read or restored is refused
 * ({@link ProfileErrorCode#HEAD_UNREADABLE} and the other codes the server reports). A join after a crash or an
 * upgrade from Build 52 may adopt the player's vanilla playerdata into the active profile once (see the server's
 * {@code docs/awf/multiprofile/PROFILE-LIFECYCLE.md}). While it does not apply, a join keeps the vanilla
 * playerdata, exactly as in Build 52, and co-op members cannot switch into a co-op
 * ({@link ProfileErrorCode#COOP_SWITCH_UNAVAILABLE}).</p>
 *
 * <p>The service is optional: plugins must handle {@code isEnabled() == false} and a missing service
 * registration, which is what a server without MultiProfile looks like.</p>
 */
@NullMarked
public interface AuroraProfiles {

    /** Whether the feature is enabled and ready. */
    boolean isEnabled();

    /**
     * Creates a profile for the account. Fails with {@link ProfileErrorCode#SLOT_LIMIT} when the account
     * has no free slot. Since Build 51 the request may also carry a {@linkplain ProfileType policy type}
     * and a name; they fail with {@link ProfileErrorCode#TYPE_NOT_ALLOWED}, {@link ProfileErrorCode#NAME_INVALID}
     * or {@link ProfileErrorCode#NAME_TAKEN}. The name and type of the created profile are available from
     * {@link #getProfileDetails(UUID)}; the returned {@link ProfileInfo} is unchanged.
     */
    CompletableFuture<ProfileInfo> createProfile(ProfileRequest request);

    /** Every profile of the account, in creation order. */
    CompletableFuture<List<ProfileInfo>> listProfiles(UUID accountId);

    CompletableFuture<Optional<ProfileInfo>> getProfile(UUID profileId);

    /**
     * The profile with its name and policy type (Build 51). Completes with an empty optional when the
     * profile does not exist. Default failure preserves compatibility with alternative service
     * implementations built before this API was added.
     */
    default CompletableFuture<Optional<ProfileDetails>> getProfileDetails(UUID profileId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("profile details unavailable"));
    }

    /**
     * Every profile of the account with its name and policy type, in creation order (Build 51). Default
     * failure preserves compatibility with alternative service implementations built before this API was
     * added.
     */
    default CompletableFuture<List<ProfileDetails>> listProfileDetails(UUID accountId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("profile details unavailable"));
    }

    /** Marks the profile deleted and removes its snapshots and bindings; never deletes AWF worlds. */
    CompletableFuture<Boolean> deleteProfile(UUID profileId);

    CompletableFuture<Optional<ProfileInfo>> getActiveProfile(UUID accountId);

    /**
     * Switches the player's active profile; the result carries the reason on failure. Since Build 53 the
     * target may be a co-op profile the player is a member of (see the class description); refusals added for
     * play are {@link ProfileErrorCode#NOT_A_MEMBER}, {@link ProfileErrorCode#WRONG_NODE},
     * {@link ProfileErrorCode#ADAPTER_REQUIRED_FAILED} and {@link ProfileErrorCode#ADAPTER_MISSING}.
     */
    CompletableFuture<ProfileSwitchResult> switchProfile(UUID playerId, UUID profileId);

    /** Captures and persists the profile's current state, if its owner is online. */
    CompletableFuture<Void> saveProfile(UUID profileId);

    /**
     * Replaces the complete binding of an inactive, unclaimed profile. Refused during an account
     * transition or for the active/deleted profile. Does not delete or immediately load worlds.
     * The plugin must authorize player requests itself. Default failure preserves compatibility
     * with alternative service implementations built before this API was added.
     */
    default CompletableFuture<Void> bindProfileWorlds(ProfileWorldBindingRequest request) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("profile world binding unavailable"));
    }

    CompletableFuture<List<ProfileWorldRef>> getProfileWorlds(UUID profileId);

    /**
     * Puts an inactive profile on administrative hold ({@code locked}: still listed, switching to it is
     * refused) or releases it. The active profile cannot be locked, and the call is refused during an
     * account transition or while a transition journal of the account is pending. The plugin must
     * authorize player requests itself. Default failure preserves compatibility with alternative service
     * implementations built before this API was added.
     */
    default CompletableFuture<Void> setProfileLocked(UUID profileId, boolean locked) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("profile locking unavailable"));
    }

    /**
     * Sets how many profiles an account may own, between 1 and the server's {@code max-slots}; completes
     * with the new limit. Profiles beyond a lowered limit are kept, only creating more is refused. Default
     * failure preserves compatibility with alternative service implementations built before this API was
     * added.
     */
    default CompletableFuture<Integer> setProfileSlotLimit(UUID accountId, int slots) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("profile slot limits unavailable"));
    }

    /** Which node owns the profile, if any. */
    CompletableFuture<Optional<ProfileOwner>> resolveProfileOwnership(UUID profileId);

    /**
     * Registers the plugin's adapter, or replaces the one that plugin registered under the same id.
     * {@link ProfileStateAdapter#version()} is read once, here; a plugin that changes it registers again.
     *
     * @throws IllegalArgumentException when the id is malformed, its {@code id:version} key is longer
     *     than 64 characters, the version is below 1, another enabled plugin registered the id, or 32
     *     adapters are registered already and the id is not one of them
     * @throws ProfileOperationException with {@link ProfileErrorCode#INVALID_REQUEST} when the plugin is
     *     not enabled
     */
    void registerProfileStateAdapter(Plugin plugin, ProfileStateAdapter adapter);

    void unregisterProfileStateAdapter(Plugin plugin, String adapterId);

    // ---- Co-op membership (Build 52) ----

    /**
     * The co-op view of a profile: label, members and the configured size cap. Completes with an empty optional
     * when the profile does not exist or is a solo profile. Fails with {@link ProfileErrorCode#COOP_DISABLED}
     * when co-op is disabled. Build 52.
     */
    default CompletableFuture<Optional<CoopDetails>> getCoopDetails(UUID profileId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("co-op unavailable"));
    }

    /**
     * Converts the actor's solo profile to co-op (one way: a co-op profile never becomes solo again). The actor
     * must be the profile's owner. Refused with {@link ProfileErrorCode#TYPE_NOT_ALLOWED} when the profile's
     * {@linkplain ProfileType policy type} is not in {@code coop.allowed-types},
     * {@link ProfileErrorCode#ALREADY_COOP}, {@link ProfileErrorCode#PROFILE_LOCKED} or
     * {@link ProfileErrorCode#NOT_OWNED}. Build 52.
     */
    default CompletableFuture<CoopDetails> convertToCoop(UUID actorId, UUID profileId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("co-op unavailable"));
    }

    /**
     * Invites an account to a co-op profile. The actor must be the owner or a manager. Fails with
     * {@link ProfileErrorCode#ROLE_FORBIDDEN}, {@link ProfileErrorCode#ALREADY_MEMBER},
     * {@link ProfileErrorCode#INVITE_EXISTS}, {@link ProfileErrorCode#COOP_FULL},
     * {@link ProfileErrorCode#NOT_COOP}, {@link ProfileErrorCode#CAPACITY_EXCEEDED} (open-invite bounds) or
     * {@link ProfileErrorCode#INVALID_REQUEST} (inviting oneself). The invitation expires
     * {@code coop.invite-expiry-minutes} after issue. Fires {@link CoopInviteEvent} after the commit. Build 52.
     */
    default CompletableFuture<CoopInviteInfo> invite(UUID actorId, UUID profileId, UUID inviteeId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("co-op unavailable"));
    }

    /**
     * Accepts an invitation addressed to {@code playerId}, which makes the player a {@link CoopRole#MEMBER}.
     * One commit consumes the invitation, creates the membership and gives the account access; a repeat with
     * the same invitation after it committed returns the same membership. Fails with
     * {@link ProfileErrorCode#INVITE_NOT_FOUND}, {@link ProfileErrorCode#INVITE_EXPIRED} (judged at commit),
     * {@link ProfileErrorCode#COOP_FULL}, {@link ProfileErrorCode#SLOT_LIMIT} (a co-op membership uses one of
     * the account's profile slots), {@link ProfileErrorCode#WRONG_NODE} (OD-1: the node does not hold the
     * profile's bound world). Fires {@link CoopMemberJoinEvent}.
     * From Build 53 the new member can switch into the profile ({@link #switchProfile}).
     */
    default CompletableFuture<CoopMemberInfo> acceptInvite(UUID playerId, UUID inviteId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("co-op unavailable"));
    }

    /** Declines an invitation addressed to {@code playerId}. Fails with {@link ProfileErrorCode#INVITE_NOT_FOUND}. Build 52. */
    default CompletableFuture<Void> declineInvite(UUID playerId, UUID inviteId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("co-op unavailable"));
    }

    /**
     * Cancels an open invitation. The owner may cancel any invitation of the profile, a manager only those it
     * issued. Fails with {@link ProfileErrorCode#INVITE_NOT_FOUND} or {@link ProfileErrorCode#ROLE_FORBIDDEN}.
     * Build 52.
     */
    default CompletableFuture<Void> cancelInvite(UUID actorId, UUID inviteId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("co-op unavailable"));
    }

    /**
     * Open invitations addressed to the player, oldest first, one {@linkplain CoopPage page} at a time (pass a
     * {@code null} cursor for the first). Expired invitations are not listed. Build 52.
     */
    default CompletableFuture<CoopPage<CoopInviteInfo>> listInvitesFor(UUID playerId, @Nullable String cursor) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("co-op unavailable"));
    }

    /**
     * Open invitations of a co-op profile, oldest first. The actor must be the owner or a manager. Build 52.
     */
    default CompletableFuture<CoopPage<CoopInviteInfo>> listInvites(UUID actorId, UUID profileId,
                                                                    @Nullable String cursor) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("co-op unavailable"));
    }

    /**
     * The player leaves a co-op profile. The owner cannot leave
     * ({@link ProfileErrorCode#OWNER_ACTION_REFUSED}); a player whose <i>active</i> profile is this
     * co-op profile is captured and switched out first (Build 53; in Build 52 this was refused with
     * {@link ProfileErrorCode#MEMBER_ONLINE}, which Build 53 returns only when that cannot be done safely, for
     * example when the player is online on another node). A player who is online but active in another profile,
     * or offline, can leave. Committing the leave consumes the membership epoch durably, frees the account's
     * slot and records the member's personal state as an unavailable-to-delivery salvage entry. Fires
     * {@link CoopMemberLeaveEvent} with {@link CoopMemberLeaveEvent.Reason#LEFT}. Build 52.
     */
    default CompletableFuture<Void> leave(UUID playerId, UUID profileId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("co-op unavailable"));
    }

    /**
     * Opens a kick vote against a member. The actor must be the owner or a manager and may not be the target; the
     * target may not be the owner ({@link ProfileErrorCode#OWNER_ACTION_REFUSED}) and, from Build 53, may have
     * this co-op profile as its active profile: the removal first captures and switches it out
     * ({@link ProfileErrorCode#MEMBER_ONLINE} when that cannot be done safely, for example on another node). One vote can be open per profile
     * ({@link ProfileErrorCode#VOTE_OPEN}); in a two-member co-op the target must have been inactive for
     * {@code coop.kick-vote.inactive-days} ({@link ProfileErrorCode#TARGET_NOT_INACTIVE}). The starter's
     * approval is counted. If that already makes the vote unanimous (the owner started it against the only
     * other member) the removal commits in the same call; if that removal fails the call fails, the vote stays
     * {@link CoopKickVoteState#PASSED} and {@link #voteKick} with {@code true} retries it. Build 52.
     */
    default CompletableFuture<CoopKickVoteInfo> startKickVote(UUID actorId, UUID profileId, UUID targetId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("co-op unavailable"));
    }

    /**
     * Casts a vote in an open kick vote: {@code true} approves, {@code false} declines and closes the vote
     * ({@link CoopKickVoteState#DECLINED}). The voter must be in the vote's frozen electorate; a membership or
     * role change since the vote opened cancels it ({@link ProfileErrorCode#ELECTORATE_CHANGED}). Fails with
     * {@link ProfileErrorCode#VOTE_NOT_FOUND} (also for an expired vote, which is closed),
     * {@link ProfileErrorCode#NOT_A_MEMBER} or {@link ProfileErrorCode#MEMBER_ONLINE} (the approval would remove
     * a target that cannot be switched out safely, for example one online on another node). When the last eligible voter approves, the removal commits in
     * the same call and {@link CoopMemberLeaveEvent} fires with {@link CoopMemberLeaveEvent.Reason#KICKED};
     * approving a {@link CoopKickVoteState#PASSED} vote retries a removal that failed.
     * Build 52.
     */
    default CompletableFuture<CoopKickVoteInfo> voteKick(UUID voterId, UUID voteId, boolean approve) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("co-op unavailable"));
    }

    /** The open kick vote of a profile, if any (an expired vote is not open). Build 52. */
    default CompletableFuture<Optional<CoopKickVoteInfo>> getOpenKickVote(UUID profileId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("co-op unavailable"));
    }

    /**
     * The members of a co-op profile, the owner first and then by join time, one {@linkplain CoopPage page} at a
     * time. Build 52.
     */
    default CompletableFuture<CoopPage<CoopMemberInfo>> listMembers(UUID profileId, @Nullable String cursor) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("co-op unavailable"));
    }

    /**
     * Sets the shared display label of a co-op profile (1 to 32 characters, not required to be unique). Only the
     * owner may set it. It is separate from the profile's immutable name and from each member's persisted alias:
     * it changes neither. Fails with {@link ProfileErrorCode#ROLE_FORBIDDEN} or
     * {@link ProfileErrorCode#NAME_INVALID}. Build 52.
     */
    default CompletableFuture<Void> setCoopLabel(UUID actorId, UUID profileId, String label) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("co-op unavailable"));
    }
}
