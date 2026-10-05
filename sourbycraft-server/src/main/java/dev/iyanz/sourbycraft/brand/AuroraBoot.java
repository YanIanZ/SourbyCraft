package dev.iyanz.sourbycraft.brand;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import java.util.Locale;

/** Append-only progress based on finished service stages, with per-stage failure evidence. */
public final class AuroraBoot {
    static final int CELLS = 16;
    private AuroraBoot() {}

    public record Stage(String name, boolean successful, long millis) {}

    public static String render(int done, int total, String stage, boolean failed) {
        return render(done, total, new Stage(stage, !failed, 0), failed ? 1 : 0, ConsoleStyle.current());
    }

    public static String render(int done, int total, Stage stage, int failures, ConsoleStyle style) {
        int safeTotal = Math.max(0, total);
        int safeDone = Math.clamp(done, 0, safeTotal);
        int percent = safeTotal == 0 ? 0 : (int) Math.round(100.0 * safeDone / safeTotal);
        int filled = safeTotal == 0 ? 0 : (int) Math.round((double) CELLS * safeDone / safeTotal);
        String bar = "[" + (style.unicode() ? "█" : "=").repeat(filled)
            + (style.unicode() ? "·" : ".").repeat(CELLS - filled) + "]";
        var barColor = failures > 0 ? SourbyCraftColors.WARNING : SourbyCraftColors.AURORA;
        String result = style.paint(String.format(Locale.ROOT, "%2d/%-2d %3d%% %s", safeDone, safeTotal, percent, bar), barColor)
            + "  " + style.paint(stage.successful() ? "OK  " : "FAIL", stage.successful()
                ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DANGER)
            + "  " + ConsoleStyle.singleLine(stage.name()).toLowerCase(Locale.ROOT)
            + "  (" + Math.max(0, stage.millis()) + "ms)";
        return failures > 0 ? result + "  | " + failures + " failed" : result;
    }

    public static String summary(int total, int failures, long millis) {
        String verdict = failures == 0 ? "COMPLETE" : failures >= total ? "FAILED" : "DEGRADED";
        return ConsoleStyle.current().paint(verdict + " | " + Math.max(0, total - failures) + "/" + total
            + " stages completed without error | " + failures + " failed | " + Math.max(0, millis) + "ms",
            failures > 0 ? SourbyCraftColors.DANGER : SourbyCraftColors.SUCCESS)
            + "\nMinecraft continues loading; wait for the server-ready message before connecting.";
    }
}
