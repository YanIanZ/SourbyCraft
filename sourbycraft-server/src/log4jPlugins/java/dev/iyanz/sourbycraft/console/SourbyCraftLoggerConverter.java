package dev.iyanz.sourbycraft.console;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.pattern.ConverterKeys;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;
import org.apache.logging.log4j.core.pattern.PatternConverter;

/**
 * {@code %scLogger}: the logger name as a short, readable {@code [tag]} for the console and file
 * patterns in {@code log4j2.xml} (paper feature patch 0016).
 *
 * <p>Stock Paper prints {@code [%logger]}, so any logger named after its class prints the fully
 * qualified name, e.g. {@code [ca.spottedleaf.dataconverter.minecraft.datatypes.MCTypeRegistry]}
 * or {@code [oshi.software.os.mac.MacOperatingSystem]}. This converter shortens only names in a
 * fixed list of server-internal namespaces and prints every other name exactly as it is, so a
 * plugin or library logger (including dotted names such as {@code My.Plugin} or
 * {@code com.example.plugin.Main}) keeps its identity:
 * <ul>
 *   <li>{@code dev.iyanz.sourbycraft.*} becomes {@code SourbyCraft/<Class>};</li>
 *   <li>{@code dev.iyanz.aurora.*} and {@code io.canvasmc.*} become {@code Aurora/<Class>};</li>
 *   <li>{@code ca.spottedleaf.dataconverter.*} becomes {@code DataConverter}, {@code oshi.*} {@code OSHI};</li>
 *   <li>{@code io.papermc.*}, {@code ca.spottedleaf.*}, {@code net.minecraft.*},
 *       {@code org.bukkit.craftbukkit.*}, {@code com.mojang.*} and {@code com.destroystokyo.paper.*}
 *       become their simple class name (the segment after the last dot, without a {@code $Inner}
 *       suffix), which is what Paper's own class loggers already print;</li>
 *   <li>anything else, and every name without a dot, is printed unchanged.</li>
 * </ul>
 * An empty (root) logger name prints nothing.
 *
 * <p>The output is plain text. With the {@code console} option ({@code %scLogger{console}}) the
 * {@code SourbyCraft} and {@code Aurora} tags are coloured, but only when TerminalConsoleAppender
 * reports ANSI support (a real terminal) and only below WARN, where {@code %highlightError} does
 * not already colour the whole line. Redirected output (a panel, {@code > server.log}, Docker logs)
 * and {@code logs/latest.log} therefore never contain escape codes from this converter.
 *
 * <p>Compiled from the {@code log4jPlugins} source set, so the annotation processor lists it in
 * {@code Log4j2Plugins.dat} and it is available when log4j first reads {@code log4j2.xml}.
 */
@Plugin(name = "SourbyCraftLoggerConverter", category = PatternConverter.CATEGORY)
@ConverterKeys({"scLogger"})
public final class SourbyCraftLoggerConverter extends LogEventPatternConverter {

    private static final String ESC = "\u001B";
    private static final String RESET = ESC + "[0m";
    // Brand palette mirrored from dev.iyanz.sourbycraft.SourbyCraftColors (this source set is
    // compiled without the server module, so the values are duplicated; keep them in sync).
    private static final String PRIMARY = fg(0xFF, 0xB3, 0x47); // #FFB347 SourbyCraft
    private static final String AURORA = fg(0x22, 0xD3, 0xEE); // #22D3EE Aurora

    private static final String BRAND = "SourbyCraft";
    private static final String ENGINE = "Aurora";

    /**
     * Known internal namespace prefix to tag. A tag ending in '/' gets the simple class name
     * appended; an empty tag means the simple class name alone. Unlisted names are never changed.
     */
    private static final String[][] ALIASES = {
        {"dev.iyanz.sourbycraft.", BRAND + "/"},
        {"dev.iyanz.aurora.", ENGINE + "/"},
        {"io.canvasmc.", ENGINE + "/"},
        {"ca.spottedleaf.dataconverter.", "DataConverter"},
        {"oshi.", "OSHI"},
        // Server internals: the simple class name ("" = no prefix).
        {"io.papermc.", ""},
        {"ca.spottedleaf.", ""},
        {"net.minecraft.", ""},
        {"org.bukkit.craftbukkit.", ""},
        {"com.mojang.", ""},
        {"com.destroystokyo.paper.", ""},
    };

    private final boolean console;

    private SourbyCraftLoggerConverter(final String[] options) {
        super("scLogger", "scLogger");
        this.console = options != null && options.length > 0 && "console".equalsIgnoreCase(options[0]);
    }

    public static SourbyCraftLoggerConverter newInstance(final String[] options) {
        return new SourbyCraftLoggerConverter(options);
    }

    @Override
    public void format(final LogEvent event, final StringBuilder toAppendTo) {
        final String tag = tag(event.getLoggerName());
        if (tag == null) {
            return;
        }
        final String colour = this.console ? colour(tag, event.getLevel()) : null;
        toAppendTo.append('[');
        if (colour != null) {
            toAppendTo.append(colour).append(tag).append(RESET);
        } else {
            toAppendTo.append(tag);
        }
        toAppendTo.append(']');
    }

    /** The short tag for a logger name, or {@code null} for the root logger. */
    static String tag(final String logger) {
        if (logger == null || logger.isEmpty()) {
            return null;
        }
        if (logger.indexOf('.') < 0) {
            return logger;
        }
        for (final String[] alias : ALIASES) {
            if (logger.startsWith(alias[0])) {
                return alias[1].isEmpty() || alias[1].endsWith("/") ? alias[1] + simpleName(logger) : alias[1];
            }
        }
        return logger;
    }

    private static String colour(final String tag, final Level level) {
        if (level != null && level.isMoreSpecificThan(Level.WARN)) {
            return null; // %highlightError colours WARN/ERROR lines as a whole
        }
        final String colour = tag.equals(BRAND) || tag.startsWith(BRAND + "/") ? PRIMARY
            : tag.equals(ENGINE) || tag.startsWith(ENGINE + "/") ? AURORA : null;
        return colour != null && ansiSupported() ? colour : null;
    }

    private static boolean ansiSupported() {
        return TerminalAnsiConverter.terminalAnsi();
    }

    /** Segment after the last dot, without a {@code $Inner} suffix; the whole name if that is empty. */
    private static String simpleName(final String logger) {
        String simple = logger.substring(logger.lastIndexOf('.') + 1);
        final int inner = simple.indexOf('$');
        if (inner > 0) {
            simple = simple.substring(0, inner);
        }
        return simple.isEmpty() ? logger : simple;
    }

    private static String fg(final int r, final int g, final int b) {
        return ESC + "[38;2;" + r + ";" + g + ";" + b + "m";
    }
}
