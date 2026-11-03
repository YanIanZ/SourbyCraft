package dev.iyanz.sourbycraft.api.world;

/**
 * Added in Build 54. How the server's AWF commits are written ({@code aurora.awf.persistence},
 * RESTART_REQUIRED; the default is {@link #INCREMENTAL}). Every mode commits through the same
 * atomic generation sequence; they differ in what is written and what is verified. The setting is
 * server-wide, not per world.
 */
public enum AwfPersistenceMode {
    /** Every chunk's bytes are rewritten, whether or not they changed. */
    FULL,
    /** Only chunks whose bytes are not already stored are written. */
    INCREMENTAL,
    /** As {@link #INCREMENTAL}, then every object the new generation references is re-read and verified. */
    CHECKPOINT,
    /** Commits are refused. */
    READ_ONLY
}
