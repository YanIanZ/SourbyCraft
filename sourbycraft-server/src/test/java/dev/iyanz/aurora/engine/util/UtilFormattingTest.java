package dev.iyanz.aurora.engine.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OBS-14 engine util: the pure helpers in {@link Util} (formatting, strings, flags, futures,
 * directory pruning, gradients). Region/world helpers need a running server and are not covered.
 *
 * <p>{@code formatNanosToLargestDecimalUnit} formats with {@link Locale#ROOT}, so a host in a
 * comma-decimal locale (e.g. {@code id-ID}) still prints {@code "1.50ms"}; see
 * {@link #decimalFormattingIgnoresTheDefaultLocale()}. The other tests pin {@link Locale#ROOT} as the
 * default so they stay independent of the host.</p>
 */
class UtilFormattingTest {

    private Locale previousLocale;

    @BeforeEach
    void pinLocale() {
        previousLocale = Locale.getDefault();
        Locale.setDefault(Locale.ROOT);
    }

    @AfterEach
    void restoreLocale() {
        Locale.setDefault(previousLocale);
    }

    @Test
    void wholeUnitFormatting() {
        assertEquals("0ns", Util.formatNanosToLargestWholeUnit(0L));
        assertEquals("999ns", Util.formatNanosToLargestWholeUnit(999L));
        assertEquals("1ms", Util.formatNanosToLargestWholeUnit(1_999_999L));
        assertEquals("999ms", Util.formatNanosToLargestWholeUnit(999_999_999L));
        assertEquals("59s", Util.formatNanosToLargestWholeUnit(59_999_999_999L));
        assertEquals("1 minute", Util.formatNanosToLargestWholeUnit(seconds(60)));
        assertEquals("2 minutes", Util.formatNanosToLargestWholeUnit(seconds(125)));
        assertEquals("1 hour", Util.formatNanosToLargestWholeUnit(seconds(3_600)));
        assertEquals("2 hours", Util.formatNanosToLargestWholeUnit(seconds(7_200)));
        assertEquals("1 day", Util.formatNanosToLargestWholeUnit(seconds(86_400)));
        assertEquals("3 days", Util.formatNanosToLargestWholeUnit(seconds(3 * 86_400)));
    }

    @Test
    void microsecondTierCoversOneToNineHundredNinetyNineMicroseconds() {
        assertEquals("1\u00b5s", Util.formatNanosToLargestWholeUnit(1_000L));
        assertEquals("1\u00b5s", Util.formatNanosToLargestWholeUnit(1_999L));
        assertEquals("999\u00b5s", Util.formatNanosToLargestWholeUnit(999_999L));
        assertEquals("1ms", Util.formatNanosToLargestWholeUnit(1_000_000L));
        assertEquals("1.00\u00b5s", Util.formatNanosToLargestDecimalUnit(1_000L));
        assertEquals("1.50\u00b5s", Util.formatNanosToLargestDecimalUnit(1_500L));
        assertEquals("250.00\u00b5s", Util.formatNanosToLargestDecimalUnit(250_000L));
        assertEquals("1.00ms", Util.formatNanosToLargestDecimalUnit(1_000_000L));
        assertEquals("999.00ns", Util.formatNanosToLargestDecimalUnit(999L));
        assertEquals("in 3\u00b5s", Util.formatTargetTime(0L, 3_000L));
        assertEquals("3\u00b5s ago", Util.formatTargetTime(3_000L, 0L));
    }

    @Test
    void decimalFormattingIgnoresTheDefaultLocale() {
        for (final Locale locale : List.of(Locale.forLanguageTag("id-ID"), Locale.GERMANY, Locale.FRANCE,
            Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("hi-IN-u-nu-deva"))) {
            Locale.setDefault(locale);
            assertEquals("1.50ms", Util.formatNanosToLargestDecimalUnit(1_500_000L), locale.toString());
            assertEquals("1.50\u00b5s", Util.formatNanosToLargestDecimalUnit(1_500L), locale.toString());
            assertEquals("999.00ns", Util.formatNanosToLargestDecimalUnit(999L), locale.toString());
            assertEquals("2.50s", Util.formatNanosToLargestDecimalUnit(2_500_000_000L), locale.toString());
            assertEquals("1.50 minutes", Util.formatNanosToLargestDecimalUnit(seconds(90)), locale.toString());
        }
    }

    @Test
    void decimalUnitFormatting() {
        assertEquals("999.00ns", Util.formatNanosToLargestDecimalUnit(999L));
        assertEquals("1.50ms", Util.formatNanosToLargestDecimalUnit(1_500_000L));
        assertEquals("2.50s", Util.formatNanosToLargestDecimalUnit(2_500_000_000L));
        assertEquals("1.00 minute", Util.formatNanosToLargestDecimalUnit(seconds(60)));
        assertEquals("1.50 minutes", Util.formatNanosToLargestDecimalUnit(seconds(90)));
        assertEquals("1.00 hour", Util.formatNanosToLargestDecimalUnit(seconds(3_600)));
        assertEquals("1.00 day", Util.formatNanosToLargestDecimalUnit(seconds(86_400)));
        assertEquals("2.00 days", Util.formatNanosToLargestDecimalUnit(seconds(2 * 86_400)));
    }

    @Test
    void targetTimeTense() {
        assertEquals("in 5s", Util.formatTargetTime(0L, seconds(5)));
        assertEquals("5s ago", Util.formatTargetTime(seconds(5), 0L));
        assertEquals("in 0ns", Util.formatTargetTime(42L, 42L));
        assertEquals("2 minutes ago", Util.formatTargetTime(seconds(1_000), seconds(1_000 - 150)));
    }

    @Test
    void stringHelpers() {
        assertEquals("", Util.capitalize(""));
        assertEquals("X", Util.capitalize("x"));
        assertEquals("Region", Util.capitalize("region"));
        assertEquals("", Util.snakeToCamelCase(""));
        assertEquals("single", Util.snakeToCamelCase("single"));
        assertEquals("guardSeverity", Util.snakeToCamelCase("guard_severity"));
        assertEquals("aB", Util.snakeToCamelCase("a__b"));
        assertEquals("trailing", Util.snakeToCamelCase("trailing_"));
        assertEquals("upperFirst", Util.snakeToCamelCase("UPPER_first"));
    }

    @Test
    void flagParsingIsLenient() {
        final String flag = "aurora.test.util-flag." + System.identityHashCode(this);
        try {
            System.clearProperty(flag);
            assertFalse(Util.isFlagEnabled(flag), "absent");
            for (final String on : List.of("", "true", "TRUE")) {
                System.setProperty(flag, on);
                assertTrue(Util.isFlagEnabled(flag), "'" + on + "'");
            }
            for (final String off : List.of("false", "yes", "1")) {
                System.setProperty(flag, off);
                assertFalse(Util.isFlagEnabled(flag), "'" + off + "'");
            }
        } finally {
            System.clearProperty(flag);
        }
    }

    @Test
    void futureHelpersDoNotWaitForCompletedOrZeroBudgetFutures() {
        assertEquals("done", Util.joinFuture(CompletableFuture.completedFuture("done")));
        assertThrows(CompletionException.class,
            () -> Util.joinFuture(CompletableFuture.failedFuture(new IllegalStateException("boom"))));

        assertTrue(Util.waitFor(CompletableFuture.completedFuture(null), TimeUnit.SECONDS, 0L));
        assertFalse(Util.waitFor(new CompletableFuture<>(), TimeUnit.MILLISECONDS, 0L));
        assertFalse(Util.waitFor(new CompletableFuture<>(), TimeUnit.MILLISECONDS, -1L));
    }

    @Test
    void removeDirectoryContentsOnlyDeletesMatchingTopLevelFiles(@TempDir final Path dir) throws IOException {
        Files.writeString(dir.resolve("a.txt"), "a");
        Files.writeString(dir.resolve("b.log"), "b");
        Files.createDirectories(dir.resolve("sub.txt"));
        Files.writeString(dir.resolve("sub.txt").resolve("c.txt"), "c");

        Util.removeDirectoryContentsIf(dir.toFile(), path -> path.getFileName().toString().endsWith(".txt"));

        assertFalse(Files.exists(dir.resolve("a.txt")));
        assertTrue(Files.exists(dir.resolve("b.log")));
        assertTrue(Files.isDirectory(dir.resolve("sub.txt")), "directories are never deleted");
        assertTrue(Files.exists(dir.resolve("sub.txt").resolve("c.txt")), "no recursion");
    }

    @Test
    void removeDirectoryContentsRejectsAFile(@TempDir final Path dir) throws IOException {
        final File file = Files.writeString(dir.resolve("plain"), "x").toFile();
        assertThrows(IllegalArgumentException.class, () -> Util.removeDirectoryContentsIf(file, path -> true));
    }

    @Test
    void gradientColoursEveryCharacterStartingAtTheFirstColour() {
        final Component gradient = Util.gradient("abcd", null, NamedTextColor.RED, NamedTextColor.BLUE);

        assertEquals(4, gradient.children().size());
        final StringBuilder text = new StringBuilder();
        int previousBlue = -1;
        for (final Component child : gradient.children()) {
            final TextComponent character = (TextComponent) child;
            text.append(character.content());
            final TextColor colour = character.color();
            assertTrue(colour.blue() > previousBlue, "blue increases towards the end colour");
            previousBlue = colour.blue();
        }
        assertEquals("abcd", text.toString());
        assertEquals(TextColor.color(NamedTextColor.RED), TextColor.color(gradient.children().get(0).color()));
    }

    @Test
    void gradientNeedsTwoColoursAndAcceptsEmptyText() {
        assertThrows(IllegalArgumentException.class, () -> Util.gradient("abc", null, NamedTextColor.RED));
        assertTrue(Util.gradient("", null, NamedTextColor.RED, NamedTextColor.BLUE).children().isEmpty());
    }

    private static long seconds(final long seconds) {
        return TimeUnit.SECONDS.toNanos(seconds);
    }
}
