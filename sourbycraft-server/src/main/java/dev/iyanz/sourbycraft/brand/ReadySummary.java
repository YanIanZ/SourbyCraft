package dev.iyanz.sourbycraft.brand;

import dev.iyanz.sourbycraft.execution.ThreadLedger;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.util.Locale;

/**
 * The one-line summary under the {@code Ready} rule, logged when the server fires
 * {@code ServerLoadEvent(STARTUP)} (worlds and plugins are up; Minecraft prints {@code Done (}
 * right after):
 * <pre>
 * SourbyCraft Build 48 - Aurora Nexus (DEV, 26.2@432d141) | Minecraft 26.2 | ready 16.5 s after JVM start (cold start; no previous profile) | 3 worlds | 1/1 plugins enabled | 22 configured threads on 8 processors
 * </pre>
 *
 * @param release        {@link BuildInfo#releaseIdentity()}
 * @param minecraft      Minecraft version id
 * @param readyMillis    JVM start to the server-load event (the startup profile's total)
 * @param comparison     the profile's comparison with the previous boot, may be empty
 * @param worlds         loaded worlds, or -1 when unknown
 * @param pluginsEnabled enabled plugins, or -1 when unknown
 * @param pluginsTotal   loaded plugins, or -1 when unknown
 * @param threads        configured platform threads from the thread ledger, or -1 when unknown
 * @param processors     available processors
 */
public record ReadySummary(String release, String minecraft, long readyMillis, String comparison,
                           int worlds, int pluginsEnabled, int pluginsTotal, int threads, int processors) {

    public String render(final ConsoleStyle style) {
        final String identity = style.unicode() ? this.release : this.release.replace('—', '-');
        final String shown = ConsoleStyle.singleLine(identity);
        final String painted = style.color() ? style.paint(shown, stageColor(shown)) : shown;
        final StringBuilder line = new StringBuilder(200)
            .append("SourbyCraft ").append(painted)
            .append(" | Minecraft ").append(ConsoleStyle.singleLine(this.minecraft))
            .append(" | ready ").append(seconds(this.readyMillis)).append(" after JVM start");
        if (this.comparison != null && !this.comparison.isBlank()) {
            line.append(" (").append(ConsoleStyle.singleLine(this.comparison)).append(')');
        }
        line.append(" | ").append(count(this.worlds, "world", "worlds"));
        if (this.pluginsTotal < 0) {
            line.append(" | plugins unavailable");
        } else {
            line.append(" | ").append(Math.max(0, this.pluginsEnabled)).append('/').append(this.pluginsTotal)
                .append(this.pluginsTotal == 1 ? " plugin enabled" : " plugins enabled");
        }
        line.append(" | ").append(this.threads < 0 ? "threads unavailable" : this.threads + " configured threads")
            .append(" on ").append(Math.max(0, this.processors)).append(this.processors == 1 ? " processor" : " processors");
        return line.toString();
    }

    static String seconds(final long millis) {
        return String.format(Locale.ROOT, "%.1f s", Math.max(0L, millis) / 1000.0);
    }

    /** Red for a prerelease identity, amber for a dev/experimental one, green for a release. */
    static net.kyori.adventure.text.format.TextColor stageColor(final String identity) {
        if (identity.endsWith("PR!")) return dev.iyanz.sourbycraft.SourbyCraftColors.DANGER;
        if (identity.contains("(DEV") || identity.contains("(EXP") || identity.contains("(UNKNOWN")) {
            return dev.iyanz.sourbycraft.SourbyCraftColors.WARNING;
        }
        return dev.iyanz.sourbycraft.SourbyCraftColors.SUCCESS;
    }

    private static String count(final int n, final String one, final String many) {
        return n < 0 ? many + " unavailable" : n + " " + (n == 1 ? one : many);
    }

    /**
     * Collects the facts from the running server and logs the rule and the summary line. Every
     * lookup is guarded: a count that cannot be read prints as unavailable, and nothing here can
     * fail the boot.
     */
    public static void log(final long readyMillis, final String comparison) {
        final ReadySummary summary = collect(readyMillis, comparison);
        ConsoleSections.ready();
        SourbyLogger.info(summary.render(ConsoleStyle.current()));
    }

    static ReadySummary collect(final long readyMillis, final String comparison) {
        String release = "Build ?";
        String minecraft = "?";
        try {
            final BuildInfo info = BuildInfo.load();
            release = info.releaseIdentity();
            minecraft = info.mcVersion() == null || info.mcVersion().isBlank() ? "?" : info.mcVersion();
        } catch (final Throwable ignored) {
            // fallbacks
        }
        int worlds = -1;
        try {
            worlds = org.bukkit.Bukkit.getWorlds().size();
        } catch (final Throwable ignored) {
            // no server
        }
        int pluginsTotal = -1;
        int pluginsEnabled = -1;
        try {
            final org.bukkit.plugin.Plugin[] plugins = org.bukkit.Bukkit.getPluginManager().getPlugins();
            pluginsTotal = plugins.length;
            int enabled = 0;
            for (final org.bukkit.plugin.Plugin plugin : plugins) {
                if (plugin.isEnabled()) enabled++;
            }
            pluginsEnabled = enabled;
        } catch (final Throwable ignored) {
            // no server
        }
        int threads = -1;
        try {
            int total = 0;
            for (final ThreadLedger.Pool pool : ThreadLedger.collect()) {
                if (pool.threads() > 0) total += pool.threads();
            }
            threads = total;
        } catch (final Throwable ignored) {
            // ledger unavailable
        }
        return new ReadySummary(release, minecraft, readyMillis, comparison, worlds, pluginsEnabled, pluginsTotal,
            threads, Runtime.getRuntime().availableProcessors());
    }
}
