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
 * Former Canvas names that are still read after the rename to Aurora/SourbyCraft names (owner decision
 * 2026-10-21). Each old name is honoured with a one-line deprecation warning, logged once per name (or once per
 * name and subject), until {@link #REMOVAL_BUILD}; then the fallback is deleted. The new name always wins when both
 * are present.
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
     * was renamed from {@code legacyName}.
     */
    public static boolean flag(final String name, final String legacyName) {
        final String value = property(name, legacyName);
        return value != null && (value.isEmpty() || Boolean.parseBoolean(value));
    }

    /**
     * A strict boolean JVM flag (only {@code =true} counts, like {@link Boolean#getBoolean}) that was renamed from
     * {@code legacyName}.
     */
    public static boolean strictFlag(final String name, final String legacyName) {
        return Boolean.parseBoolean(property(name, legacyName));
    }

    /** The raw value of {@code name}, else of {@code legacyName} (warning once), else {@code null}. */
    public static @Nullable String property(final String name, final String legacyName) {
        final String value = properties.apply(name);
        if (value != null) {
            return value;
        }
        final String legacy = properties.apply(legacyName);
        if (legacy != null) {
            warnOnce("property:" + legacyName, "JVM flag -D" + legacyName + " is deprecated and stops working in "
                + REMOVAL_BUILD + "; use -D" + name + " instead.");
        }
        return legacy;
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
