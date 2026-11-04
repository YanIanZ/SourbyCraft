package dev.iyanz.sourbycraft.awf.multiprofile;

/** What role a bound world plays for a profile. */
public enum ProfileWorldRole {
    /** The profile's main gameplay world. */
    PRIMARY,
    /** Nether counterpart. */
    NETHER,
    /** End counterpart. */
    END,
    /** A world shared by several profiles (membership is separate from ownership). */
    SHARED,
    /** Any other world a consumer attaches. */
    AUXILIARY
}
