package dev.iyanz.sourbycraft.console;

import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.pattern.ConverterKeys;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;
import org.apache.logging.log4j.core.pattern.PatternConverter;
import org.apache.logging.log4j.core.pattern.PatternFormatter;

/**
 * {@code %scHighlight{pattern}}: formats {@code pattern} and, on a real terminal only, paints it
 * yellow for WARN and red for ERROR and FATAL so a problem stands out from the INFO stream. Below
 * WARN, and whenever TerminalConsoleAppender reports no ANSI support, the nested text is appended
 * as it is. Replaces TerminalConsoleAppender's {@code %highlightError} around the message and
 * exception of the console line in {@code log4j2.xml} (paper feature patch 0016), so the whole
 * line follows one palette ({@link ConsolePalette}).
 *
 * <p>Reports a nested {@code %xEx} through {@link #handlesThrowable()}, so PatternLayout does not
 * append a second stack trace.
 */
@Plugin(name = "scHighlight", category = PatternConverter.CATEGORY)
@ConverterKeys({"scHighlight"})
public final class HighlightConverter extends LogEventPatternConverter {

    private final List<PatternFormatter> formatters;

    private HighlightConverter(final List<PatternFormatter> formatters) {
        super("scHighlight", null);
        this.formatters = formatters;
    }

    public static HighlightConverter newInstance(final Configuration config, final String[] options) {
        if (options == null || options.length != 1) {
            LOGGER.error("scHighlight expects exactly one pattern option");
            return null;
        }
        return new HighlightConverter(PatternLayout.createPatternParser(config).parse(options[0]));
    }

    @Override
    public void format(final LogEvent event, final StringBuilder toAppendTo) {
        final Level level = event.getLevel();
        final String colour = level != null && level.isMoreSpecificThan(Level.WARN) && ConsolePalette.terminal()
            ? ConsolePalette.forLevel(level) : null;
        if (colour != null) {
            toAppendTo.append(colour);
        }
        for (final PatternFormatter formatter : this.formatters) {
            formatter.format(event, toAppendTo);
        }
        if (colour != null) {
            toAppendTo.append(ConsolePalette.RESET);
        }
    }

    @Override
    public boolean handlesThrowable() {
        return TerminalAnsiConverter.handlesThrowable(this.formatters);
    }
}
