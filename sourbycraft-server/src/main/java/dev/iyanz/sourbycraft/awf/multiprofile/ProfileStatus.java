package dev.iyanz.sourbycraft.awf.multiprofile;

/** Where a profile is in its lifecycle. A deleted profile keeps its id reserved, never reused. */
public enum ProfileStatus {
    /** Usable and listed. */
    ACTIVE,
    /** Visible but refusing switches (administrative hold). */
    LOCKED,
    /** Tombstoned; snapshots and bindings are removed. */
    DELETED
}
