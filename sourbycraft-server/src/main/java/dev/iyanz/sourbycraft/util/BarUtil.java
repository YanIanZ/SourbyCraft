package dev.iyanz.sourbycraft.util;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

import static net.kyori.adventure.text.Component.text;

/**
 * SourbyCraft progress-bar helper.
 *
 * <p>Bar style switched in 26.1.2-EXP from the legacy ASCII full/light
 * blocks ({@code █░}) to the parallelogram glyphs ({@code ▰▱}) for a
 * tighter, lower-stroke look that lines up cleanly with the hex-coloured
 * SourbyCraft palette. The default width is 40 so a full bar gives a
 * visible 1-percent resolution per glyph.
 *
 * <p>Every public render path returns an Adventure {@link Component}
 * so callers can compose with hex colours from
 * {@link SourbyCraftColors} without re-stringifying. The raw
 * {@link #bar(double, int)} variant exists for places that need a
 * plain string (BossBar labels, log lines).
 */
public final class BarUtil {

    public static final String FILLED   = "▰";
    public static final String EMPTY    = "▱";
    public static final String INFINITY = "∞";

    public static final int DEFAULT_WIDTH = 40;

    private BarUtil() {}

    /** Raw bar string with no colour, no percentage. */
    public static String bar(double percent, int width) {
        int filled = (int) Math.round(Math.clamp(percent, 0, 100) / 100.0 * width);
        return FILLED.repeat(filled) + EMPTY.repeat(width - filled);
    }

    /** {@code bar(percent, DEFAULT_WIDTH) + " 51%"} pattern from the brief. */
    public static String barWithPercent(double percent, int width) {
        return bar(percent, width) + " " + (int) Math.round(Math.clamp(percent, 0, 100)) + "%";
    }

    /** Generic percentage bar, severity coloured (success / primary / danger). */
    public static Component coloredBar(double percent, int width) {
        TextColor color = percent > 80 ? SourbyCraftColors.DANGER
            : percent > 50 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.SUCCESS;
        return text()
            .append(text(bar(percent, width), color))
            .append(text(" " + (int) Math.round(Math.clamp(percent, 0, 100)) + "%", SourbyCraftColors.VALUE))
            .build();
    }

    /** TPS bar — colour breakpoints tuned to MC tick budget; full at 20 TPS. */
    public static Component tpsBar(double tps, int width) {
        double pct = Math.clamp(tps / 20.0, 0.0, 1.0) * 100.0;
        TextColor color = tps > 18 ? SourbyCraftColors.SUCCESS
            : tps > 15 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
        return text()
            .append(text(bar(pct, width), color))
            .append(text(" " + (int) Math.round(pct) + "%", SourbyCraftColors.VALUE))
            .build();
    }

    /** RAM percentage bar — inverse semantic, low is healthy. */
    public static Component ramBar(double percent, int width) {
        TextColor color = percent < 50 ? SourbyCraftColors.SUCCESS
            : percent < 80 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.DANGER;
        return text()
            .append(text(bar(percent, width), color))
            .append(text(" " + (int) Math.round(Math.clamp(percent, 0, 100)) + "%", SourbyCraftColors.VALUE))
            .build();
    }

    public static String formatBytes(long bytes) {
        if (bytes <= 0 || bytes == Long.MAX_VALUE) return INFINITY + " MB";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return (bytes / 1024) + " KB";
        if (bytes < 1073741824) return (bytes / 1048576) + " MB";
        return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1073741824.0);
    }
}
