package dev.iyanz.sourbycraft.api.profile;

import java.util.Locale;
import java.util.Optional;

/**
 * The policy type of a profile, chosen once when it is created and never changed afterwards.
 *
 * <p>This is not {@link ProfileInfo#type()}, the free consumer namespace (for example
 * {@code skyblock}). The two are independent: a profile has both.</p>
 *
 * <p><b>What the server does with it:</b> the type is validated against the server's
 * {@code aurora.awf.multiprofile.types.allowed} list when the profile is created, stored in the
 * profile record (store format 2) and returned by {@link ProfileDetails#profileType()}. Since Build 54
 * its rules come from the {@link ProfileTypeRegistry}: each constant is the id of a built-in
 * {@link ProfileTypeDefinition} (which a plugin may replace), and the server checks the actions it owns
 * (bank, co-op, salvage; see the {@link ProfileTypeRegistry} constants) against it. The trading, auction
 * house and bazaar restrictions on the constants below are rules of the built-in definitions that a
 * consumer enforces with {@link ProfileTypeRegistry#allows}; the server has no such action of its own.
 * A plugin-registered type is stored as {@link #CLASSIC} with its id in
 * {@link ProfileTypeRegistry#TYPE_METADATA_KEY}; use {@link ProfileDetails#typeId()} for the effective id.</p>
 */
public enum ProfileType {

    /** The default type. Built-in rules: none denied. */
    CLASSIC("classic"),
    /** Built-in rules: {@code trade}, {@code auction} and {@code bazaar} denied (enforced by the consumer). */
    IRONMAN("ironman"),
    /** Built-in rules: {@code trade}, {@code auction} and {@code bazaar} denied (enforced by the consumer); a single island is the consumer's policy. */
    STRANDED("stranded"),
    /** A seasonal profile. Built-in rules: none denied. Archiving at the end of the event is planned (Build 55), not implemented. */
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
