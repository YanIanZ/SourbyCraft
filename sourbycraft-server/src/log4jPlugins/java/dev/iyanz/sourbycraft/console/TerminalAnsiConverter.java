package dev.iyanz.sourbycraft.console;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import net.minecrell.terminalconsole.TerminalConsoleAppender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.pattern.ConverterKeys;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;
import org.apache.logging.log4j.core.pattern.PatternConverter;
import org.apache.logging.log4j.core.pattern.PatternFormatter;
import org.apache.logging.log4j.core.pattern.PatternParser;

/**
 * {@code %ansiIfTerminal{pattern}}: formats {@code pattern} and keeps its ANSI escape codes only
 * when TerminalConsoleAppender reports ANSI support (a real terminal); otherwise it strips them,
 * like Paper's {@code %stripAnsi}.
 *
 * <p>Used around the whole line, message and exception included, in the console pattern of
 * {@code log4j2.xml} (paper feature patch 0016). Command replies to the console ({@code /ver}, {@code /perf}, ...) and other messages carry
 * truecolor escapes; on a terminal they render, but when stdout is redirected (a game panel,
 * {@code > server.log}, {@code docker logs}) TerminalConsoleAppender writes them verbatim as
 * {@code [38;2;...m} noise. The file appender uses {@link PlainTextConverter} ({@code %plainText}),
 * which always strips.
 */
@Plugin(name = "ansiIfTerminal", category = PatternConverter.CATEGORY)
@ConverterKeys({"ansiIfTerminal"})
public final class TerminalAnsiConverter extends LogEventPatternConverter {

    static final Pattern ANSI = Pattern.compile("\\e\\[[\\d;]*[^\\d;]");

    private final List<PatternFormatter> formatters;
    private final BooleanSupplier ansiSupported;

    private TerminalAnsiConverter(final List<PatternFormatter> formatters, final BooleanSupplier ansiSupported) {
        super("ansiIfTerminal", null);
        this.formatters = formatters;
        this.ansiSupported = ansiSupported;
    }

    public static TerminalAnsiConverter newInstance(final Configuration config, final String[] options) {
        if (options == null || options.length != 1) {
            LOGGER.error("ansiIfTerminal expects exactly one pattern option");
            return null;
        }
        final PatternParser parser = PatternLayout.createPatternParser(config);
        return new TerminalAnsiConverter(parser.parse(options[0]), TerminalAnsiConverter::terminalAnsi);
    }

    static boolean terminalAnsi() {
        try {
            return TerminalConsoleAppender.isAnsiSupported();
        } catch (final Throwable unavailable) {
            return false;
        }
    }

    @Override
    public void format(final LogEvent event, final StringBuilder toAppendTo) {
        final int start = toAppendTo.length();
        for (final PatternFormatter formatter : this.formatters) {
            formatter.format(event, toAppendTo);
        }
        if (!this.ansiSupported.getAsBoolean()) {
            stripFrom(toAppendTo, start);
        }
    }

    /**
     * Removes ANSI escape sequences from {@code buffer} at and after {@code start}. Scans first and
     * copies only when an ESC is present, so plain text costs no allocation.
     */
    static void stripFrom(final StringBuilder buffer, final int start) {
        int esc = -1;
        for (int i = start, n = buffer.length(); i < n; i++) {
            if (buffer.charAt(i) == '\u001B') {
                esc = i;
                break;
            }
        }
        if (esc < 0) {
            return;
        }
        final String tail = ANSI.matcher(buffer.substring(esc)).replaceAll("");
        buffer.setLength(esc);
        buffer.append(tail);
    }

    /**
     * True when a nested pattern prints the throwable (e.g. {@code %xEx}); PatternLayout then does
     * not append a second stack trace of its own.
     */
    @Override
    public boolean handlesThrowable() {
        return handlesThrowable(this.formatters);
    }

    static boolean handlesThrowable(final List<PatternFormatter> formatters) {
        for (final PatternFormatter formatter : formatters) {
            if (formatter.handlesThrowable()) {
                return true;
            }
        }
        return false;
    }
}
