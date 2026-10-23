package dev.iyanz.sourbycraft.console;

import org.apache.logging.log4j.Level;

/**
 * The escape sequences the console converters use, and the one rule for when they may be used.
 *
 * <p>Every sequence here is a constant: converters append them to the layout buffer and never
 * build strings per event. They are written only when {@link #terminal()} is true, i.e. when
 * TerminalConsoleAppender reports ANSI support (a real terminal). Redirected output (a panel,
 * {@code > server.log}, Docker logs) and {@code logs/latest.log} never see them.
 *
 * <p>Palette (24-bit): the brand cyan and violet for the {@code SourbyCraft} and {@code Aurora}
 * tags, slate grey for the timestamp, and the level colours. The values mirror
 * {@code dev.iyanz.sourbycraft.SourbyCraftColors} (this source set is compiled without the server
 * module, so they are duplicated; keep them in sync).
 */
final class ConsolePalette {

    static final String ESC = "\u001B";
    static final String RESET = ESC + "[0m";

    static final String CYAN = fg(0x22, 0xD3, 0xEE);   // SourbyCraftColors.AURORA / PRIMARY
    static final String VIOLET = fg(0xC0, 0x84, 0xFC); // SourbyCraftColors.ACCENT
    static final String BLUE = fg(0x7D, 0xD3, 0xFC);   // SourbyCraftColors.INFO
    static final String YELLOW = fg(0xFB, 0xBF, 0x24); // SourbyCraftColors.WARNING
    static final String RED = fg(0xF4, 0x3F, 0x5E);    // SourbyCraftColors.DANGER
    static final String GREY = fg(0x64, 0x74, 0x8B);   // SourbyCraftColors.DIM

    private ConsolePalette() {}

    /** True only on a real terminal; the single gate for every escape sequence in this package. */
    static boolean terminal() {
        return TerminalAnsiConverter.terminalAnsi();
    }

    /** The colour of a level token and of a highlighted message, or {@code null} for plain text. */
    static String forLevel(final Level level) {
        if (level == null) {
            return null;
        }
        if (level.isMoreSpecificThan(Level.ERROR)) {
            return RED;
        }
        if (level == Level.WARN) {
            return YELLOW;
        }
        if (level == Level.INFO) {
            return BLUE;
        }
        return GREY; // DEBUG, TRACE and custom levels below INFO
    }

    private static String fg(final int r, final int g, final int b) {
        return ESC + "[38;2;" + r + ";" + g + ";" + b + "m";
    }
}
