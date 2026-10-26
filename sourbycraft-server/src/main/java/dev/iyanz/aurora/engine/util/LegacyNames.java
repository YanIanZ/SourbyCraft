package dev.iyanz.aurora.engine.util;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;

/**
 * Former Canvas JVM flag names, renamed to Aurora names in Build 48 (owner decision 2026-10-21) and no longer read
 * since {@link #REMOVAL_BUILD}. Only the new name decides. An old name that is set while its new name is not gets a
 * one-line warning, logged once per name (or once per name and subject), saying it is ignored and naming the
 * replacement, so an operator who still passes it learns why it has no effect.
 *
 * <p>Deliberately free of Minecraft types: {@code SharedConstants} and {@code MoonriseCommon} call it from their
 * static initialisers.</p>
 */
@NullMarked
public final class LegacyNames {

    /** The first build that no longer reads the old names. */
    public static final String REMOVAL_BUILD = "Build 50";

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static volatile UnaryOperator<@Nullable String> properties = System::getProperty;
    private static volatile Consumer<String> warnings = message -> LoggerFactory.getLogger("Aurora").warn(message);

    private LegacyNames() {
    }

    /**
     * A lenient boolean JVM flag ({@code -Dname} alone means {@code true}, like {@link Util#isFlagEnabled}) that
     * was renamed from {@code legacyName}; the old name is not read.
     */
    public static boolean flag(final String name, final String legacyName) {
        final String value = property(name, legacyName);
        return value != null && (value.isEmpty() || Boolean.parseBoolean(value));
    }

    /**
     * A strict boolean JVM flag (only {@code =true} counts, like {@link Boolean#getBoolean}) that was renamed from
     * {@code legacyName}; the old name is not read.
     */
    public static boolean strictFlag(final String name, final String legacyName) {
        return Boolean.parseBoolean(property(name, legacyName));
    }

    /**
     * The raw value of {@code name}, else {@code null}. {@code legacyName} is never returned: when it is set and
     * {@code name} is not, one warning says it is ignored.
     */
    public static @Nullable String property(final String name, final String legacyName) {
        final String value = properties.apply(name);
        if (value == null && properties.apply(legacyName) != null) {
            warnOnce("property:" + legacyName, "JVM flag -D" + legacyName + " is ignored since " + REMOVAL_BUILD
                + "; use -D" + name + " instead.");
        }
        return value;
    }

    /**
     * Logs {@code message} the first time {@code key} is seen in this process.
     *
     * @return whether it was logged now
     */
    public static boolean warnOnce(final String key, final String message) {
        if (!WARNED.add(key)) {
            return false;
        }
        warnings.accept(message);
        return true;
    }

    /** Test seam: replaces the property source and the warning sink, and forgets earlier warnings. */
    public static void installForTesting(final UnaryOperator<@Nullable String> propertySource, final Consumer<String> warningSink) {
        properties = Objects.requireNonNull(propertySource);
        warnings = Objects.requireNonNull(warningSink);
        WARNED.clear();
    }

    /** Test seam: restores {@link System#getProperty} and the logger. */
    public static void resetForTesting() {
        properties = System::getProperty;
        warnings = message -> LoggerFactory.getLogger("Aurora").warn(message);
        WARNED.clear();
    }
}
