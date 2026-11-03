package dev.iyanz.sourbycraft.api.profile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;

/**
 * Added in Build 54. How profiles of one {@link ProfileTypeDefinition type} are named.
 *
 * <p>{@link Policy#SERVER} uses the server's name pool ({@code names.policy: cute}, the fruit and vegetable list).
 * {@link Policy#POOL} uses the type's own pool: a new profile rolls a name from it, and a requested name must be one
 * of its entries (optionally followed by a space and a number 1-999). Either way the name is chosen once, never
 * changes and is unique within the account ignoring case; when every pool entry is taken in the account a numeric
 * suffix is added ({@code Ember 2}).</p>
 *
 * @param policy where names come from
 * @param pool the type's own names; empty for {@link Policy#SERVER}, 1-{@value #MAX_POOL} entries for
 *     {@link Policy#POOL}. Each entry is 1-{@value #MAX_ENTRY} characters: words of ASCII letters (an inner
 *     {@code '} or {@code -} allowed) separated by single spaces; entries are unique ignoring case
 */
@NullMarked
public record ProfileNaming(Policy policy, List<String> pool) {

    /** Most entries of a type's pool. */
    public static final int MAX_POOL = 256;
    /**
     * Longest pool entry. Leaves room for a {@code " 999"} suffix inside the 32-character profile name limit.
     */
    public static final int MAX_ENTRY = 24;
    /** The server's pool. */
    public static final ProfileNaming SERVER = new ProfileNaming(Policy.SERVER, List.of());

    private static final Pattern ENTRY = Pattern.compile("[A-Za-z]+(?:['-][A-Za-z]+)*(?: [A-Za-z]+(?:['-][A-Za-z]+)*)*");

    /** Where names come from. */
    public enum Policy {
        /** The server's pool ({@code names.policy}). */
        SERVER,
        /** The type's own {@link ProfileNaming#pool()}. */
        POOL;

        /** The lower-case id used in configuration. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public ProfileNaming {
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(pool, "pool");
        if (policy == Policy.SERVER && !pool.isEmpty()) throw new IllegalArgumentException("naming policy server takes no pool");
        if (policy == Policy.POOL && (pool.isEmpty() || pool.size() > MAX_POOL)) {
            throw new IllegalArgumentException("a naming pool has 1-" + MAX_POOL + " entries");
        }
        final List<String> copy = new ArrayList<>(pool.size());
        final Set<String> keys = new HashSet<>();
        for (final String entry : pool) {
            if (!validEntry(entry)) throw new IllegalArgumentException("naming pool entry: " + entry);
            if (!keys.add(entry.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("naming pool entry listed twice: " + entry);
            copy.add(entry);
        }
        pool = List.copyOf(copy);
    }

    /** A type-owned pool. */
    public static ProfileNaming pool(final List<String> names) {
        return new ProfileNaming(Policy.POOL, names);
    }

    /** Whether {@code entry} is a well-formed pool entry. */
    public static boolean validEntry(final String entry) {
        return entry != null && !entry.isEmpty() && entry.length() <= MAX_ENTRY && ENTRY.matcher(entry).matches();
    }
}
