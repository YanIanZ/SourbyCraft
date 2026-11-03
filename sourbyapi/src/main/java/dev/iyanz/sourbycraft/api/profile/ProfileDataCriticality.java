package dev.iyanz.sourbycraft.api.profile;

/**
 * How much a profile switch (or a quit/shutdown capture) depends on a {@link ProfileStateAdapter} (Build 53).
 * Constants are only appended.
 *
 * <p><b>{@link #REQUIRED}.</b> The adapter's data is part of what makes a profile switch correct, so the
 * server never treats a missing or failed adapter as success:</p>
 * <ul>
 *   <li>its capture must be durable (inside the snapshot the server saved) before the target profile becomes
 *       active for the player;</li>
 *   <li>its apply must be verified (it returned without throwing) before the switch is reported as
 *       successful;</li>
 *   <li>a capture or apply failure refuses the switch ({@link ProfileErrorCode#ADAPTER_REQUIRED_FAILED}), or,
 *       when it happens after the transition journal already holds a target, is journaled for recovery; it is
 *       never skipped and never reported as success;</li>
 *   <li>an adapter that is registered as REQUIRED for a profile's stored data but is not present on this node
 *       refuses the switch with {@link ProfileErrorCode#ADAPTER_MISSING}.</li>
 * </ul>
 *
 * <p><b>{@link #BEST_EFFORT}.</b> The Build 52 behaviour: a capture or apply that fails, returns nothing or
 * exceeds a limit is skipped for that operation with a diagnostic in the log, and the switch continues. The
 * opaque bytes the profile already stores for that adapter are preserved (they are not replaced by nothing),
 * within the snapshot limits.</p>
 */
public enum ProfileDataCriticality {
    /** A failure or absence refuses the switch or is journaled for recovery; a skip is never success. */
    REQUIRED,
    /** A failure may be skipped with a diagnostic; prior opaque data is preserved. */
    BEST_EFFORT
}
