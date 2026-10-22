package dev.iyanz.aurora.engine.configuration;

import dev.iyanz.aurora.engine.GlobalConfiguration;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DEV-H follow-up (TimeSpan 'M' rule): the real {@code GlobalConfiguration} options carry the time-span
 * validation, so an operator file that wrote {@code M} for minutes fails the load through the same
 * {@code Validator} pass that post-load runs, naming the key. Uses the nested {@code Part}s only, so
 * it neither loads {@code config/aurora-server.yml} nor needs a Minecraft bootstrap.
 */
class TimeSpanOptionValidationTest {

    @Test
    void defaultsPassValidation() {
        assertDoesNotThrow(() -> Validator.validateObject(new GlobalConfiguration.Logs()));
        assertDoesNotThrow(() -> Validator.validateObject(new GlobalConfiguration.Autosave()));
    }

    @Test
    void logCleanerRejectsUpperCaseMBeforeAnyFileIsConsidered() throws Exception {
        final GlobalConfiguration.Logs logs = new GlobalConfiguration.Logs();
        set(logs, "cleanerTimeSpan", "1M");

        final RuntimeException ex = assertThrows(RuntimeException.class, () -> Validator.validateObject(logs));

        assertTrue(ex.getMessage().contains("cleanerTimeSpan"), ex.getMessage());
        assertTrue(ex.getMessage().contains("logs.cleanerTimeSpan"), ex.getMessage());
        assertTrue(ex.getMessage().contains("'mo'"), ex.getMessage());
    }

    @Test
    void logCleanerStillAcceptsMonthsAndMinutes() throws Exception {
        final GlobalConfiguration.Logs logs = new GlobalConfiguration.Logs();
        for (final String ok : new String[] {"1mo", "90m", "2w", "30d"}) {
            set(logs, "cleanerTimeSpan", ok);
            assertDoesNotThrow(() -> Validator.validateObject(logs), ok);
        }
    }

    @Test
    void autosaveRejectsUpperCaseMAndLargeUnits() throws Exception {
        final GlobalConfiguration.Autosave autosave = new GlobalConfiguration.Autosave();

        set(autosave, "autosaveFrequency", "10M");
        final RuntimeException upper = assertThrows(RuntimeException.class, () -> Validator.validateObject(autosave));
        assertTrue(upper.getMessage().contains("autosave.autosaveFrequency"), upper.getMessage());

        set(autosave, "autosaveFrequency", "1w");
        final RuntimeException large = assertThrows(RuntimeException.class, () -> Validator.validateObject(autosave));
        assertTrue(large.getMessage().contains("at most days"), large.getMessage());

        set(autosave, "autosaveFrequency", "10m");
        assertDoesNotThrow(() -> Validator.validateObject(autosave));
    }

    private static void set(final Object target, final String field, final String value) throws ReflectiveOperationException {
        final Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }
}
