package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Profile names following Hypixel SkyBlock's fruit and vegetable list.
 *
 * <p>A profile's {@link AuroraProfile#name() name} is chosen once at creation and never changes. It is
 * unique within an account when compared by {@link #key}: lower case, so {@code Apple} and
 * {@code apple} are the same name. When every pool name is taken in the account (more profiles than
 * names, only possible with a raised slot limit) a pool name followed by a space and the smallest free
 * number is used, which keeps the invariant and the name still readable.</p>
 *
 * <p>The repository, not this class, enforces uniqueness: it compares the keys of the account's
 * profiles under the account write guard in the same commit that stores the new profile. This class
 * only chooses and validates names. A record written before names were stored (format 1) receives
 * {@link #fromId}, a name derived from its id, when the store is migrated to format 2; the derivation
 * uses the original 21-name list so it never changes once a build has shown it.</p>
 *
 * <p>Per-account <em>aliases</em> ({@link PlayerAccount#aliases()}) are the same names in the form
 * {@link #alias} gives them ({@code [a-z0-9_-]}, {@code apple-2}); they disambiguate a co-op profile
 * whose name collides with another profile of a member's account. Aliases are not used by Build 51's
 * solo paths beyond being stored.</p>
 */
public final class CuteNames {

    /** Longest name, including a numeric suffix. */
    public static final int MAX_LENGTH = 32;
    /** Longest alias ({@code [a-z0-9_-]}). */
    public static final int MAX_ALIAS_LENGTH = 32;
    /** Highest numeric suffix; above it {@link #generate} gives up rather than loop. */
    public static final int MAX_SUFFIX = 999;

    /**
     * Hypixel SkyBlock's published list, in wiki order. Kept as its own list because
     * {@link #fromId} uses it to reproduce the name of a record written before names were stored:
     * appending names to {@link #NAMES} must never change an old record's derived name.
     */
    public static final List<String> LEGACY_NAMES = List.of(
        "Apple", "Banana", "Blueberry", "Coconut", "Cucumber", "Grapes", "Kiwi", "Lemon", "Lime", "Mango",
        "Orange", "Papaya", "Pear", "Peach", "Pineapple", "Pomegranate", "Raspberry", "Strawberry",
        "Tomato", "Watermelon", "Zucchini");

    /** Every name a new profile may roll: Hypixel's list plus the rest of the fruit and vegetable vocabulary. */
    public static final List<String> NAMES = java.util.stream.Stream.concat(LEGACY_NAMES.stream(),
        java.util.stream.Stream.of(
            "Apricot", "Avocado", "Blackberry", "Cantaloupe", "Cherry", "Clementine", "Cranberry", "Currant",
            "Date", "Dragonfruit", "Durian", "Elderberry", "Fig", "Gooseberry", "Grapefruit", "Guava",
            "Honeydew", "Huckleberry", "Jackfruit", "Kumquat", "Lychee", "Mandarin", "Mulberry", "Nectarine",
            "Olive", "Passionfruit", "Persimmon", "Plantain", "Plum", "Quince", "Rambutan", "Redcurrant",
            "Sapodilla", "Soursop", "Starfruit", "Tamarind", "Tangerine", "Yuzu",
            "Artichoke", "Asparagus", "Beetroot", "Broccoli", "Cabbage", "Carrot", "Cauliflower", "Celery",
            "Corn", "Eggplant", "Garlic", "Leek", "Lettuce", "Onion", "Parsnip", "Pea", "Pepper", "Potato",
            "Pumpkin", "Radish", "Spinach", "Squash", "Sweetcorn", "Turnip", "Yam"))
        .toList();

    private static final Set<String> POOL_KEYS = new HashSet<>();

    static {
        for (final String name : NAMES) POOL_KEYS.add(key(name));
        for (final String name : LEGACY_NAMES) POOL_KEYS.add(key(name));
    }

    private CuteNames() {}

    /**
     * A random pool name whose key is not in {@code takenKeys} (the keys of the account's existing
     * names, see {@link #key}). When the whole pool is taken, the first pool name with the smallest
     * free numeric suffix ({@code Apple 2}, {@code Banana 2}, ..., {@code Apple 3}, ...). Deterministic
     * only in the exhausted case; a caller that needs replay to choose the same name records the
     * chosen name in its intent and reuses it.
     */
    public static String generate(final Set<String> takenKeys) {
        Objects.requireNonNull(takenKeys, "takenKeys");
        final List<String> free = NAMES.stream().filter(name -> !takenKeys.contains(key(name))).toList();
        if (!free.isEmpty()) return free.get(ThreadLocalRandom.current().nextInt(free.size()));
        for (int suffix = 2; suffix <= MAX_SUFFIX; suffix++) {
            for (final String name : NAMES) {
                final String candidate = name + " " + suffix;
                if (valid(candidate) && !takenKeys.contains(key(candidate))) return candidate;
            }
        }
        throw new IllegalStateException("no free profile name for " + takenKeys.size() + " taken names");
    }

    /**
     * A stable name derived from a profile id, for records written before names existed. The same id
     * always yields the same name, so migrating an old store twice never changes it. Two old profiles
     * of one account may derive the same name; the migration then suffixes the later one (by creation
     * time, then id) with {@link #generate} semantics.
     */
    public static String fromId(final UUID profileId) {
        final int index = Math.floorMod(profileId.hashCode(), LEGACY_NAMES.size());
        return LEGACY_NAMES.get(index);
    }

    /**
     * Whether a name is well formed and canonical: a pool name, optionally followed by one space and a
     * number 1-999 without a leading zero. {@code Apple 01} is refused rather than accepted as a second
     * spelling of {@code Apple 1}, so two valid names with equal {@link #key keys} are the same name.
     */
    public static boolean valid(final String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_LENGTH) return false;
        final String base = name.replaceFirst(" [1-9]\\d{0,2}$", "");
        if (base.equals(name) && name.contains(" ")) return false;
        return POOL_KEYS.contains(key(base));
    }

    /**
     * The canonical stored form of a valid name: the pool spelling (capitalised as in the pool), with
     * its suffix as given. Refuses anything {@link #valid} refuses.
     */
    public static String normalize(final String name) {
        if (!valid(name)) throw new IllegalArgumentException("not a profile name: " + name);
        final String base = name.replaceFirst(" [1-9]\\d{0,2}$", "");
        final String suffix = name.substring(base.length());
        for (final String pool : NAMES) {
            if (key(pool).equals(key(base))) return pool + suffix;
        }
        return name;
    }

    /** The uniqueness key of a name: lower case. Two names with equal keys are the same name. */
    public static String key(final String name) {
        return Objects.requireNonNull(name, "name").toLowerCase(Locale.ROOT);
    }

    /** Case-insensitive comparison, for tab completion and admin input. */
    public static boolean matches(final String name, final String candidate) {
        return name != null && candidate != null && key(name).equals(key(candidate));
    }

    /**
     * The alias form of a name: {@link #key}, with every run of characters outside {@code [a-z0-9_-]}
     * replaced by one {@code -}, cut to {@value #MAX_ALIAS_LENGTH} characters ({@code Apple 2} is
     * {@code apple-2}).
     */
    public static String alias(final String name) {
        String alias = key(name).replaceAll("[^a-z0-9_-]+", "-");
        if (alias.length() > MAX_ALIAS_LENGTH) alias = alias.substring(0, MAX_ALIAS_LENGTH);
        if (alias.isEmpty() || alias.equals("-")) throw new IllegalArgumentException("no alias for " + name);
        return alias;
    }

    /** Whether an alias is well formed: 1-32 characters of {@code [a-z0-9_-]}. */
    public static boolean validAlias(final String alias) {
        return alias != null && alias.matches("[a-z0-9_-]{1,32}");
    }

    /**
     * {@code base} when it is free in {@code takenAliases}, else {@code base-N} with the smallest free
     * {@code N} from 2, truncating the base so the result fits {@value #MAX_ALIAS_LENGTH} characters.
     */
    public static String allocateAlias(final String base, final Set<String> takenAliases) {
        if (!validAlias(base)) throw new IllegalArgumentException("alias base: " + base);
        if (!takenAliases.contains(base)) return base;
        for (int suffix = 2; suffix <= MAX_SUFFIX; suffix++) {
            final String tail = "-" + suffix;
            final String head = base.length() + tail.length() > MAX_ALIAS_LENGTH
                ? base.substring(0, MAX_ALIAS_LENGTH - tail.length()) : base;
            final String candidate = head + tail;
            if (!takenAliases.contains(candidate)) return candidate;
        }
        throw new IllegalStateException("no free alias for " + base + " among " + takenAliases.size() + " aliases");
    }
}
