package dev.iyanz.aurora.engine.configuration.validation;

import dev.iyanz.aurora.engine.configuration.Part;
import dev.iyanz.aurora.engine.util.TimeSpan;
import java.time.DateTimeException;
import org.jspecify.annotations.Nullable;

/**
 * Rejects a time-span option that {@link TimeSpan#parse} cannot read, with a message that names the
 * option and the fix.
 *
 * <p>Runs from {@code Validator.validateObject} at the start of {@code GlobalConfiguration}'s post-load, before
 * the value is used. That matters for {@code logs.cleanerTimeSpan}: the log cleaner deletes files, so
 * a unit that could mean two things (a lone upper-case {@code M}, see {@link TimeSpan}) must stop the load
 * rather than be guessed at. It also means a bad value is caught even when the log directory is empty,
 * where the old per-file parse never ran.</p>
 *
 * @param keyPath         the dotted path of the option in {@code config/aurora-server.yml}, used in the message
 * @param acceptLargeUnits {@code true} when weeks, months and years are fine (the log cleaner); {@code false}
 *                         when the option must not exceed days (the autosave period)
 */
public record TimeSpanValidation(String keyPath, boolean acceptLargeUnits) implements Part.Validation<String> {

    @Override
    public void validate(final @Nullable String text) {
        try {
            final TimeSpan span = TimeSpan.parse(text);
            if (!acceptLargeUnits) {
                span.verifyIsntLargeUnit();
            }
            span.asNanoSpan(); // overflows on an absurd count such as 9999999999y
        } catch (final IllegalArgumentException | DateTimeException | ArithmeticException invalid) {
            throw new IllegalArgumentException(keyPath + " (config/aurora-server.yml) is not a usable time span: "
                + invalid.getMessage() + ". Write a whole number and a unit, e.g. 90s, 5m, 12h, 30d"
                + (acceptLargeUnits ? ", 4w, 1mo, 1y" : " (at most days for this option)")
                + "; 'm' is minutes and 'mo' is months. The value was not applied.", invalid);
        }
    }
}
