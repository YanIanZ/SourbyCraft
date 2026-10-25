package dev.iyanz.sourbycraft.awf.multiprofile;

/**
 * Phases of a profile switch, persisted in the recovery journal before the work of that phase starts.
 *
 * <p>A crash can therefore only ever be observed at a phase boundary. {@code recover} completes the
 * switch forward when the account already names the target, does nothing when the journal never
 * reached {@link #APPLYING_TARGET} (the target state was not applied), and otherwise rolls the player
 * back to the journal's rollback point. The terminal phases are {@link #ACTIVE} and {@link #FAILED}.</p>
 *
 * <p>Journals store the ordinal, so the declaration order below is fixed. A switch runs
 * {@link #PREPARING_TARGET} before {@link #FREEZING} and {@link #SAVING}, so the player is frozen
 * only for the capture, the apply and the commit, not while a world is created.</p>
 */
public enum TransitionPhase {
    /** Validating the player and the target. */
    VALIDATING,
    /** The player is frozen; capturing the old state on its owning entity thread. */
    FREEZING,
    /** The rollback point (the old state's snapshot, or on a first switch the archived state) is durable. */
    SAVING,
    /** Acquiring the target ownership and making its world ready; runs before {@link #FREEZING}. */
    PREPARING_TARGET,
    /** Applying the target state on the player's owning entity thread; from here it may be on the player. */
    APPLYING_TARGET,
    /** Moving the player to the target world. */
    TELEPORTING,
    /** Committing the active-profile reference. */
    COMMITTING,
    /** Finished. A switch removes its journal after the commit instead; earlier builds wrote this phase. */
    ACTIVE,
    /**
     * The target state may be on the player and the rollback has not completed (the player was
     * offline, or the rollback failed or timed out); the next join or {@code recover} rolls it back.
     */
    RECOVERING,
    /**
     * Terminal record: the switch failed before the target state was applied, its rollback completed,
     * or no rollback point exists. It blocks nothing and is listed by {@code /awf multiprofile status}.
     */
    FAILED;

    public boolean terminal() {
        return this == ACTIVE || this == FAILED;
    }
}
