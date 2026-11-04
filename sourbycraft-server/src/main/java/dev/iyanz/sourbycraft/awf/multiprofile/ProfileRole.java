package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;

/**
 * A member's role in a profile, stored in the profile's member rows (format 2, record type 2), and the
 * permission matrix co-op operations are checked against.
 *
 * <p>The matrix ({@link #allows}) is the repository-side policy of Build 52: OWNER and MANAGER invite,
 * accept join requests (no request flow exists yet; the permission is defined so a later build adds no
 * new role semantics) and open kick votes; every current member casts in a vote it is eligible for;
 * only the OWNER changes the shared display label, converts the profile to co-op, changes roles or
 * deletes the profile; a MANAGER or MEMBER may leave, the unique OWNER may not (owner leave, owner kick
 * and ownership transfer are refused in this port). The permission check happens in the operation
 * builder ({@link CoopMembership}) against the member rows of the {@code M} revision the operation's
 * guard compares at commit, so a role change that lands in between makes the operation stale instead
 * of letting it through under the old role.</p>
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

    /** Whether this role may perform {@code permission}; the single source of the matrix. */
    public boolean allows(final Permission permission) {
        return switch (permission) {
            case INVITE, CANCEL_INVITE, ACCEPT_REQUEST, START_KICK_VOTE, MANAGE_BANK -> this != MEMBER;
            case CAST_KICK_VOTE -> true;
            case CHANGE_LABEL, CONVERT_TO_COOP, CHANGE_ROLES, TRANSFER_OWNERSHIP, DELETE_PROFILE -> this == OWNER;
            case LEAVE -> this != OWNER;
        };
    }

    public boolean canInvite() {
        return allows(Permission.INVITE);
    }

    /** Permission to initiate a kick vote; approval uses current non-target membership. */
    public boolean canVote() {
        return allows(Permission.START_KICK_VOTE);
    }

    public boolean canManageBank() {
        return allows(Permission.MANAGE_BANK);
    }

    /** Only the owner changes roles. */
    public boolean canChangeRoles() {
        return allows(Permission.CHANGE_ROLES);
    }

    public boolean canTransferOwnership() {
        return allows(Permission.TRANSFER_OWNERSHIP);
    }

    public boolean canArchive() {
        return allows(Permission.DELETE_PROFILE);
    }

    /** Only the owner changes the shared display label; aliases are each account's own. */
    public boolean canChangeLabel() {
        return allows(Permission.CHANGE_LABEL);
    }

    /** MANAGER and MEMBER leave; the unique OWNER does not (it deletes the profile instead). */
    public boolean canLeave() {
        return allows(Permission.LEAVE);
    }

    /**
     * Co-op permissions. Destructive ones (roles, ownership, deletion, conversion) are OWNER only;
     * {@link #TRANSFER_OWNERSHIP} and {@link #CHANGE_ROLES} are defined but no Build 52 operation grants
     * them (owner transfer is refused, role changes are a later build).
     */
    public enum Permission {
        INVITE,
        /** Revoke an open invite of this profile (the inviter may also revoke its own). */
        CANCEL_INVITE,
        /** Accept a join request (no request flow in Build 52). */
        ACCEPT_REQUEST,
        START_KICK_VOTE,
        /** Cast in a kick vote one is an eligible voter of. */
        CAST_KICK_VOTE,
        CHANGE_LABEL,
        CONVERT_TO_COOP,
        MANAGE_BANK,
        CHANGE_ROLES,
        TRANSFER_OWNERSHIP,
        DELETE_PROFILE,
        LEAVE
    }
}
