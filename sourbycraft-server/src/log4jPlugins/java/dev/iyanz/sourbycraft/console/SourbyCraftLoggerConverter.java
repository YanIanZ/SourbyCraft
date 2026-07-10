package dev.iyanz.sourbycraft.console;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.pattern.ConverterKeys;
import org.apache.logging.log4j.core.pattern.LogEventPatternConverter;
import org.apache.logging.log4j.core.pattern.PatternConverter;

/**
 * SourbyCraft-branded logger-name converter for the log4j2 console pattern.
 *
 * <p>Folia/Luminol config modules obtain their logger via
 * {@code com.mojang.logging.LogUtils.getLogger()}, which names the logger after the
 * fully-qualified class (e.g. {@code me.earthme.luminol.config.modules.optimizations.SIMDConfig}).
 * The stock console pattern renders that verbose FQN as {@code [%logger]}, producing lines like:
 *
 * <pre>[21:42:48 WARN]: [me.earthme.luminol.config.modules.optimizations.SIMDConfig] SIMD ...</pre>
 *
 * <p>This converter replaces the {@code [%logger]} token in the <em>console</em> pattern with a
 * clean, hex/ANSI-colored SourbyCraft tag. Loggers that belong to the fork's internal packages
 * (Luminol, Leaves/Leaf, Purpur ports, Paper, and the authored {@code dev.iyanz.sourbycraft.*}
 * code) collapse to {@code SourbyCraft/SimpleClassName}; the {@code SourbyCraft} prefix is drawn
 * in the brand PRIMARY color (#FFB347) and the class in DIM grey. Vanilla / Mojang / unknown
 * third-party loggers keep their real (short) name uncolored, so nothing that isn't ours is
 * mislabelled.
 *
 * <p>Registered as a log4j2 pattern plugin via the {@code log4jPlugins} source set (the same
 * mechanism Paper uses for {@code %stripAnsi}); the annotation processor bakes it into
 * {@code Log4j2Plugins.dat}, so it is available the instant log4j reads {@code log4j2.xml} — no
 * programmatic reconfigure and no {@code net/minecraft} patch churn.
 *
 * <p>The color escapes are raw 24-bit ("truecolor") ANSI, matching the branded startup banner.
 * Paper/Folia's JLine console renders them on truecolor-capable terminals; on a terminal that
 * does not understand them they are inert and the text still reads. The converter is deliberately
 * used <em>only</em> in the {@code TerminalConsole} appender — the rolling {@code File} appender
 * keeps a plain {@code %logger}, so {@code logs/latest.log} stays ANSI-free and grep-friendly.
 */
@Plugin(name = "SourbyCraftLoggerConverter", category = PatternConverter.CATEGORY)
@ConverterKeys({"scLogger"})
public final class SourbyCraftLoggerConverter extends LogEventPatternConverter {

    private static final String ESC = "\u001B";
    private static final String RESET = ESC + "[0m";

    // Brand palette mirrored from dev.iyanz.sourbycraft.SourbyCraftColors (the log4jPlugins
    // source set is compiled in isolation and cannot depend on the server module, so the two
    // hex values are duplicated here on purpose — keep them in sync if the brand palette moves).
    private static final String PRIMARY = fg(0xFF, 0xB3, 0x47); // #FFB347 SourbyCraft brand
    private static final String DIM     = fg(0x80, 0x80, 0x80); // #808080 class name

    private static final String BRAND = "SourbyCraft";

    /** Logger-name prefixes that belong to the fork and should collapse to the brand tag. */
    private static final String[] INTERNAL_PREFIXES = {
        "me.earthme.luminol.",
        "org.leavesmc.",
        "org.dreeam.leaf.",
        "org.purpurmc.",
        "org.leaf.",
        "dev.iyanz.sourbycraft.",
        "io.papermc.paper.",
        "com.destroystokyo.paper.",
        "abomination.",
        "com.kiocg.",
    };

    private static String fg(int r, int g, int b) {
        return ESC + "[38;2;" + r + ";" + g + ";" + b + "m";
    }

    private SourbyCraftLoggerConverter(final String[] options) {
        super("scLogger", "scLogger");
    }

    public static SourbyCraftLoggerConverter newInstance(final String[] options) {
        return new SourbyCraftLoggerConverter(options);
    }

    @Override
    public void format(final LogEvent event, final StringBuilder toAppendTo) {
        final String logger = event.getLoggerName();
        if (logger == null || logger.isEmpty()) {
            // Root logger et al. — the console pattern already omits the token for these via
            // the LoggerNamePatternSelector, but guard anyway so we never print "[]".
            return;
        }

        if (isInternal(logger)) {
            // [SourbyCraft/SimpleName] with brand + dim coloring, then a hard reset so the
            // surrounding %highlightError / %msg picks up its own color cleanly.
            toAppendTo.append('[')
                      .append(PRIMARY).append(BRAND)
                      .append(DIM).append('/').append(simpleName(logger))
                      .append(RESET)
                      .append(']');
        } else {
            // Vanilla / Mojang / unknown third-party: keep the real logger name verbatim and
            // uncolored, so nothing that isn't ours is renamed, recolored, or truncated. This
            // exactly reproduces the stock [%logger] behavior for those loggers.
            toAppendTo.append('[').append(logger).append(']');
        }
    }

    private static boolean isInternal(final String logger) {
        if (logger.equals(BRAND)) {
            return true; // dev.iyanz.sourbycraft.util.SourbyLogger uses the literal "SourbyCraft"
        }
        for (final String prefix : INTERNAL_PREFIXES) {
            if (logger.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** Last dot-separated segment of a logger name (the simple class name), or the whole string. */
    private static String simpleName(final String logger) {
        final int dot = logger.lastIndexOf('.');
        return dot >= 0 && dot < logger.length() - 1 ? logger.substring(dot + 1) : logger;
    }
}
