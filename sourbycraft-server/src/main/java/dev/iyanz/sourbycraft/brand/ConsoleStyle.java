package dev.iyanz.sourbycraft.brand;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import java.util.Locale;
import net.kyori.adventure.text.format.TextColor;

/** Console-only presentation. No cursor movement, timing work or runtime tuning. */
public record ConsoleStyle(boolean color, boolean unicode) {
    public static ConsoleStyle current() {
        return resolve(System.getProperty("sourbycraft.console",
            SourbyCraftConfig.cfgGet("ui.console-style", "auto")), System.console() != null,
            System.getenv("NO_COLOR") != null, "dumb".equals(System.getenv("TERM")));
    }

    public static ConsoleStyle resolve(String mode, boolean interactive, boolean noColor, boolean dumb) {
        String value = mode.toLowerCase(Locale.ROOT);
        boolean rich = value.equals("rich") || value.equals("auto") && interactive;
        return new ConsoleStyle(rich && !noColor && !dumb, rich);
    }

    public String paint(String value, TextColor color) {
        return this.color ? "\u001b[38;2;" + color.red() + ";" + color.green() + ";" + color.blue()
            + "m" + value + "\u001b[0m" : value;
    }

    public String rule() { return (unicode ? "─" : "-").repeat(60); }

    public static String singleLine(String value) {
        return value == null ? "unavailable" : value.replaceAll("[\\p{Cc}\\p{Cf}]", " ").strip();
    }
}
