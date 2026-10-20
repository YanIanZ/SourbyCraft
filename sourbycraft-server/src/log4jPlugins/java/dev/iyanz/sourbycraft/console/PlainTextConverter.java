package dev.iyanz.sourbycraft.console;

import java.util.List;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.pattern.ConverterKeys;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;
import org.apache.logging.log4j.core.pattern.PatternConverter;
import org.apache.logging.log4j.core.pattern.PatternFormatter;

/**
 * {@code %plainText{pattern}}: formats {@code pattern} and always removes ANSI escape sequences.
 *
 * <p>Paper's {@code %stripAnsi} does the same but does not report that a nested {@code %xEx}
 * prints the throwable, so wrapping an exception in it would make PatternLayout append a second,
 * unstripped stack trace. This converter reports it ({@link #handlesThrowable()}), so the file
 * pattern in {@code log4j2.xml} (paper feature patch 0016) can strip message and exception text
 * together: {@code %plainText{%msg%n%xEx{full}}}.
 */
@Plugin(name = "plainText", category = PatternConverter.CATEGORY)
@ConverterKeys({"plainText"})
public final class PlainTextConverter extends LogEventPatternConverter {

    private final List<PatternFormatter> formatters;

    private PlainTextConverter(final List<PatternFormatter> formatters) {
        super("plainText", null);
        this.formatters = formatters;
    }

    public static PlainTextConverter newInstance(final Configuration config, final String[] options) {
        if (options == null || options.length != 1) {
            LOGGER.error("plainText expects exactly one pattern option");
            return null;
        }
        return new PlainTextConverter(PatternLayout.createPatternParser(config).parse(options[0]));
    }

    @Override
    public void format(final LogEvent event, final StringBuilder toAppendTo) {
        final int start = toAppendTo.length();
        for (final PatternFormatter formatter : this.formatters) {
            formatter.format(event, toAppendTo);
        }
        TerminalAnsiConverter.stripFrom(toAppendTo, start);
    }

    @Override
    public boolean handlesThrowable() {
        return TerminalAnsiConverter.handlesThrowable(this.formatters);
    }
}
