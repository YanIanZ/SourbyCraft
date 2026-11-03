package dev.iyanz.sourbycraft.api.profile;

/** State of a co-op invitation (Build 52). Constants are only appended. */
public enum CoopInviteState {
    /** Open: can be accepted, declined or cancelled until it expires. */
    OPEN,
    /** The invitee joined; the membership is durable. */
    ACCEPTED,
    /** The invitee declined. */
    DECLINED,
    /** An authorised member of the profile cancelled it. */
    CANCELLED,
    /** Its expiry passed. Expiry is judged at commit time against the repository clock. */
    EXPIRED
}
