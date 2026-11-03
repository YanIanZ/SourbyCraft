package dev.iyanz.sourbycraft.awf.multiprofile;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Profile-name helpers following Hypixel SkyBlock's fruit/vegetable list; not wired yet into
 * 26.2 profile creation or persistence.
 *
 * <p>The intended policy chooses a name once at creation, immutable and unique per account.
 * Callers would need to supply the taken names and enforce that policy. When every name is taken (an account with more
 * profiles than names, only possible with a raised slot limit) a numeric suffix is appended, which
 * keeps the invariant and the name still readable.</p>
 */
public final class CuteNames {

    /**
     * Hypixel SkyBlock's published list, in wiki order. Kept as its own list because
     * {@link #fromId} uses it to reproduce the name of a record written before names were stored:
     * appending names to {@link #NAMES} must never change an old record's derived name.
     */
    public static final List<String> LEGACY_NAMES = List.of(
        "Apple", "Banana", "Blueberry", "Coconut", "Cucumber", "Grapes", "Kiwi", "Lemon", "Lime", "Mango",
        "Orange", "Papaya", "Pear", "Peach", "Pineapple", "Pomegranate", "Raspberry", "Strawberry",
        "Tomato", "Watermelon", "Zucchini");

    /**
     * Every name a new profile may roll: Hypixel's list plus the rest of the fruit and vegetable
     * vocabulary. More variety than Hypixel, with the intended rule: chosen once, unique per account,
     * immutable (not enforced by this list).
     */
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

    private CuteNames() {}

    /**
     * A random name not in {@code taken}. Falls back to the first free name plus a suffix when the
     * whole list is taken.
     */
    public static String generate(final Set<String> taken) {
        Objects.requireNonNull(taken, "taken");
        final List<String> free = NAMES.stream().filter(name -> !taken.contains(name)).toList();
        if (!free.isEmpty()) return free.get(ThreadLocalRandom.current().nextInt(free.size()));
        for (int suffix = 2; suffix < 1000; suffix++) {
            for (final String name : NAMES) {
                final String candidate = name + " " + suffix;
                if (!taken.contains(candidate) && valid(candidate)) return candidate;
            }
        }
        throw new IllegalStateException("no free profile name for " + taken.size() + " profiles");
    }

    /**
     * A stable name derived from a profile id, for records written before names existed. The same id
     * always yields the same name, so reading an old file twice never changes it.
     */
    public static String fromId(final java.util.UUID profileId) {
        final int index = Math.floorMod(profileId.hashCode(), LEGACY_NAMES.size());
        return LEGACY_NAMES.get(index);
    }

    /** Whether a name is well formed: a known name, optionally with a numeric suffix. */
    public static boolean valid(final String name) {
        if (name == null || name.isEmpty() || name.length() > 32) return false;
        final String base = name.replaceFirst(" \\d{1,3}$", "");
        if (base.equals(name) && name.contains(" ")) return false;
        return NAMES.contains(base) || LEGACY_NAMES.contains(base);
    }

    /** The canonical form intended for storage and display; not wired yet. */
    public static String normalize(final String name) {
        if (!valid(name)) throw new IllegalArgumentException("not a cute name: " + name);
        return name;
    }

    /** Case-insensitive membership test, for tab completion and admin input. */
    public static boolean matches(final String name, final String candidate) {
        return name != null && candidate != null && name.toLowerCase(Locale.ROOT)
            .equals(candidate.toLowerCase(Locale.ROOT));
    }
}
