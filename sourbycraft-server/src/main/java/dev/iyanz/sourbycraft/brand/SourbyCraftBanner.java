package dev.iyanz.sourbycraft.brand;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import java.util.Locale;
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
    private static final String DIM   = fg(SourbyCraftColors.DIM);
    private static final String OK    = fg(SourbyCraftColors.SUCCESS);

    /**
     * Renders the branded ANSI-truecolor startup box for {@code info} as a single multi-line
     * string (leading newline included), ready to print straight to a console stream.
     */
    /** Width of the box interior, excluding the frame characters. */
    private static final int WIDTH = 58;

    /**
     * Renders the branded startup box for {@code info}.
     *
     * <p>The frame is swept through the Aurora gradient character by character, which is the
     * one place AURORA-UX section 1 allows a gradient: a header. The body lines are flat, and
     * nothing here animates or sleeps — the box is printed once, already complete.
     */
    public static String render(BuildInfo info) {
        final String javaVersion = System.getProperty("java.specification.version");
        final int cores = Runtime.getRuntime().availableProcessors();
        final String engine = info.engineName();
        final String shownVersion = info.displayVersion();

        final StringBuilder sb = new StringBuilder();
        sb.append('\n');
        sb.append(sweep("   ╭" + "─".repeat(WIDTH + 2) + "╮")).append(RESET).append('\n');
        sb.append(row(centre("SOURBYCRAFT · " + engine.toUpperCase(Locale.ROOT)), TITLE));
        sb.append(row(centre("Java " + javaVersion + " · Minecraft " + info.mcVersion()
            + " · " + shownVersion), BODY));
        sb.append(row(centre(info.tagline()), BODY));
        sb.append(sweep("   ╰" + "─".repeat(WIDTH + 2) + "╯")).append(RESET).append('\n');
        // Environment line sits below the box, unframed: it is reference detail rather than
        // identity, and the spec keeps the box itself to the identity rows.
        sb.append(DIM).append("   ").append(engine).append(" engine · ")
          .append(cores).append(" cores · ")
          .append(Runtime.getRuntime().maxMemory() / (1024L * 1024L)).append(" MiB heap")
          .append(RESET).append('\n');
        return sb.toString();
    }

    /** One framed line, with the frame drawn in the gradient's end colours. */
    private static String row(final String text, final String colour) {
        return FRAME + "   │ " + colour + pad(text, WIDTH) + FRAME + " │" + RESET + "\n";
    }

    private static String centre(final String text) {
        if (text.length() >= WIDTH) {
            return text;
        }
        final int left = (WIDTH - text.length()) / 2;
        return " ".repeat(left) + text;
    }

    /**
     * Sweeps a string through the Aurora gradient, one colour step per character.
     *
     * <p>Used only for the two frame rules. A terminal without truecolor renders the escapes
     * as nothing and the rule still draws.
     */
    private static String sweep(final String text) {
        final StringBuilder out = new StringBuilder(text.length() * 12);
        final int span = Math.max(1, text.length() - 1);
        for (int i = 0; i < text.length(); i++) {
            final TextColor colour = SourbyCraftColors.lerp(
                SourbyCraftColors.AURORA_DEEP, SourbyCraftColors.AURORA, (double)i / span);
            out.append(fg(colour)).append(text.charAt(i));
        }
        return out.toString();
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s.substring(0, width);
        return s + " ".repeat(width - s.length());
    }
}
