package dev.iyanz.sourbycraft.awf.multiprofile;

/**
 * Phases of a profile switch, persisted in the recovery journal before the work of that phase starts.
 *
 * <p>A crash can therefore only ever be observed at a phase boundary, and {@code recover} can decide
 * whether to roll forward (the old state is still durable) or roll back (the new state was never
 * applied). The terminal phases are {@link #ACTIVE} and {@link #FAILED}.</p>
 */
public enum TransitionPhase {
    /** Validating the player, the target and the requested deadline. */
    VALIDATING,
    /** Capturing the old profile state on its owning entity thread. */
    FREEZING,
    /** Writing the old state as a durable snapshot. */
    SAVING,
    /** Acquiring the target ownership and making its world ready. */
    PREPARING_TARGET,
    /** Applying the target state on the player's owning entity thread. */
    APPLYING_TARGET,
    /** Moving the player to the target world. */
    TELEPORTING,
    /** Committing the active-profile reference. */
    COMMITTING,
    /** Finished. */
    ACTIVE,
    /** Compensating after a failure; the journal is kept until compensation is durable. */
    RECOVERING,
    /** Terminal failure; the journal records why and remains for an operator. */
    FAILED;

    public boolean terminal() {
        return this == ACTIVE || this == FAILED;
    }
}
