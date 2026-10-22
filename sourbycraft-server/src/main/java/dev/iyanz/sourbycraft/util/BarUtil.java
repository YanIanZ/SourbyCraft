package dev.iyanz.sourbycraft.util;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

import static net.kyori.adventure.text.Component.text;

/** Compact gauges for command panels and HUDs. Non-finite measurements stay unknown. */
public final class BarUtil {

    public static final String FILLED   = "█";
    public static final String EMPTY    = "·";
    public static final String INFINITY = "∞";

    public static final int DEFAULT_WIDTH = 18;

    private BarUtil() {}

    /** Raw bar string with no colour, no percentage. */
    public static String bar(double percent, int width) {
        width = Math.clamp(width, 1, 80);
        if (!Double.isFinite(percent)) return "[" + "?".repeat(width) + "]";
        int filled = (int) Math.round(Math.clamp(percent, 0, 100) / 100.0 * width);
        return "[" + FILLED.repeat(filled) + EMPTY.repeat(width - filled) + "]";
    }

    /** {@code bar(percent, DEFAULT_WIDTH) + " 51%"} with the clamped percentage; unavailable measurements remain explicit. */
    public static String barWithPercent(double percent, int width) {
        return bar(percent, width) + " " + percentage(percent);
    }

    /** Generic percentage bar, severity coloured (success / primary / danger). */
    public static Component coloredBar(double percent, int width) {
        TextColor color = !Double.isFinite(percent) ? SourbyCraftColors.DIM : percent > 80 ? SourbyCraftColors.DANGER
            : percent > 50 ? SourbyCraftColors.WARNING : SourbyCraftColors.SUCCESS;
        return text()
            .append(text(bar(percent, width), color))
            .append(text(" " + percentage(percent), SourbyCraftColors.VALUE))
            .build();
    }

    /** TPS bar — colour breakpoints tuned to MC tick budget; full at 20 TPS. */
    public static Component tpsBar(double tps, int width) {
        double pct = Double.isFinite(tps) ? Math.clamp(tps / 20.0, 0.0, 1.0) * 100.0 : Double.NaN;
        TextColor color = !Double.isFinite(tps) ? SourbyCraftColors.DIM : tps > 18 ? SourbyCraftColors.SUCCESS
            : tps > 15 ? SourbyCraftColors.WARNING : SourbyCraftColors.DANGER;
        return text()
            .append(text(bar(pct, width), color))
            .append(text(" " + percentage(pct), SourbyCraftColors.VALUE))
            .build();
    }

    /** RAM percentage bar — inverse semantic, low is healthy. */
    public static Component ramBar(double percent, int width) {
        TextColor color = !Double.isFinite(percent) ? SourbyCraftColors.DIM : percent < 50 ? SourbyCraftColors.SUCCESS
            : percent < 80 ? SourbyCraftColors.WARNING : SourbyCraftColors.DANGER;
        return text()
            .append(text(bar(percent, width), color))
            .append(text(" " + percentage(percent), SourbyCraftColors.VALUE))
            .build();
    }

    private static String percentage(double value) {
        return Double.isFinite(value) ? Math.round(Math.clamp(value, 0, 100)) + "%" : "unavailable";
    }

    /** Human-readable byte count (B/KB/MB/GB, 1024-based); {@link #INFINITY} for an unlimited maximum or {@link Long#MAX_VALUE}. */
    public static String formatBytes(long bytes) {
        if (bytes == Long.MAX_VALUE) return INFINITY;
        if (bytes < 0) return "unavailable";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return (bytes / 1024) + " KB";
        if (bytes < 1073741824) return (bytes / 1048576) + " MB";
        return String.format(java.util.Locale.ROOT, "%.1f GB", bytes / 1073741824.0);
    }
}
