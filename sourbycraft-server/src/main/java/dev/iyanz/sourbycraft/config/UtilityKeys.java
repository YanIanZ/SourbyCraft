package dev.iyanz.sourbycraft.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every key of {@code sourbycraft_config/sourbycraft_global_config.toml} in its Build 49 layout:
 * path, default, lifecycle, the comment written beside it, and the names it had before.
 *
 * <p>This is the single table the seeder, the one-time file migration ({@link ConfigMigration})
 * and the documentation tests read, so a key cannot be renamed, re-defaulted or dropped in one place
 * without the others noticing. The {@code [messages]} lists are owned by
 * {@link dev.iyanz.sourbycraft.lang.SourbyMessages} (their paths did not change); only the section
 * comment for them lives here.</p>
 *
 * <p>Layout (file order): {@code [console]}, {@code [players]}, {@code [messages]},
 * {@code [plugins.viaversion]}, {@code [plugins.protocollib]}, {@code [updater]}. The old names
 * ({@code ui.*}, {@code branding.gc-advisor.enabled}, {@code sourbycraft.*}, {@code viaversion.*},
 * {@code protocollib.*}, {@code misc.auto_update.*}) are read until Build 50, with one WARN per key
 * per load, and rewritten to the new names once at boot.</p>
 */
public final class UtilityKeys {

    private UtilityKeys() {}

    /** The file this table describes, relative to the server directory. */
    public static final String FILE = "sourbycraft_config/sourbycraft_global_config.toml";

    /** A table header comment. */
    public record Section(String path, String comment) {}

    private static ConfigKey key(final String path, final Object defaultValue, final AuroraConfig.Lifecycle lifecycle,
                                 final String comment, final String... legacyPaths) {
        return ConfigKey.of(path, defaultValue, lifecycle, comment, legacyPaths);
    }

    // ------------------------------------------------------------------------------- [console]

    public static final ConfigKey CONSOLE_STYLE = key("console.style", "auto", AuroraConfig.Lifecycle.RESTART_REQUIRED,
        "Character set of the startup lines SourbyCraft builds (section rules, launch and ready lines): auto (rich on an "
            + "interactive terminal, plain otherwise), plain (ASCII dashes and hyphens), rich (Unicode box drawing and em dash). "
            + "-Dsourbycraft.console=plain|rich|auto overrides this. It does not decide colour: the console colours time, level "
            + "and tag only when the terminal supports ANSI (-Dterminal.ansi=false turns that off); redirected output and "
            + "logs/latest.log are never coloured.",
        "ui.console-style");
    public static final ConfigKey PLUGINS_PAGE_SIZE = key("console.plugins-page-size", 12, AuroraConfig.Lifecycle.LIVE,
        "Rows per page of /plugins and /pl, clamped to 1-40.",
        "ui.plugins-page-size");
    public static final ConfigKey GC_ADVISOR = key("console.gc-advisor", true, AuroraConfig.Lifecycle.RESTART_REQUIRED,
        "Print the GC / JVM-flag advisory at startup.",
        "branding.gc-advisor.enabled");

    // ------------------------------------------------------------------------------- [players]

    public static final ConfigKey MAX_PLAYERS = key("players.max-players", 0, AuroraConfig.Lifecycle.RESTART_REQUIRED,
        "Player slots, re-applied at boot so it wins over server.properties; 0 = use server.properties. "
            + "/maxp <n> changes the live count and writes this key, so a value here usually came from /maxp.",
        "sourbycraft.max-players");
    public static final ConfigKey FULL_SERVER_BYPASS = key("players.full-server-bypass", false, AuroraConfig.Lifecycle.RESTART_REQUIRED,
        "Let holders of sourbycraft.maxplayers.bypass (and operators) join a full server. false keeps the fast "
            + "config-phase join path; true registers a PlayerLoginEvent listener that slows every join.",
        "sourbycraft.maxplayers.bypass-enabled");

    // ------------------------------------------------------------------------------- [plugins.*]

    public static final ConfigKey VIAVERSION_AUTO_PROVISION = key("plugins.viaversion.auto-provision", true,
        AuroraConfig.Lifecycle.RESTART_REQUIRED,
        "Install the pinned, SHA-256-verified ViaVersion and ViaBackwards jars and their default configs before the "
            + "plugin scan. false = manage Via yourself (jars this server installed earlier are quarantined as "
            + "*.jar.disabled; operator-installed jars are left alone). Read before the full configuration loads.",
        "viaversion.auto-provision");
    public static final ConfigKey PROTOCOLLIB_AUTO_PROVISION = key("plugins.protocollib.auto-provision", true,
        AuroraConfig.Lifecycle.RESTART_REQUIRED,
        "Install the pinned official ProtocolLib jar before the plugin scan (build-data/protocollib-pin.json). "
            + "Existing operator jars and configs are preserved. Read before the full configuration loads.",
        "protocollib.auto-provision");

    // ------------------------------------------------------------------------------- [updater]

    public static final ConfigKey UPDATER_ENABLED = key("updater.enabled", true, AuroraConfig.Lifecycle.RESTART_REQUIRED,
        "Check the GitHub Releases of the repository below for a newer jar on this build's channel.",
        "misc.auto_update.enabled");
    public static final ConfigKey UPDATER_REPOSITORY = key("updater.repository", "YanIanZ/SourbyCraft", AuroraConfig.Lifecycle.LIVE,
        "GitHub repository to check, as owner/name. Applies at the next check after /sourbycraft reload.",
        "misc.auto_update.repo");
    public static final ConfigKey UPDATER_CHANNEL = key("updater.channel", "", AuroraConfig.Lifecycle.LIVE,
        "Release channel to track: REL, DEV or EXP. Blank = this build's own channel. Applies at the next check.",
        "misc.auto_update.channel");
    public static final ConfigKey UPDATER_APPLY_MODE = key("updater.apply-mode", "notify", AuroraConfig.Lifecycle.LIVE,
        "notify = log and tell operators; stage = download, verify and swap at the next restart; auto = download, "
            + "verify, swap the launch jar and self-restart (keeps <jar>.prev and restores it if the next boot fails); "
            + "off = no checks. A release for another Minecraft version is never staged. Applies at the next check; "
            + "/update apply forces one auto run.",
        "misc.auto_update.apply_mode");
    public static final ConfigKey UPDATER_CHECK_TIMES = key("updater.check-times", List.of("06:00"), AuroraConfig.Lifecycle.RESTART_REQUIRED,
        "Daily check times, HH:mm, server-local time zone.",
        "misc.auto_update.check_times");
    public static final ConfigKey UPDATER_CHECK_INTERVAL_MINUTES = key("updater.check-interval-minutes", 30,
        AuroraConfig.Lifecycle.RESTART_REQUIRED,
        "Also poll every N minutes; 0 = only the daily check-times. The schedule is built once at boot.",
        "misc.auto_update.check_interval_minutes");
    public static final ConfigKey UPDATER_RESTART_MODE = key("updater.restart-mode", "auto", AuroraConfig.Lifecycle.LIVE,
        "How apply-mode = auto restarts after the swap: auto (Pterodactyl detected -> exit 70, else spigot), spigot, "
            + "exit or stop. Applies at the next check.",
        "misc.auto_update.restart_mode");
    public static final ConfigKey UPDATER_RESTART_DELAY_SECONDS = key("updater.restart-delay-seconds", 60, AuroraConfig.Lifecycle.LIVE,
        "Warning window broadcast to players before an automatic restart; at least 5. Applies at the next check.",
        "misc.auto_update.restart_delay_seconds");
    public static final ConfigKey UPDATER_ALLOW_PRERELEASE = key("updater.allow-prerelease", false, AuroraConfig.Lifecycle.LIVE,
        "Whether GitHub prereleases on the tracked channel are eligible. Applies at the next check.",
        "misc.auto_update.allow_prerelease");
    public static final ConfigKey UPDATER_VIA_AUTO_UPDATE = key("updater.via-auto-update", true, AuroraConfig.Lifecycle.LIVE,
        "Also keep the auto-provisioned ViaVersion and ViaBackwards current on the same cadence "
            + "(needs plugins.viaversion.auto-provision). Applies at the next check.",
        "misc.auto_update.via_auto_update");

    /** Every key, in file order. */
    public static final List<ConfigKey> KEYS = List.of(
        CONSOLE_STYLE, PLUGINS_PAGE_SIZE, GC_ADVISOR,
        MAX_PLAYERS, FULL_SERVER_BYPASS,
        VIAVERSION_AUTO_PROVISION, PROTOCOLLIB_AUTO_PROVISION,
        UPDATER_ENABLED, UPDATER_REPOSITORY, UPDATER_CHANNEL, UPDATER_APPLY_MODE, UPDATER_CHECK_TIMES,
        UPDATER_CHECK_INTERVAL_MINUTES, UPDATER_RESTART_MODE, UPDATER_RESTART_DELAY_SECONDS, UPDATER_ALLOW_PRERELEASE,
        UPDATER_VIA_AUTO_UPDATE);

    /** The file header, written as the comment of the first table. */
    public static final String HEADER = String.join("\n",
        "SourbyCraft server settings (sourbycraft_config/sourbycraft_global_config.toml) - Build 49 layout.",
        "",
        "Sections: [console] startup console and command presentation; [players] slots and full-server bypass;",
        "[messages] varied MiniMessage lines; [plugins.*] verified plugin provisioning; [updater] the auto-updater.",
        "Every key states its lifecycle and default: LIVE = /sourbycraft reload applies it;",
        "RESTART_REQUIRED = read once at startup (or, for the updater, when its schedule is built).",
        "",
        "Not in this file: Aurora services (CPU budget, bridge, scheduler budgets, AWF, diagnostics) live in",
        "sourbycraft_config/aurora.toml; the region engine in config/aurora-server.yml and config/aurora-worlds.yml;",
        "network limits in sourbycraft-security.yml. Reference: docs/AURORA-CONFIG.md.",
        "",
        "Build 49 migrated the previous layout ([ui], [branding], [sourbycraft], [viaversion], [protocollib],",
        "[misc.auto_update]) once; the file as it was is kept beside this one as *.migrated. Old key names are",
        "still read until Build 50, with one WARN per key.",
        "");

    /** Table comments, in file order. */
    public static final List<Section> SECTIONS = List.of(
        new Section("console", HEADER + "\nStartup console and command presentation."),
        new Section("players", "Player slots. /maxp changes the live slot count and persists it here."),
        new Section("messages", "Varied server messages: each key is a LIST of MiniMessage/hex variants and one is picked at "
            + "random per event. <player> is the player's name in join/leave. [] (an empty list) disables an optional "
            + "message; remove a key to restore the built-in variants."),
        new Section("plugins", "Plugins the server installs itself (pinned versions, SHA-256-verified, https only; nothing "
            + "is downloaded with -Dsourbyclip.offline=true). RESTART_REQUIRED: read before the plugin scan."),
        new Section("updater", "SourbyCraft auto-updater (docs/development/AUTO-UPDATER.md). Checks GitHub Releases on this "
            + "build's channel; apply-mode decides what happens with a newer jar."));

    /**
     * Keys nothing has read since Build 44 (automatic memory tuning). The migration removes them from
     * the file after the backup and says so; they are never read again.
     */
    public static final List<String> RETIRED_PREFIXES = List.of("perf.smart-swap", "swap.auto-create");

    /** Old path to current path, for every key that was renamed. */
    public static Map<String, String> legacyToCurrent() {
        final Map<String, String> map = new LinkedHashMap<>();
        for (final ConfigKey key : KEYS) {
            for (final String legacy : key.legacyPaths()) {
                map.put(legacy, key.path());
            }
        }
        return map;
    }

    /** The current paths, in file order. */
    public static List<String> paths() {
        final List<String> paths = new ArrayList<>(KEYS.size());
        for (final ConfigKey key : KEYS) paths.add(key.path());
        return List.copyOf(paths);
    }

    /** The key at {@code path}, or {@code null}. */
    public static ConfigKey byPath(final String path) {
        for (final ConfigKey key : KEYS) {
            if (key.path().equals(path)) return key;
        }
        return null;
    }
}
