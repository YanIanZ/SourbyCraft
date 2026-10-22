package dev.iyanz.aurora.engine.configuration.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DEV-H follow-up (TimeSpan 'M' rule): the validator that turns an unreadable time-span option into a
 * load failure that names the key and the fix, instead of a bare exception from deep inside post-load.
 */
class TimeSpanValidationTest {

    private final TimeSpanValidation cleaner = new TimeSpanValidation("logs.cleanerTimeSpan", true);
    private final TimeSpanValidation autosave = new TimeSpanValidation("autosave.autosaveFrequency", false);

    @Test
    void engineDefaultsAreValid() {
        assertDoesNotThrow(() -> cleaner.validate("30d"));
        assertDoesNotThrow(() -> autosave.validate("5m"));
    }

    @Test
    void largeUnitsAreAcceptedOnlyWhereTheOptionAllowsThem() {
        for (final String large : new String[] {"2w", "1mo", "1 month", "1y"}) {
            assertDoesNotThrow(() -> cleaner.validate(large), large);
            final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> autosave.validate(large), large);
            assertTrue(ex.getMessage().contains("autosave.autosaveFrequency"), ex.getMessage());
            assertTrue(ex.getMessage().contains("at most days"), ex.getMessage());
        }
    }

    @Test
    void aLoneUpperCaseMNamesTheKeyAndTheFix() {
        for (final TimeSpanValidation validation : new TimeSpanValidation[] {cleaner, autosave}) {
            final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> validation.validate("1M"));
            final String message = ex.getMessage();
            assertTrue(message.contains(validation.keyPath()), message);
            assertTrue(message.contains("config/aurora-server.yml"), message);
            assertTrue(message.contains("'M'"), message);
            assertTrue(message.contains("'m' for minutes") && message.contains("'mo'"), message);
            assertTrue(message.contains("not applied"), message);
        }
    }

    @Test
    void garbageNullAndNegativeLookingValuesAreRejectedWithTheKey() {
        for (final String bad : new String[] {null, "", "abc", "5", "5 parsecs", "-5m", "1.5h", "m"}) {
            final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> cleaner.validate(bad), String.valueOf(bad));
            assertTrue(ex.getMessage().contains("logs.cleanerTimeSpan"), ex.getMessage());
        }
    }

    @Test
    void anAbsurdCountThatOverflowsNanosIsRejectedHereNotLaterPerFile() {
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> cleaner.validate("9999999999999y"));
        assertTrue(ex.getMessage().contains("logs.cleanerTimeSpan"), ex.getMessage());
    }
}
