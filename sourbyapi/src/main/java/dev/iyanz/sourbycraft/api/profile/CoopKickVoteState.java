package dev.iyanz.sourbycraft.api.profile;

/** State of a co-op kick vote (Build 52). Constants are only appended. */
public enum CoopKickVoteState {
    /** Open for votes. */
    OPEN,
    /** Every eligible voter approved; the removal is being committed. */
    PASSED,
    /** The removal committed. */
    EXECUTED,
    /** An eligible voter declined; the vote is closed. */
    DECLINED,
    /** Membership or roles changed while the vote was open, or the target left. A fresh vote is needed. */
    CANCELLED,
    /** Its expiry passed. */
    EXPIRED
}
