package dev.iyanz.aurora.engine.util;

import com.google.common.collect.ImmutableMap;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.UnsupportedTemporalTypeException;
import java.util.Map;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a span of time stored as a specific whole number amount of a chrono unit. This is utilized to store time
 * spans and estimate frequencies of events in a more generic fashion in comparison to using ticks for everything,
 * especially given the tick rate can change at runtime as of {@code 1.20.3}.
 * <p>
 * Utilization of this class is intended to be more specific and consistent about time frames rather than relying on
 * ticks always running 20 times per second
 * <p>
 * Parsed text is an integer followed by a unit, case-insensitive, e.g. {@code 250ms}, {@code 5m}, {@code 30d}. Units
 * are written in lower case ({@code ns us ms s m h d w mo y} and their long forms); the one exception is a lone
 * upper-case {@code M}, which is rejected because it reads as "month" to many operators but would otherwise be parsed
 * as a minute. Use {@code m} for minutes and {@code mo} (or {@code month}) for months. Months, weeks and years are
 * large units: see {@link #acceptLargeUnits()}.
 *
 * @author dueris
 */
public class TimeSpan {
    private static final Pattern PATTERN = Pattern.compile("^(\\d+)\\s*([a-zA-Z]+)$");
    private static final Map<String, ChronoUnit> UNIT_ALIASES;

    static {
        final ImmutableMap.Builder<String, ChronoUnit> map = ImmutableMap.builder();

        map.put("ns", ChronoUnit.NANOS);
        map.put("nano", ChronoUnit.NANOS);
        map.put("nanos", ChronoUnit.NANOS);
        map.put("nanosecond", ChronoUnit.NANOS);
        map.put("nanoseconds", ChronoUnit.NANOS);

        map.put("us", ChronoUnit.MICROS);
        map.put("micro", ChronoUnit.MICROS);
        map.put("micros", ChronoUnit.MICROS);
        map.put("microsecond", ChronoUnit.MICROS);
        map.put("microseconds", ChronoUnit.MICROS);

        map.put("ms", ChronoUnit.MILLIS);
        map.put("milli", ChronoUnit.MILLIS);
        map.put("millis", ChronoUnit.MILLIS);
        map.put("millisecond", ChronoUnit.MILLIS);
        map.put("milliseconds", ChronoUnit.MILLIS);

        map.put("s", ChronoUnit.SECONDS);
        map.put("sec", ChronoUnit.SECONDS);
        map.put("secs", ChronoUnit.SECONDS);
        map.put("second", ChronoUnit.SECONDS);
        map.put("seconds", ChronoUnit.SECONDS);

        map.put("m", ChronoUnit.MINUTES);
        map.put("min", ChronoUnit.MINUTES);
        map.put("mins", ChronoUnit.MINUTES);
        map.put("minute", ChronoUnit.MINUTES);
        map.put("minutes", ChronoUnit.MINUTES);

        map.put("h", ChronoUnit.HOURS);
        map.put("hr", ChronoUnit.HOURS);
        map.put("hrs", ChronoUnit.HOURS);
        map.put("hour", ChronoUnit.HOURS);
        map.put("hours", ChronoUnit.HOURS);

        map.put("halfday", ChronoUnit.HALF_DAYS);
        map.put("halfdays", ChronoUnit.HALF_DAYS);

        map.put("d", ChronoUnit.DAYS);
        map.put("day", ChronoUnit.DAYS);
        map.put("days", ChronoUnit.DAYS);

        map.put("w", ChronoUnit.WEEKS);
        map.put("week", ChronoUnit.WEEKS);
        map.put("weeks", ChronoUnit.WEEKS);

        map.put("mo", ChronoUnit.MONTHS);
        map.put("month", ChronoUnit.MONTHS);
        map.put("months", ChronoUnit.MONTHS);

        map.put("y", ChronoUnit.YEARS);
        map.put("yr", ChronoUnit.YEARS);
        map.put("yrs", ChronoUnit.YEARS);
        map.put("year", ChronoUnit.YEARS);
        map.put("years", ChronoUnit.YEARS);

        UNIT_ALIASES = map.build();
    }

    private final ChronoUnit chronoUnit;
    private final long count;
    private final String asString;

    private volatile boolean acceptLargeUnits = false;

    private TimeSpan(final ChronoUnit unit, final long count) {
        this.chronoUnit = unit;
        this.count = count;
        this.asString = count + UNIT_ALIASES.entrySet().stream()
            .filter(entry -> entry.getValue() == chronoUnit)
            .map(Map.Entry::getKey)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No alias for unit: " + chronoUnit));
    }

    /**
     * Constructs a new time span from a chrono unit and the amount of said unit type
     * <p>
     * Every unit from {@link ChronoUnit#NANOS} to {@link ChronoUnit#YEARS} (including
     * {@link ChronoUnit#HALF_DAYS}) is accepted; larger units such as decades have no alias and throw
     * {@link IllegalStateException}.
     *
     * @param unit
     *     the type of unit
     * @param count
     *     the amount of the specified unit
     *
     * @return the constructed time span
     */
    @Contract(value = "_, _ -> new", pure = true)
    public static TimeSpan of(final ChronoUnit unit, final long count) {
        return new TimeSpan(unit, count);
    }

    /**
     * Parses a new time span instance from a raw nullable string, allowing for more generic and lenient input
     *
     * @param str
     *     the string to parse
     *
     * @return the compiled time span
     */
    @Contract("null -> fail")
    public static TimeSpan parse(final @Nullable String str) {
        if (str == null) {
            throw new IllegalArgumentException("Input string cannot be null");
        }
        final String trimmed = str.trim();
        final java.util.regex.Matcher matcher = PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid time span format: '" + str + "'");
        }

        final long count = Long.parseLong(matcher.group(1));
        final String rawUnit = matcher.group(2);
        if (rawUnit.equals("M")) {
            // Lower-casing would silently turn "1M" into one minute; a log cleaner meaning "1 month" would then
            // delete files after a minute. Fail loudly instead of guessing.
            throw new IllegalArgumentException("Ambiguous time unit 'M' in '" + str
                + "': use 'm' for minutes or 'mo' (or 'month') for months");
        }
        final String unitStr = rawUnit.toLowerCase(java.util.Locale.ROOT);

        final ChronoUnit unit = UNIT_ALIASES.get(unitStr);
        if (unit == null) {
            throw new IllegalArgumentException("Unknown time unit: '" + unitStr + "' in '" + str + "'");
        }

        return new TimeSpan(unit, count);
    }

    /**
     * Converts the time unit and the count to nanoseconds
     *
     * @return the time span in nanos
     */
    public long asNanoSpan() {
        return chronoUnit.getDuration().multipliedBy(count).toNanos();
    }

    /**
     * Creates a new {@link java.time.Instant} of the current instant minus the time span, creating an instant targeting
     * the past
     *
     * @return the current instant minus the time span instance
     */
    public Instant inPast() {
        final Instant now = Instant.now();
        if (acceptLargeUnits) {
            // use alternative instant modification to avoid
            // unsupported temporal type exceptions
            return now.minusNanos(asNanoSpan());
        }
        return now.minus(count, chronoUnit);
    }

    /**
     * Creates a new {@link java.time.Instant} of the current instant plus the time span, creating an instant targeting
     * the future
     *
     * @return the current instant plus the time span instance
     */
    public Instant inFuture() {
        final Instant now = Instant.now();
        if (acceptLargeUnits) {
            // use alternative instant modification to avoid
            // unsupported temporal type exceptions
            return now.plusNanos(asNanoSpan());
        }
        return now.plus(count, chronoUnit);
    }

    /**
     * Marks the time span object to allow usage of chrono units larger than days. e.g. weeks, months, years, etc
     */
    public TimeSpan acceptLargeUnits() {
        this.acceptLargeUnits = true;
        return this;
    }

    /**
     * Verifies the chrono unit isn't a large timespan unit(a greater unit than {@link java.time.temporal.ChronoUnit#DAYS}
     */
    public TimeSpan verifyIsntLargeUnit() {
        return switch (chronoUnit) {
            case NANOS, MICROS, MINUTES, SECONDS, MILLIS, HOURS, HALF_DAYS, DAYS -> this;
            default -> throw new UnsupportedTemporalTypeException("Unit too large: " + chronoUnit);
        };
    }

    /**
     * Gets the current time span as a serializable string in minimal form
     *
     * @return the minimal serialized string
     */
    @Override
    public String toString() {
        return asString;
    }
}
