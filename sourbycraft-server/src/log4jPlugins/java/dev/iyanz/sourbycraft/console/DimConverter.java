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
 * {@code %scDim{pattern}}: formats {@code pattern} in slate grey on a real terminal and as plain
 * text everywhere else. Used for the timestamp column of the console line in {@code log4j2.xml}
 * (paper feature patch 0016), so the eye lands on the level and the message rather than the clock.
 */
@Plugin(name = "scDim", category = PatternConverter.CATEGORY)
@ConverterKeys({"scDim"})
public final class DimConverter extends LogEventPatternConverter {

    private final List<PatternFormatter> formatters;

    private DimConverter(final List<PatternFormatter> formatters) {
        super("scDim", null);
        this.formatters = formatters;
    }

    public static DimConverter newInstance(final Configuration config, final String[] options) {
        if (options == null || options.length != 1) {
            LOGGER.error("scDim expects exactly one pattern option");
            return null;
        }
        return new DimConverter(PatternLayout.createPatternParser(config).parse(options[0]));
    }

    @Override
    public void format(final LogEvent event, final StringBuilder toAppendTo) {
        final boolean colour = ConsolePalette.terminal();
        if (colour) {
            toAppendTo.append(ConsolePalette.GREY);
        }
        for (final PatternFormatter formatter : this.formatters) {
            formatter.format(event, toAppendTo);
        }
        if (colour) {
            toAppendTo.append(ConsolePalette.RESET);
        }
    }

    @Override
    public boolean handlesThrowable() {
        return TerminalAnsiConverter.handlesThrowable(this.formatters);
    }
}
