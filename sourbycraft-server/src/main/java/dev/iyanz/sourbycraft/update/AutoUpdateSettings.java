package dev.iyanz.sourbycraft.update;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import dev.iyanz.sourbycraft.SourbyCraftConfig;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Plain config surface for the SourbyCraft auto-updater (F1-8), replacing the archived Folia
 * build's {@code me.earthme.luminol.config.modules.misc.AutoUpdateConfig} — a Luminol
 * {@code @ConfigClassInfo}/{@code @ConfigInfo}-annotated module resolved by reflection. On this
 * Canvas re-platform benchmark build there is no Luminol config manager, so these are plain
 * static fields, seeded into + read back from the same unified TOML
 * {@link SourbyCraftConfig} manages (under {@code misc.auto_update.*}, matching the archived
 * key layout so an existing install's file keeps working).
 *
 * <p>{@link #seedDefaults} is called once from {@link SourbyCraftConfig}'s boot-time seed pass;
 * {@link #loadFromToml()} is called from {@link SourbyCraftConfig#init()} / {@code #reload()} to
 * refresh the static fields {@link SourbyUpdater}'s inner {@code Config} bridge reads.
 */
public final class AutoUpdateSettings {

    public static volatile boolean enabled = true;
    public static volatile String repo = "YanIanZ/SourbyCraft";
    public static volatile String channel = "";
    public static volatile String applyMode = "auto";
    public static volatile List<String> checkTimes = List.of("06:00");
    public static volatile int checkIntervalMinutes = 30;
    public static volatile String restartMode = "auto";
    public static volatile int restartDelaySeconds = 60;
    public static volatile boolean allowPrerelease = false;
    public static volatile boolean viaAutoUpdate = true;

    private static volatile SourbyUpdater instance;

    private AutoUpdateSettings() {}

    /** Seed the {@code misc.auto_update.*} keys into the unified TOML when absent. */
    public static void seedDefaults(CommentedFileConfig f, boolean[] changed) {
        if (f.getComment("misc") == null) {
            f.setComment("misc", "Misc SourbyCraft settings.");
        }
        if (f.getComment("misc.auto_update") == null) {
            f.setComment("misc.auto_update",
                "SourbyCraft auto-updater. Checks the SourbyCraft GitHub repo's Releases for a newer jar "
                + "on the SAME release channel as this build (REL/DEV/EXP). apply_mode controls what "
                + "happens: 'auto' downloads+verifies+swaps the launch jar and self-restarts; 'stage' "
                + "downloads+verifies+stages for the next restart; 'notify' only logs+notifies operators; "
                + "'off' disables checks.");
        }
        SourbyCraftConfig.seed(f, changed, "misc.auto_update.enabled", true,
            "Whether SourbyCraft should check for updates automatically.");
        SourbyCraftConfig.seed(f, changed, "misc.auto_update.repo", "YanIanZ/SourbyCraft",
            "SourbyCraft GitHub repository to check, as owner/name.");
        SourbyCraftConfig.seed(f, changed, "misc.auto_update.channel", "",
            "Release channel to track: REL, DEV or EXP. Leave blank to auto-detect from THIS build's version suffix.");
        SourbyCraftConfig.seed(f, changed, "misc.auto_update.apply_mode", "auto",
            "'auto' (download+verify+swap+self-restart), 'stage' (download+verify, swap on next restart), "
            + "'notify' (log+op banner only) or 'off' (disable).");
        SourbyCraftConfig.seed(f, changed, "misc.auto_update.check_times", new java.util.ArrayList<>(List.of("06:00")),
            "Daily check times in HH:mm, server-local time zone.");
        SourbyCraftConfig.seed(f, changed, "misc.auto_update.check_interval_minutes", 30,
            "Additionally poll every N minutes (0 = only the daily check_times).");
        SourbyCraftConfig.seed(f, changed, "misc.auto_update.restart_mode", "auto",
            "How 'auto' restarts after swapping the jar: 'auto' (detect Pterodactyl -> exit 70, else spigot), "
            + "'spigot', 'exit' or 'stop'.");
        SourbyCraftConfig.seed(f, changed, "misc.auto_update.restart_delay_seconds", 60,
            "Warning window broadcast to players before the auto-restart. Minimum 5.");
        SourbyCraftConfig.seed(f, changed, "misc.auto_update.allow_prerelease", false,
            "Whether GitHub prereleases on the tracked channel are eligible.");
        SourbyCraftConfig.seed(f, changed, "misc.auto_update.via_auto_update", true,
            "Also keep the auto-provisioned ViaVersion + ViaBackwards plugins current on the same cadence.");
    }

    /** Refresh every static field from the unified TOML (called at boot + on {@code /sourbycraft reload}). */
    public static void loadFromToml() {
        enabled = SourbyCraftConfig.cfgBool("misc.auto_update.enabled", enabled);
        repo = valueOr(SourbyCraftConfig.cfgGet("misc.auto_update.repo", repo), "YanIanZ/SourbyCraft");
        channel = SourbyCraftConfig.cfgGet("misc.auto_update.channel", channel);
        applyMode = valueOr(SourbyCraftConfig.cfgGet("misc.auto_update.apply_mode", applyMode), "notify");
        List<String> times = SourbyCraftConfig.cfgStringList("misc.auto_update.check_times");
        checkTimes = times.isEmpty() ? checkTimes : times;
        checkIntervalMinutes = Math.max(0, SourbyCraftConfig.cfgInt("misc.auto_update.check_interval_minutes", checkIntervalMinutes));
        restartMode = valueOr(SourbyCraftConfig.cfgGet("misc.auto_update.restart_mode", restartMode), "auto");
        restartDelaySeconds = Math.max(5, SourbyCraftConfig.cfgInt("misc.auto_update.restart_delay_seconds", restartDelaySeconds));
        allowPrerelease = SourbyCraftConfig.cfgBool("misc.auto_update.allow_prerelease", allowPrerelease);
        viaAutoUpdate = SourbyCraftConfig.cfgBool("misc.auto_update.via_auto_update", viaAutoUpdate);
    }

    private static String valueOr(String v, String def) {
        return v == null || v.isBlank() ? def : v.trim();
    }

    /** Start (or restart) the updater. No-op when disabled. Idempotent. */
    public static void startUpdater() {
        if (!enabled) return;
        if (instance == null) instance = new SourbyUpdater();
        instance.start();
    }

    /** Live updater instance for on-demand checks ({@code /update}). Creates one when needed. */
    public static @Nullable SourbyUpdater updater() {
        if (instance == null) instance = new SourbyUpdater();
        return instance;
    }
}
