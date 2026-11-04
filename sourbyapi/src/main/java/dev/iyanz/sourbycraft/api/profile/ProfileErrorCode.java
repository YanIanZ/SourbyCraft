package dev.iyanz.sourbycraft.api.profile;

/** Stable error codes of {@link AuroraProfiles}; an API consumer may switch on these. */
public enum ProfileErrorCode {
    /** AWF MultiProfile is not enabled on this server. */
    DISABLED,
    /** Switching is disabled by configuration. */
    SWITCHING_DISABLED,
    /** The player is not online (or was removed before the state could be read). */
    PLAYER_OFFLINE,
    /** No such profile. */
    PROFILE_NOT_FOUND,
    /** The profile exists but belongs to another account. */
    NOT_OWNED,
    /** The account has no free profile slot. */
    SLOT_LIMIT,
    /** The profile is locked or deleted. */
    PROFILE_LOCKED,
    /** Another switch for this player is already running. */
    SWITCH_IN_PROGRESS,
    /** The stored state is older than the profile revision; applying it would lose progress. */
    STALE_STATE,
    /** Another node holds (or took over) the profile ownership. */
    OWNERSHIP_CONFLICT,
    /** The profile's AWF world is not available. */
    WORLD_UNAVAILABLE,
    /** The switch exceeded its configured deadline and was rolled back. */
    TIMEOUT,
    /** A previous transition is incomplete; recovery must run first. */
    RECOVERY_REQUIRED,
    /** Recovery finished by rolling the player back to the previous profile; nothing is pending. */
    RECOVERED,
    /** A durable write failed. */
    IO_FAILURE,
    /** The request was invalid (bad type, missing field, too much metadata). */
    INVALID_REQUEST,
    /** The player's state exceeds the 8 MiB snapshot limit; nothing was stored. */
    STATE_TOO_LARGE,
    /** An unexpected failure; the transition journal has the detail. */
    INTERNAL_ERROR,
    /**
     * Added in Build 51 (constants are only appended, so existing ordinals are unchanged). The requested
     * profile name is already used by another profile of the account (compared ignoring case).
     */
    NAME_TAKEN,
    /** The requested profile name is not acceptable under the server's {@code names.policy}. */
    NAME_INVALID,
    /** The requested policy type is not in the server's {@code types.allowed}. */
    TYPE_NOT_ALLOWED,
    /**
     * The profile store is in a record format this build refuses (for example a format newer than the
     * build understands). Nothing was written. A store in format 2 cannot be used by a build older than
     * Build 51; the way back is restoring the pre-migration backup, not a downgrade.
     */
    STORE_FORMAT_REFUSED,
    /**
     * The store is still in the pre-Build-51 format 1 and the operation needs format 2. Set
     * {@code migration.mode} to {@code apply} (with a {@code migration.backup-dir}) and restart; see
     * the operations guide. Nothing was written.
     */
    MIGRATION_REQUIRED,
    /**
     * Added in Build 51. A revision, fence or session counter of the profile store reached its upper
     * bound ({@code 2^53 - 1}); the operation was refused and nothing was written. Counters are never
     * reset or wrapped, so the store needs an offline migration before this record can change again.
     */
    COUNTER_EXHAUSTED,
    /**
     * Added in Build 51. A claim the server held for this operation (the player's session claim or a
     * short profile transaction claim) is confirmed lost: it lapsed or another session took it over.
     * Nothing was written under it. A lost player session also freezes that player until they rejoin.
     */
    CLAIM_LOST,
    /**
     * Added in Build 51. The store did not confirm whether a write was applied (a lost reply, an
     * unconfirmed durability wait, or a failure after the commit point), and reading the store back
     * did not settle it either. Nothing was rolled back or retried; the operation must not be
     * repeated blindly. A pending switch journal or intent records it for recovery.
     */
    UNKNOWN_OUTCOME,
    /**
     * Added in Build 51. A bound of the profile store is reached: too many operations are pending for
     * this account, profile or store, a resource holds its maximum of permanent receipts, or an index
     * (invites, votes, salvage) is full. The operation was refused and nothing was written. Pending
     * operations resolve on their own or through recovery; retry afterwards, do not retry in a loop.
     */
    CAPACITY_EXCEEDED,
    /**
     * Added in Build 52. Co-op is disabled ({@code aurora.awf.multiprofile.coop.enabled} is false, the default),
     * so every co-op operation is refused. Nothing was written.
     */
    COOP_DISABLED,
    /** Added in Build 52. The player is not a member of the co-op profile (or the profile is not co-op for them). */
    NOT_A_MEMBER,
    /** Added in Build 52. The invitee is already a member of the profile. */
    ALREADY_MEMBER,
    /** Added in Build 52. The co-op already has {@code coop.max-members} members (open invites do not count, accepting does). */
    COOP_FULL,
    /** Added in Build 52. The invitation expired; expiry is judged at commit time against the repository clock. */
    INVITE_EXPIRED,
    /** Added in Build 52. No such invitation, or it is not open (accepted, declined, cancelled) or not for this player. */
    INVITE_NOT_FOUND,
    /** Added in Build 52. The actor's role does not allow the operation (for example a MEMBER inviting). */
    ROLE_FORBIDDEN,
    /**
     * Added in Build 52. The member to remove (leave or kick) is online. Build 52 refuses it with this code. From
     * Build 53 an online member is captured and switched out first, so this code is returned only when that
     * cannot be done safely (for example the member is online on another node). Nothing was written.
     */
    MEMBER_ONLINE,
    /**
     * Added in Build 52. Owner transfer, the owner leaving, or kicking the owner: refused in Build 52.
     */
    OWNER_ACTION_REFUSED,
    /**
     * Added in Build 52 (OD-1). The profile's bound world is held by another node; a co-op membership change
     * that joins the profile must run on the node that holds it. Nothing was written.
     */
    WRONG_NODE,
    /** Added in Build 52. No such kick vote, or it is not open. */
    VOTE_NOT_FOUND,
    /**
     * Added in Build 52. Membership or roles changed since the vote opened (or since the caller read it); the vote
     * is cancelled and a fresh vote is needed.
     */
    ELECTORATE_CHANGED,
    /**
     * Added in Build 52. A two-member co-op can remove the other member only after that member has been inactive for
     * {@code coop.kick-vote.inactive-days}.
     */
    TARGET_NOT_INACTIVE,
    /** Added in Build 52. The operation needs a co-op profile and this one is a solo profile. */
    NOT_COOP,
    /** Added in Build 52. The profile is already a co-op profile. */
    ALREADY_COOP,
    /** Added in Build 52. A kick vote is already open for this profile (one open vote per profile). */
    VOTE_OPEN,
    /** Added in Build 52. An open invitation for this invitee and profile exists already. */
    INVITE_EXISTS,
    /**
     * Added in Build 52. Switching into a co-op profile the player does not own was not available in Build 52.
     * Build 53 allows it for a member (when co-op is enabled and the profile's world is on this node), so a member
     * no longer receives this code; it is kept so existing consumers still compile and for a non-member, which is
     * answered with {@link #NOT_A_MEMBER}.
     */
    COOP_SWITCH_UNAVAILABLE,
    /**
     * Added in Build 53. A {@link ProfileDataCriticality#REQUIRED} {@link ProfileStateAdapter} failed to capture
     * or apply (it threw, exceeded its size bound or the snapshot budget). The switch was refused or rolled back
     * to the previous profile; nothing was skipped. When the failure came after the journal held a target, the
     * journal records it for recovery.
     */
    ADAPTER_REQUIRED_FAILED,
    /**
     * Added in Build 53. A {@link ProfileDataCriticality#REQUIRED} adapter that holds data for the profile is not
     * registered on this node (its plugin is absent, disabled or at another version), so the data cannot be
     * captured or applied safely. The switch was refused and nothing was written. Load the plugin and retry.
     */
    ADAPTER_MISSING,
    /**
     * Added in Build 53. Authoritative login could not read the member's committed state head for the active
     * profile (the store is unreadable or the head is damaged). The join is refused with this reason rather than
     * loading vanilla player data. Nothing was written.
     */
    HEAD_UNREADABLE,
    /**
     * Added in Build 54. The profile's type ({@link ProfileTypeDefinition}) denies the action (for example
     * {@code bank.deposit} on an Ironman profile), or the profile's type has no known definition on this server
     * and the action is engine-owned (fail-safe: see {@code types.unknown-type-policy}). The message names the
     * type and the action. Nothing was written.
     */
    ACTION_FORBIDDEN_BY_TYPE,
    /**
     * Added in Build 54. The requested profile type id is not registered ({@link ProfileTypeRegistry}) and is not
     * a built-in type. Nothing was written.
     */
    TYPE_UNKNOWN,
    /**
     * Added in Build 54 (owner decision OD-4). A transfer named the purse. The purse stays per profile through its
     * REQUIRED state adapter and the server has no transactional purse provider, so a purse&lt;-&gt;bank transfer is
     * refused. The consumer plugin that holds the purse moves that money on its side and credits or debits the bank
     * ledger through {@link AuroraProfiles#deposit} / {@link AuroraProfiles#withdraw}. Nothing was written.
     */
    PURSE_TRANSFER_UNSUPPORTED,
    /**
     * Added in Build 55. The player committed a switch less than {@code switching.cooldown-seconds} ago; the message
     * names the remaining seconds. Only a committed switch starts the cooldown (a refused, vetoed or failed one does
     * not). Nothing was written.
     */
    SWITCH_COOLDOWN,
    /**
     * Added in Build 55. A registered {@link ProfileSwitchGuard} refused the switch (or threw, or did not answer in
     * time); the message carries the guard's reason. The player's state was not touched and nothing was written.
     */
    SWITCH_VETOED,
    /**
     * Added in Build 55. The profile is archived (read-only: a seasonal profile after its event, or an operator's
     * {@code /awf multiprofile archive}): it can be listed and read, but not switched into, changed, converted,
     * joined or used for bank, collection or salvage operations until an operator unarchives it. A member may still
     * {@linkplain AuroraProfiles#leave leave} an archived co-op. Nothing was written.
     */
    PROFILE_ARCHIVED
}
