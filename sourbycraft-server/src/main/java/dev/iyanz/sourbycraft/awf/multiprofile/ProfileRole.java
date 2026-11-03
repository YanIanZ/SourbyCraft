package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;

/**
 * A member's role in a profile, stored in the profile's member rows (format 2, record type 2).
 *
 * <p>Build 51 stores roles and reads them back; no command or API changes them yet (membership
 * operations are Build 52). The permission methods express the intended policy that those later
 * operations check at the repository commit: OWNER and MANAGER invite and start kick votes, only
 * OWNER changes roles, the unique OWNER cannot leave, be kicked or transfer ownership in the planned
 * slices.</p>
 */
public enum ProfileRole {

    /** The unique owner, whose player id equals the profile's account id. */
    OWNER(1),
    /** Invites, starts kick votes, manages the bank; cannot archive or change roles. */
    MANAGER(2),
    /** Plays the profile: personal state and shared island access. */
    MEMBER(3);

    private final int tag;

    ProfileRole(final int tag) {
        this.tag = tag;
    }

    /** The stable numeric tag written by format-2 records. */
    public int tag() {
        return this.tag;
    }

    public static ProfileRole fromTag(final int tag, final String where) throws IOException {
        for (final ProfileRole role : values()) {
            if (role.tag == tag) return role;
        }
        throw new IOException("member role tag " + tag + " in " + where + " is not known to this build");
    }

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

    /** Only the owner changes roles. */
    public boolean canChangeRoles() {
        return this == OWNER;
    }

    public boolean canTransferOwnership() {
        return this == OWNER;
    }

    public boolean canArchive() {
        return this == OWNER;
    }
}
