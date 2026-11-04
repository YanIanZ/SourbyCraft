package dev.iyanz.sourbycraft.api.profile;

/**
 * A member's role in a co-op profile (Build 52). Constants are only appended.
 *
 * <p>Permissions as Build 52 enforces them at the repository commit, not only in a command:</p>
 * <ul>
 *   <li>{@link #OWNER}: unique, always the profile's account. Converts, invites, cancels invites, starts and
 *       votes in kick votes, changes the shared label. Cannot leave, cannot be kicked, cannot transfer
 *       ownership (all three are refused in Build 52 with {@link ProfileErrorCode#OWNER_ACTION_REFUSED}).</li>
 *   <li>{@link #MANAGER}: invites, cancels the invites it issued, starts kick votes and votes. Cannot
 *       convert, change the label or change roles. Can leave.</li>
 *   <li>{@link #MEMBER}: plays the profile; may leave and may vote in a kick vote it is eligible for. Cannot
 *       invite or start a vote.</li>
 * </ul>
 *
 * <p>Build 52 has no operation that changes a member's role: the owner is {@code OWNER}, an accepted
 * invitee joins as {@code MEMBER}, and {@code MANAGER} is stored and read but not yet assignable.</p>
 */
public enum CoopRole {
    /** The profile's owner. */
    OWNER,
    /** May invite and start kick votes. */
    MANAGER,
    /** Plays the profile. */
    MEMBER
}
