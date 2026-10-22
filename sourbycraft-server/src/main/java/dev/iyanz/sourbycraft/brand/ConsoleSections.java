package dev.iyanz.sourbycraft.brand;

import dev.iyanz.sourbycraft.util.SourbyLogger;

/**
 * The section rules that structure the boot console: {@code -- Engine ----...} before the
 * engine stages, {@code Plugins} before Paper loads plugins, {@code Worlds} before the levels are
 * prepared, {@code Plugins (post-world)} before the POSTWORLD enable pass and {@code Ready} before
 * the ready summary. Each is one INFO line on the {@code SourbyCraft} logger, append-only, no
 * cursor movement, so a hosted panel shows it as it is.
 *
 * <p>The rule uses box-drawing characters only when {@link ConsoleStyle#unicode()} is true (an
 * interactive console or {@code console.style = rich}); otherwise plain ASCII dashes, so a
 * server started under the C locale prints no {@code ?}. Call sites in Paper/Minecraft code
 * (minecraft feature patch 0078) are one line each and go through {@link #print(String)}, which
 * never throws: a decoration must not take the server down.
 */
public final class ConsoleSections {

    /** Total width of a rule, including the title. */
    static final int WIDTH = 56;

    private ConsoleSections() {}

    /** Before the engine's boot stages (from {@code SourbyCraftBootstrap#init()}). */
    public static void engine() { print("Engine"); }

    /** Before Paper's {@code loadPlugins()} + STARTUP enable pass. */
    public static void plugins() { print("Plugins"); }

    /** Before {@code Preparing level "<name>"}. */
    public static void worlds() { print("Worlds"); }

    /** Before the POSTWORLD enable pass, where most plugins come up. */
    public static void pluginsPostWorld() { print("Plugins (post-world)"); }

    /** Before the ready summary, which precedes Minecraft's {@code Done (} line. */
    public static void ready() { print("Ready"); }

    /** {@code -- Title ---...} padded to {@link #WIDTH}; a long title is never cut. */
    public static String rule(final String title, final ConsoleStyle style) {
        final String dash = style.unicode() ? "─" : "-";
        final String name = ConsoleStyle.singleLine(title);
        final StringBuilder rule = new StringBuilder(WIDTH + 8).append(dash).append(dash).append(' ').append(name).append(' ');
        while (rule.length() < WIDTH) {
            rule.append(dash);
        }
        return rule.toString();
    }

    private static void print(final String title) {
        try {
            SourbyLogger.info(rule(title, ConsoleStyle.current()));
        } catch (final Throwable ignored) {
            // presentation only; never break boot over a rule line
        }
    }
}
