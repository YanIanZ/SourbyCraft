package dev.iyanz.sourbycraft.api.profile;

/**
 * Whose data a {@link ProfileStateAdapter} stores, as the adapter declares it (Build 53). The server does not
 * inspect the plugin to verify the declaration; it is metadata that operators and the profile diagnostics
 * report, and that {@link ProfileDataCriticality#REQUIRED} decisions are documented against. Constants are only
 * appended.
 */
public enum ProfileDataScope {
    /** The data belongs to the player's account and is the same in every profile; the server does not swap it per profile. */
    ACCOUNT_WIDE,
    /** The data belongs to the profile; the adapter's bytes are captured into and applied from the profile snapshot. */
    PROFILE_SCOPED,
    /** The plugin stores state that cannot be captured per profile; declared so operators know it is shared or unisolated. */
    UNSUPPORTED
}
