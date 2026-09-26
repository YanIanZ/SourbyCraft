package dev.iyanz.sourbycraft.awf;

/**
 * The roles an Aurora World Fabric world can have, from
 * {@code docs/architecture/aurora-world-fabric.md}.
 *
 * <p>Only the vocabulary and its write rules exist so far; no world is created, loaded or
 * registered with a role yet.</p>
 */
public enum WorldRole {
    /** An ordinary vanilla-format world. */
    VANILLA(true, true),
    /** Registered from header, metadata and chunk index; chunks materialise on demand. */
    VIRTUAL(true, true),
    /** Immutable source shared by instances; never written after creation. */
    TEMPLATE(false, true),
    /** Copy-on-write child of a template; a chunk becomes instance-owned on first mutation. */
    INSTANCE(true, true),
    /** Loaded for reading only. */
    READ_ONLY(false, true),
    /** Mutable but never persisted. */
    TEMPORARY(true, false);

    private final boolean mutable;
    private final boolean persistent;

    WorldRole(final boolean mutable, final boolean persistent) {
        this.mutable = mutable;
        this.persistent = persistent;
    }

    /** Whether gameplay may mutate this world's chunks. */
    public boolean mutable() {
        return this.mutable;
    }

    /** Whether this world's state is ever committed to a backend. */
    public boolean persistent() {
        return this.persistent;
    }

    /** Whether a commit of a new generation is permitted for this role. */
    public boolean acceptsCommits() {
        return this.mutable && this.persistent;
    }
}
