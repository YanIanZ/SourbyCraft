package dev.iyanz.sourbycraft.api.profile;

import java.util.Locale;
import java.util.Optional;

/**
 * The policy type of a profile, chosen once when it is created and never changed afterwards.
 *
 * <p>This is not {@link ProfileInfo#type()}, the free consumer namespace (for example
 * {@code skyblock}). The two are independent: a profile has both.</p>
 *
 * <p><b>What Build 51 does with it:</b> the type is validated against the server's
 * {@code aurora.awf.multiprofile.types.allowed} list when the profile is created, stored in the
 * profile record (store format 2) and returned by {@link ProfileDetails#profileType()}. <b>Nothing
 * else.</b> No rule is enforced for any type in this build: an {@link #IRONMAN}, {@link #STRANDED}
 * or {@link #BINGO} profile behaves exactly like a {@link #CLASSIC} one, and the restrictions
 * described on each constant are the intended policy of a later build, not a guarantee. A consumer
 * that needs a restriction today must enforce it itself.</p>
 */
public enum ProfileType {

    /** The default type. No restriction is intended. */
    CLASSIC("classic"),
    /** Intended policy: no trading, auction house or bazaar. Not enforced by Build 51. */
    IRONMAN("ironman"),
    /** Intended policy: no trading, auction house or bazaar, and a single island. Not enforced by Build 51. */
    STRANDED("stranded"),
    /** Intended policy: a seasonal profile, archived read-only at the end of its event. Not enforced by Build 51. */
    BINGO("bingo");

    private final String id;

    ProfileType(final String id) {
        this.id = id;
    }

    /** The stable lower-case identifier used in configuration, commands and storage. */
    public String id() {
        return this.id;
    }

    /**
     * Resolves an identifier, ignoring case and surrounding white space. Returns empty for {@code null}
     * and for any identifier that is not one of the constants.
     */
    public static Optional<ProfileType> fromId(final String id) {
        if (id == null) return Optional.empty();
        final String key = id.strip().toLowerCase(Locale.ROOT);
        for (final ProfileType type : values()) {
            if (type.id.equals(key)) return Optional.of(type);
        }
        return Optional.empty();
    }
}
