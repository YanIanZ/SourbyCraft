package dev.iyanz.sourbycraft.brand;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import net.kyori.adventure.text.format.TextColor;

/**
 * Branded startup banner printed to the console at server start.
 *
 * <p>Ported from the Paper tag {@code paper-26.2-pre-folia} (where it was a plain
 * monochrome box). On this Canvas re-platform it is emitted from
 * {@link dev.iyanz.sourbycraft.core.SourbyCraftBootstrap}, itself called from a small
 * hand-authored {@code minecraft-patch} to {@code DedicatedServer#initServer}.
 *
 * <p>The box is colored with ANSI 24-bit truecolor escapes derived from
 * {@link SourbyCraftColors} (PRIMARY {@code #FFB347} for the frame, SUCCESS
 * {@code #77DD77} / INFO {@code #AEC6CF} for the body). Paper/Folia's JLine
 * console renders these on any truecolor-capable terminal; on a terminal that
 * does not understand them the sequences are inert and the text still reads.
 */
public final class SourbyCraftBanner {

    private SourbyCraftBanner() {}

    private static final String ESC = "\u001B";
    private static final String RESET = ESC + "[0m";

    /** Build an ANSI 24-bit foreground escape from an Adventure {@link TextColor}. */
    private static String fg(TextColor c) {
        return ESC + "[38;2;" + c.red() + ";" + c.green() + ";" + c.blue() + "m";
    }

    private static final String FRAME = fg(SourbyCraftColors.PRIMARY);
    private static final String TITLE = fg(SourbyCraftColors.HEADER);
    private static final String BODY  = fg(SourbyCraftColors.INFO);
    private static final String OK    = fg(SourbyCraftColors.SUCCESS);

    /**
     * Renders the branded ANSI-truecolor startup box for {@code info} as a single multi-line
     * string (leading newline included), ready to print straight to a console stream.
     */
    public static String render(BuildInfo info) {
        final String java = System.getProperty("java.specification.version");
        final int cores = Runtime.getRuntime().availableProcessors();
        StringBuilder sb = new StringBuilder();
        sb.append('\n');
        final String shownVersion = info.displayVersion();
        sb.append(FRAME).append("   ┌─ ").append(TITLE).append("SOURBYCRAFT ").append(FRAME)
          .append("─".repeat(Math.max(0, 44 - shownVersion.length())))
          .append(' ').append(OK).append(shownVersion).append(' ').append(FRAME).append("─┐").append(RESET).append('\n');
        sb.append(FRAME).append("   │  ").append(BODY).append(pad(info.tagline(), 58)).append(FRAME).append("│").append(RESET).append('\n');
        sb.append(FRAME).append("   │  ").append(BODY).append(pad("Canvas engine benchmark · utilities only", 58)).append(FRAME).append("│").append(RESET).append('\n');
        sb.append(FRAME).append("   │  ").append(BODY).append(pad("Canvas " + info.mcVersion() + "  ·  Java " + java + "  ·  " + cores + " cores", 58)).append(FRAME).append("│").append(RESET).append('\n');
        sb.append(FRAME).append("   └").append("─".repeat(62)).append("┘").append(RESET).append('\n');
        return sb.toString();
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }
}
