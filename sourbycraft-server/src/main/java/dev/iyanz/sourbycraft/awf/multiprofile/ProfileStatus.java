package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;

/**
 * Where a profile is in its lifecycle. A deleted profile keeps its id reserved, never reused.
 *
 * <p>Format-1 records store the ordinal; format-2 records store {@link #tag()}, a number fixed per
 * constant that does not move when constants are reordered or added.</p>
 */
public enum ProfileStatus {
    /** Usable and listed. */
    ACTIVE(1),
    /** Visible but refusing switches (administrative hold). */
    LOCKED(2),
    /** Tombstoned; snapshots and bindings are removed. */
    DELETED(3);

    private final int tag;

    ProfileStatus(final int tag) {
        this.tag = tag;
    }

    /** The stable numeric tag written by format-2 records. */
    public int tag() {
        return this.tag;
    }

    /** The constant for a stored tag; an unknown tag is a corrupt or newer record and is refused. */
    public static ProfileStatus fromTag(final int tag, final String where) throws IOException {
        for (final ProfileStatus status : values()) {
            if (status.tag == tag) return status;
        }
        throw new IOException("profile status tag " + tag + " in " + where + " is not known to this build");
    }
}
