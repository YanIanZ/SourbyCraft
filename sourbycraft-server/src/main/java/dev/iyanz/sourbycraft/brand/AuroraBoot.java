package dev.iyanz.sourbycraft.brand;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import java.util.Locale;
import net.kyori.adventure.text.format.TextColor;

/**
 * The Aurora engine's boot progress, drawn as a bar that means something.
 *
 * <p>The percentage is stages completed over stages declared, not elapsed time. A bar driven by a
 * timer is decoration: it reaches 100% whether or not the thing it claims to measure worked, and
 * it tells an operator watching a slow start nothing about which stage is slow. This one advances
 * only when a stage actually finishes, names the stage it just finished, and keeps a failure
 * visible instead of quietly filling to full.</p>
 *
 * <p>Colour is 24-bit truecolor swept from {@link SourbyCraftColors#AURORA} to
 * {@link SourbyCraftColors#AURORA_DEEP}, with {@link SourbyCraftColors#CRITICAL} once a stage has
 * failed. On a terminal without truecolor the escapes are inert and the bar still reads. Nothing
 * animates, sleeps or redraws: each line is printed once, already complete, so the log stays a
 * log.</p>
 */
public final class AuroraBoot {

    private AuroraBoot() {}

    private static final String ESC = "\u001B";
    private static final String RESET = ESC + "[0m";
    /** Bar cells: wide enough to read a percentage off, narrow enough for a log line. */
    static final int CELLS = 36;
    private static final char FILLED = '\u2588';
    private static final char EMPTY = '\u2591';

    private static String fg(final TextColor colour) {
        return ESC + "[38;2;" + colour.red() + ";" + colour.green() + ";" + colour.blue() + "m";
    }

    /**
     * One progress line.
     *
     * @param done   stages finished, clamped into range
     * @param total  stages declared; a non-positive total renders an empty bar rather than dividing
     * @param stage  what just finished
     * @param failed whether any stage so far failed, which colours the bar
     * @return the line, without a trailing newline
     */
    public static String render(final int done, final int total, final String stage, final boolean failed) {
        final int safeTotal = Math.max(0, total);
        final int safeDone = Math.max(0, Math.min(done, safeTotal));
        final int percent = safeTotal == 0 ? 0 : (int) Math.round(100.0 * safeDone / safeTotal);
        final int filled = safeTotal == 0 ? 0 : (int) Math.round((double) CELLS * safeDone / safeTotal);

        final StringBuilder bar = new StringBuilder(CELLS * 24);
        for (int cell = 0; cell < CELLS; cell++) {
            if (cell < filled) {
                // Sweep the filled run so the bar reads as one gradient rather than a slab of
                // colour; unfilled cells stay dim so the remaining work is still visible.
                bar.append(fg(failed ? SourbyCraftColors.CRITICAL
                        : lerp(SourbyCraftColors.AURORA, SourbyCraftColors.AURORA_DEEP,
                            CELLS == 1 ? 0.0 : (double) cell / (CELLS - 1))))
                    .append(FILLED);
            } else {
                bar.append(fg(SourbyCraftColors.DIM)).append(EMPTY);
            }
        }

        return fg(SourbyCraftColors.AURORA) + "  " + bar + RESET
            + fg(SourbyCraftColors.HEADER) + String.format(Locale.ROOT, " %3d%%", percent)
            + fg(SourbyCraftColors.DIM) + "  " + stage.toLowerCase(Locale.ROOT) + RESET;
    }

    /**
     * The closing line: what the engine ended up as.
     *
     * @param total    stages declared
     * @param failures stages that threw
     * @param millis   wall time the sequence took
     */
    public static String summary(final int total, final int failures, final long millis) {
        final TextColor colour = failures == 0 ? SourbyCraftColors.AURORA : SourbyCraftColors.CRITICAL;
        final String verdict = failures == 0
            ? total + " stages online"
            : failures + " of " + total + " stages degraded";
        return fg(colour) + "  " + verdict
            + fg(SourbyCraftColors.DIM) + "  " + millis + "ms" + RESET;
    }

    private static TextColor lerp(final TextColor from, final TextColor to, final double amount) {
        final double t = Math.max(0.0, Math.min(1.0, amount));
        return TextColor.color(
            (int) Math.round(from.red() + (to.red() - from.red()) * t),
            (int) Math.round(from.green() + (to.green() - from.green()) * t),
            (int) Math.round(from.blue() + (to.blue() - from.blue()) * t));
    }
}
