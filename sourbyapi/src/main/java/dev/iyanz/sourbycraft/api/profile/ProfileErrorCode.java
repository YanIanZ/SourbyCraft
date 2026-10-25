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
    INTERNAL_ERROR
}
