package dev.iyanz.sourbycraft.awf.multiprofile;

/**
 * A proposed member role; not wired yet into the 26.2 profile engine.
 * The intended policy allows only OWNER and MANAGER to invite or start kick votes. Any non-target
 * member would be able to approve or decline a vote, and only OWNER would be able to archive.
 */
public enum ProfileRole {

    /** The unique owner: every permission, including archive and ownership transfer. */
    OWNER,
    /** Invite, kick votes, bank and island management; cannot archive or transfer ownership. */
    MANAGER,
    /** Plays the profile; personal state and shared island access. */
    MEMBER;

    public boolean canInvite() {
        return this != MEMBER;
    }

    /** Permission to initiate a kick vote; approval uses current non-target membership. */
    public boolean canVote() {
        return this != MEMBER;
    }

    public boolean canManageBank() {
        return this != MEMBER;
    }

    public boolean canTransferOwnership() {
        return this == OWNER;
    }

    public boolean canArchive() {
        return this == OWNER;
    }
}
