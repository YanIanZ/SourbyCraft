package dev.iyanz.aurora.engine.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.temporal.UnsupportedTemporalTypeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 engine util: {@link TimeSpan} parsing and arithmetic.
 *
 * <p>{@code inPast()}/{@code inFuture()} read {@link Instant#now()} directly (no clock seam), so
 * they are checked by bracketing the call between two reads of the wall clock rather than by
 * sleeping; an injected {@code java.time.Clock} would make them exact.</p>
 */
class TimeSpanTest {

    @Test
    void parsesEveryUnitFamilyCaseInsensitivelyWithOptionalSpace() {
        assertEquals("250ms", TimeSpan.parse("250ms").toString());
        assertEquals("10s", TimeSpan.parse(" 10 SECONDS ").toString());
        assertEquals("5m", TimeSpan.parse("5min").toString());
        assertEquals("2h", TimeSpan.parse("2 hours").toString());
        assertEquals("3d", TimeSpan.parse("3day").toString());
        assertEquals("1w", TimeSpan.parse("1week").toString());
        assertEquals("1mo", TimeSpan.parse("1month").toString());
        assertEquals("4y", TimeSpan.parse("4yrs").toString());
        assertEquals("7ns", TimeSpan.parse("7nanos").toString());
        assertEquals("9us", TimeSpan.parse("9micro").toString());
    }

    @Test
    void mIsMinutesAndMoIsMonths() {
        assertEquals(ChronoUnit.MINUTES.getDuration().toNanos(), TimeSpan.parse("1m").asNanoSpan());
        assertEquals(ChronoUnit.MONTHS.getDuration().toNanos(), TimeSpan.parse("1mo").asNanoSpan());
        assertEquals(ChronoUnit.MONTHS.getDuration().toNanos(), TimeSpan.parse("1MO").asNanoSpan());
        assertEquals(ChronoUnit.MONTHS.getDuration().toNanos(), TimeSpan.parse("1 Month").asNanoSpan());
    }

    @Test
    void aLoneUpperCaseMIsRejectedInsteadOfBeingSilentlyAMinute() {
        for (final String ambiguous : new String[] {"1M", "30M", " 5 M "}) {
            final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> TimeSpan.parse(ambiguous), ambiguous);
            assertTrue(ex.getMessage().contains("'m'") && ex.getMessage().contains("'mo'"), ex.getMessage());
        }
        // Other upper-case spellings keep working case-insensitively.
        assertEquals(ChronoUnit.MINUTES.getDuration().toNanos(), TimeSpan.parse("1MIN").asNanoSpan());
        assertEquals(ChronoUnit.SECONDS.getDuration().toNanos(), TimeSpan.parse("1S").asNanoSpan());
    }

    @Test
    void nanoSpanMultipliesTheUnitDuration() {
        assertEquals(300_000_000_000L, TimeSpan.parse("5m").asNanoSpan());
        assertEquals(0L, TimeSpan.parse("0s").asNanoSpan());
        assertEquals(1_500_000L, TimeSpan.of(ChronoUnit.MICROS, 1_500).asNanoSpan());
    }

    @Test
    void nanoSpanOverflowThrowsInsteadOfWrapping() {
        assertThrows(ArithmeticException.class, () -> TimeSpan.parse("1000000y").asNanoSpan());
    }

    @Test
    void rejectsMalformedInput() {
        assertThrows(IllegalArgumentException.class, () -> TimeSpan.parse(null));
        for (final String bad : new String[] {"", "abc", "5", "s", "-5s", "1.5h", "5 parsecs", "5s extra"}) {
            assertThrows(IllegalArgumentException.class, () -> TimeSpan.parse(bad), bad);
        }
        // Long.parseLong overflow surfaces as NumberFormatException, an IllegalArgumentException.
        assertThrows(IllegalArgumentException.class, () -> TimeSpan.parse("99999999999999999999s"));
    }

    @Test
    void ofRejectsAUnitWithoutAnAlias() {
        assertThrows(IllegalStateException.class, () -> TimeSpan.of(ChronoUnit.DECADES, 1));
        assertThrows(IllegalStateException.class, () -> TimeSpan.of(ChronoUnit.FOREVER, 1));
    }

    @Test
    void halfDaysAreConstructibleAndPassTheLargeUnitGuard() {
        final TimeSpan span = TimeSpan.of(ChronoUnit.HALF_DAYS, 3);
        assertEquals(3 * 12 * 3_600_000_000_000L, span.asNanoSpan());
        assertSame(span, span.verifyIsntLargeUnit());
        assertEquals("3halfday", span.toString());
        assertEquals(span.asNanoSpan(), TimeSpan.parse("3halfdays").asNanoSpan());

        final Instant before = Instant.now();
        final Instant past = span.inPast();
        final Instant after = Instant.now();
        assertWithin(past, before.minus(3, ChronoUnit.HALF_DAYS), after.minus(3, ChronoUnit.HALF_DAYS));
    }

    @Test
    void everyUnitUpToYearsRoundTripsThroughOfToStringAndParse() {
        for (final ChronoUnit unit : new ChronoUnit[] {ChronoUnit.NANOS, ChronoUnit.MICROS, ChronoUnit.MILLIS,
            ChronoUnit.SECONDS, ChronoUnit.MINUTES, ChronoUnit.HOURS, ChronoUnit.HALF_DAYS, ChronoUnit.DAYS,
            ChronoUnit.WEEKS, ChronoUnit.MONTHS, ChronoUnit.YEARS}) {
            final TimeSpan made = TimeSpan.of(unit, 2);
            final TimeSpan reparsed = TimeSpan.parse(made.toString());
            assertEquals(made.asNanoSpan(), reparsed.asNanoSpan(), unit + " via " + made);
            assertEquals(made.toString(), reparsed.toString(), unit.toString());
        }
    }

    @Test
    void largeUnitGuard() {
        final TimeSpan days = TimeSpan.parse("3d");
        assertSame(days, days.verifyIsntLargeUnit());
        assertThrows(UnsupportedTemporalTypeException.class, () -> TimeSpan.parse("1w").verifyIsntLargeUnit());
        assertThrows(UnsupportedTemporalTypeException.class, () -> TimeSpan.parse("1mo").verifyIsntLargeUnit());
    }

    @Test
    void pastAndFutureAreOffsetFromNow() {
        final TimeSpan span = TimeSpan.parse("10s");

        final Instant beforePast = Instant.now();
        final Instant past = span.inPast();
        final Instant afterPast = Instant.now();
        assertWithin(past, beforePast.minusSeconds(10), afterPast.minusSeconds(10));

        final Instant beforeFuture = Instant.now();
        final Instant future = span.inFuture();
        final Instant afterFuture = Instant.now();
        assertWithin(future, beforeFuture.plusSeconds(10), afterFuture.plusSeconds(10));
    }

    @Test
    void largeUnitsNeedAnExplicitOptInForInstantArithmetic() {
        assertThrows(UnsupportedTemporalTypeException.class, () -> TimeSpan.parse("1mo").inPast());
        assertThrows(UnsupportedTemporalTypeException.class, () -> TimeSpan.parse("1w").inFuture());

        final TimeSpan month = TimeSpan.parse("1mo").acceptLargeUnits();
        final long nanos = month.asNanoSpan();
        final Instant before = Instant.now();
        final Instant past = month.inPast();
        final Instant after = Instant.now();
        assertWithin(past, before.minusNanos(nanos), after.minusNanos(nanos));
    }

    private static void assertWithin(final Instant value, final Instant low, final Instant high) {
        assertFalse(value.isBefore(low), value + " before " + low);
        assertFalse(value.isAfter(high), value + " after " + high);
    }
}
