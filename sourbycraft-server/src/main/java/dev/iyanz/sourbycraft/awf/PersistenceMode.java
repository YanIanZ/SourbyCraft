package dev.iyanz.sourbycraft.awf;

/**
 * How an Aurora World Fabric save commits, from {@code aurora-world-fabric.md}.
 *
 * <p>Every mode commits through the same atomic generation sequence; they differ in what is
 * written and what is verified.</p>
 */
public enum PersistenceMode {
    /** Every chunk's bytes are rewritten, whether or not they changed. */
    FULL,
    /** Only chunks whose bytes are not already stored are written; unchanged chunks are referenced. */
    INCREMENTAL,
    /** As INCREMENTAL, then every object the new generation references is re-read and verified. */
    CHECKPOINT,
    /** Commits are refused. */
    READ_ONLY
}
