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
    CAPACITY_EXCEEDED
}
