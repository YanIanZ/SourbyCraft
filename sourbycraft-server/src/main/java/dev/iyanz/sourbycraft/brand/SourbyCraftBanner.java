package dev.iyanz.sourbycraft.brand;

import dev.iyanz.sourbycraft.SourbyCraftColors;

/** One compact identity card. Long metadata is never cut off to fit a decorative frame. */
public final class SourbyCraftBanner {
    private SourbyCraftBanner() {}

    public static String render(BuildInfo info) {
        return render(info, ConsoleStyle.current());
    }

    public static String render(BuildInfo info, ConsoleStyle style) {
        String prefix = style.unicode() ? "│ " : "  ";
        return "\n" + style.rule() + "\n"
            + style.paint("SOURBYCRAFT  /  AURORA ENGINE", SourbyCraftColors.HEADER) + "\n"
            + prefix + "Release    " + ConsoleStyle.singleLine(info.releaseIdentity()) + "\n"
            + prefix + "Minecraft  " + info.mcVersion() + "  |  Channel " + info.version() + "\n"
            + prefix + "Runtime    Java " + Runtime.version().feature() + "  |  "
                + Runtime.getRuntime().availableProcessors() + " JVM-visible processors\n"
            + prefix + "Heap       " + Runtime.getRuntime().maxMemory() / (1024L * 1024L) + " MiB maximum\n"
            + style.rule() + "\n"
            + style.paint("Boot progress measures Aurora service stages; Minecraft readiness follows later.",
                SourbyCraftColors.LABEL) + "\n";
    }
}
