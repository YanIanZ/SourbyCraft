package dev.iyanz.sourbycraft.brand;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import java.nio.file.Path;
import java.util.Locale;
import net.kyori.adventure.text.format.TextColor;

/** Console-only presentation. No cursor movement, timing work or runtime tuning. */
public record ConsoleStyle(boolean color, boolean unicode) {

    private static final Path UNIFIED_FILE = Path.of("sourbycraft_config", "sourbycraft_global_config.yml");

    /** {@code console.style} read from the file before the configuration loaded; {@code null} until first needed. */
    private static volatile String earlyMode;

    /**
     * The style for this console: {@code -Dsourbycraft.console}, else {@code console.style}, else {@code auto}.
     *
     * <p>{@code console.style} comes from the published {@link SourbyCraftConfig} snapshot once it holds the key
     * (seeding puts every current key in memory when the file opens). Before that -- the launch lines and the
     * Engine rule print before the configuration stage -- it is read once from
     * {@code sourbycraft_config/sourbycraft_global_config.yml} (or its {@code .toml} predecessor until that is
     * converted) and cached; a missing or unreadable file, or a value that is not text, reads as {@code auto}.</p>
     */
    public static ConsoleStyle current() {
        final String property = System.getProperty("sourbycraft.console");
        return resolve(property != null ? property : configuredMode(), System.console() != null,
            System.getenv("NO_COLOR") != null, "dumb".equals(System.getenv("TERM")));
    }

    private static String configuredMode() {
        final String key = dev.iyanz.sourbycraft.config.UtilityKeys.CONSOLE_STYLE.path();
        if (SourbyCraftConfig.cfgGet(key, null) != null) {
            return SourbyCraftConfig.cfgGet(key, "auto");
        }
        String mode = earlyMode;
        if (mode == null) {
            mode = readEarly(key);
            earlyMode = mode;
        }
        return mode;
    }

    private static String readEarly(final String key) {
        try {
            final Path file = dev.iyanz.sourbycraft.config.ConfigSources.existing(UNIFIED_FILE);
            if (file == null) return "auto";
            final Object value = dev.iyanz.sourbycraft.config.ConfigSources.read(file).get(key);
            return value instanceof String mode ? mode : "auto";
        } catch (final Exception | LinkageError unreadable) {
            // Presentation only: the configuration stage names a broken file with its line when it loads.
            return "auto";
        }
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
