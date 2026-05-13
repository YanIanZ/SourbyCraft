package dev.iyanz.sourbycraft.util;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;

import static net.kyori.adventure.text.Component.text;

public final class BarUtil {
    public static final String FILLED = "█";
    public static final String EMPTY = "░";
    public static final String INFINITY = "∞";

    public static String bar(double percent, int width) {
        int filled = (int) (Math.clamp(percent, 0, 100) / 100.0 * width);
        return FILLED.repeat(filled) + EMPTY.repeat(width - filled);
    }

    public static Component coloredBar(double percent, int width) {
        TextColor color = percent > 80 ? SourbyCraftColors.DANGER
            : percent > 50 ? SourbyCraftColors.PRIMARY : SourbyCraftColors.SUCCESS;
        return text(bar(percent, width), color);
    }

    public static String formatBytes(long bytes) {
        if (bytes <= 0 || bytes == Long.MAX_VALUE) return INFINITY + " MB";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return (bytes / 1024) + " KB";
        if (bytes < 1073741824) return (bytes / 1048576) + " MB";
        return String.format("%.1f GB", bytes / 1073741824.0);
    }

    private BarUtil() {}
}
