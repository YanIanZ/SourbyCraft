package dev.iyanz.sourbycraft.console;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.pattern.ConverterKeys;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;
import org.apache.logging.log4j.core.pattern.PatternConverter;

/**
 * {@code %scLevel}: the level as a fixed five-character column ({@code INFO }, {@code WARN },
 * {@code ERROR}, {@code FATAL}, {@code DEBUG}, {@code TRACE}; any other level is its name cut or
 * padded to five characters), the second column of the console line in {@code log4j2.xml} (paper
 * feature patch 0016). A fixed width keeps the tag and message columns aligned for every level.
 *
 * <p>With the {@code console} option the token is coloured by level (WARN yellow, ERROR and FATAL
 * red, INFO blue, anything lower grey), but only on a real terminal
 * ({@link ConsolePalette#terminal()}); redirected output and {@code logs/latest.log} get the plain
 * token. Constants only: nothing is allocated per event.
 */
@Plugin(name = "SourbyCraftLevelConverter", category = PatternConverter.CATEGORY)
@ConverterKeys({"scLevel"})
public final class LevelConverter extends LogEventPatternConverter {

    static final int WIDTH = 5;

    private final boolean console;

    private LevelConverter(final String[] options) {
        super("scLevel", "scLevel");
        this.console = options != null && options.length > 0 && "console".equalsIgnoreCase(options[0]);
    }

    public static LevelConverter newInstance(final String[] options) {
        return new LevelConverter(options);
    }

    @Override
    public void format(final LogEvent event, final StringBuilder toAppendTo) {
        final Level level = event.getLevel();
        final String colour = this.console && ConsolePalette.terminal() ? ConsolePalette.forLevel(level) : null;
        if (colour != null) {
            toAppendTo.append(colour);
        }
        appendLabel(level, toAppendTo);
        if (colour != null) {
            toAppendTo.append(ConsolePalette.RESET);
        }
    }

    /** Appends the level name cut or space-padded to {@value #WIDTH} characters. */
    static void appendLabel(final Level level, final StringBuilder toAppendTo) {
        final String name = level == null ? "?" : level.name();
        final int shown = Math.min(WIDTH, name.length());
        toAppendTo.append(name, 0, shown);
        for (int i = shown; i < WIDTH; i++) {
            toAppendTo.append(' ');
        }
    }
}
