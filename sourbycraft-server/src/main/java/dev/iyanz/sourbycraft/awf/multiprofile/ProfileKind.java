package dev.iyanz.sourbycraft.awf.multiprofile;

import java.io.IOException;

/**
 * Whether a profile is played by its owner alone or shared by a co-op (format 2, record type 2, payload
 * schema 2).
 *
 * <p>The conversion is one way: {@link AuroraProfile#convertedToCoop} turns a {@link #SOLO} profile into a
 * {@link #COOP} one, nothing turns it back (Hypixel parity). A co-op profile with only its owner left is
 * still a co-op profile: the kind is stored explicitly and never derived from the member count. Both
 * repositories refuse an after-image that moves a profile from COOP to SOLO.</p>
 *
 * <p>Record layout: a SOLO profile without a display label is written with payload schema 1, exactly as
 * Build 51 wrote it; a COOP profile (or one with a display label) is written with payload schema 2, which
 * Build 51 refuses to decode ({@code "uses payload schema 2; this build reads schema 1"}) rather than read
 * it as a solo profile of the owner.</p>
 */
public enum ProfileKind {

    /** Owned and played by one account. */
    SOLO(1),
    /** Shared: member rows beyond the owner may exist, invites and votes apply. */
    COOP(2);

    private final int tag;

    ProfileKind(final int tag) {
        this.tag = tag;
    }

    /** The stable numeric tag written by format-2 records. */
    public int tag() {
        return this.tag;
    }

    public static ProfileKind fromTag(final int tag, final String where) throws IOException {
        for (final ProfileKind kind : values()) {
            if (kind.tag == tag) return kind;
        }
        throw new IOException("profile kind tag " + tag + " in " + where + " is not known to this build");
    }
}
