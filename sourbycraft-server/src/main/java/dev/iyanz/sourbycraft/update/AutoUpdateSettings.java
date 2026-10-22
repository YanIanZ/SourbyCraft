package dev.iyanz.sourbycraft.update;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin;
import dev.iyanz.sourbycraft.config.ConfigKey;
import dev.iyanz.sourbycraft.config.UtilityKeys;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Plain config surface for the SourbyCraft auto-updater (F1-8), replacing the archived Folia
 * build's {@code me.earthme.luminol.config.modules.misc.AutoUpdateConfig} — a Luminol
 * {@code @ConfigClassInfo}/{@code @ConfigInfo}-annotated module resolved by reflection. On this
 * current build there is no Luminol config manager, so these are plain
 * static fields, seeded into + read back from the same unified TOML
 * {@link SourbyCraftConfig} manages (the {@code [updater]} table since Build 49; the archived
 * {@code misc.auto_update.*} layout is migrated once and read until Build 50).
 *
 * <p>{@link #seedDefaults} is called once from {@link SourbyCraftConfig}'s boot-time seed pass;
 * {@link #loadFromToml()} is called from {@link SourbyCraftConfig#init()} / {@code #reload()} to
 * refresh the static fields {@link SourbyUpdater}'s inner {@code Config} bridge reads.
 *
 * <p><b>Reloadability</b> (verified against the consumers): {@code apply-mode}, {@code repository},
 * {@code channel}, {@code allow-prerelease}, {@code restart-mode} and {@code restart-delay-seconds}
 * are read by {@link SourbyUpdater#check} on every check, so after {@code /sourbycraft reload} the
 * next check uses the new value (LIVE). {@code enabled}, {@code check-times} and
 * {@code check-interval-minutes} shape the schedule {@link SourbyUpdater#start()} builds once at
 * boot, and {@code apply-mode=off} skips that schedule entirely, so turning the updater on or off,
 * or changing its cadence, is RESTART_REQUIRED. {@code /update status} therefore shows the interval the
 * running schedule uses and, when a reload changed it, the pending one ({@link #intervalSummary}).
 *
 * <p>{@link #startUpdater()} also runs the {@link UpdateRollbackGuard} boot step before anything
 * else, even when the updater is disabled: a rollback owed from an earlier apply must still happen.
 */
public final class AutoUpdateSettings {

    public static volatile boolean enabled = true;
    public static volatile String repo = "YanIanZ/SourbyCraft";
    public static volatile String channel = "";
    public static volatile String applyMode = "notify";
    public static volatile List<String> checkTimes = List.of("06:00");
    public static volatile int checkIntervalMinutes = 30;
    public static volatile String restartMode = "auto";
    public static volatile int restartDelaySeconds = 60;
    public static volatile boolean allowPrerelease = false;
    public static volatile boolean viaAutoUpdate = true;

    private static volatile SourbyUpdater instance;

    private AutoUpdateSettings() {}

    /**
     * Seed the {@code [updater]} keys ({@link UtilityKeys}) into the unified TOML when absent, with the
     * section comment. {@code SourbyCraftConfig.seedDefaults} already seeds every utility key; this
     * exists so the updater's own tests can seed and read back its table on its own.
     */
    public static void seedDefaults(CommentedFileConfig f, boolean[] changed) {
        for (final UtilityKeys.Section section : UtilityKeys.SECTIONS) {
            if (section.path().equals("updater") && f.getComment(section.path()) == null) {
                f.setComment(section.path(), section.comment());
            }
        }
        for (final ConfigKey key : UtilityKeys.KEYS) {
            if (!key.table().equals("updater")) continue;
            final Object value = key.defaultValue() instanceof List<?> list ? new java.util.ArrayList<>(list) : key.defaultValue();
            SourbyCraftConfig.seed(f, changed, key.path(), value, key.documentation());
        }
    }

    /**
     * Refresh every static field from the unified TOML (called at boot + on {@code /sourbycraft reload}).
     * A missing, blank or wrong-typed key falls back to the documented default, not to the previous
     * live value: deleting {@code apply-mode} from the TOML and reloading must not keep an earlier
     * {@code auto}. The pre-Build-49 names ({@code misc.auto_update.*}) are resolved by
     * {@link SourbyCraftConfig} before this reads.
     */
    public static void loadFromToml() {
        enabled = SourbyCraftConfig.cfgBool(UtilityKeys.UPDATER_ENABLED.path(), true);
        repo = valueOr(SourbyCraftConfig.cfgGet(UtilityKeys.UPDATER_REPOSITORY.path(), ""), "YanIanZ/SourbyCraft");
        channel = valueOr(SourbyCraftConfig.cfgGet(UtilityKeys.UPDATER_CHANNEL.path(), ""), "");
        applyMode = valueOr(SourbyCraftConfig.cfgGet(UtilityKeys.UPDATER_APPLY_MODE.path(), ""), "notify");
        List<String> times = SourbyCraftConfig.cfgStringList(UtilityKeys.UPDATER_CHECK_TIMES.path());
        checkTimes = times.isEmpty() ? List.of("06:00") : times;
        checkIntervalMinutes = Math.max(0, SourbyCraftConfig.cfgInt(UtilityKeys.UPDATER_CHECK_INTERVAL_MINUTES.path(), 30));
        restartMode = valueOr(SourbyCraftConfig.cfgGet(UtilityKeys.UPDATER_RESTART_MODE.path(), ""), "auto");
        restartDelaySeconds = Math.max(5, SourbyCraftConfig.cfgInt(UtilityKeys.UPDATER_RESTART_DELAY_SECONDS.path(), 60));
        allowPrerelease = SourbyCraftConfig.cfgBool(UtilityKeys.UPDATER_ALLOW_PRERELEASE.path(), false);
        viaAutoUpdate = SourbyCraftConfig.cfgBool(UtilityKeys.UPDATER_VIA_AUTO_UPDATE.path(), true);
    }

    /**
     * The interval for {@code /update status}: the one the running schedule uses, plus the configured
     * one when a reload changed it (it only applies after a restart). {@code running < 0} means this
     * process built no schedule, so only the configured value exists.
     */
    public static String intervalSummary(int running, int configured) {
        if (running < 0 || running == configured) return (running < 0 ? configured : running) + "m";
        return running + "m (pending restart: " + configured + "m)";
    }

    private static String valueOr(String v, String def) {
        return v == null || v.isBlank() ? def : v.trim();
    }

    /**
     * Run the rollback-guard boot step, then start (or restart) the updater. The guard runs even
     * when the updater is disabled; the scheduler part is a no-op when disabled. Idempotent.
     */
    public static void startUpdater() {
        runBootGuard();
        if (!enabled) return;
        if (instance == null) instance = new SourbyUpdater();
        instance.start();
    }

    /**
     * Advance {@link UpdateRollbackGuard} for this boot and, on the first boot after an apply, arm
     * the ready listener. Ready is {@link ServerLoadEvent} {@code STARTUP}: worlds loaded and plugins
     * enabled, fired immediately before the server accepts connections and shortly before the
     * {@code Done (} line. Everything here is logged loudly and never throws into the boot.
     */
    private static void runBootGuard() {
        final UpdateRollbackGuard guard = UpdateRollbackGuard.SERVER;
        try {
            final UpdateRollbackGuard.BootDecision decision = guard.onBoot();
            final String tag = guard.state().map(UpdateRollbackGuard.State::tag).orElse("?");
            switch (decision) {
                case NONE -> { }
                case AWAIT_READY -> {
                    final String prev = guard.state().map(s -> String.valueOf(s.prevJar())).orElse("<unknown>");
                    SourbyLogger.info("auto-updater: first boot after applying " + tag + "; the previous jar is kept at "
                        + prev + ". If this boot does not reach ready, the next boot restores it.");
                    Bukkit.getPluginManager().registerEvents(new Listener() {
                        @EventHandler(priority = EventPriority.MONITOR)
                        public void onLoad(final ServerLoadEvent event) {
                            if (event.getType() != ServerLoadEvent.LoadType.STARTUP) return;
                            guard.onReady();
                            SourbyLogger.info("auto-updater: " + tag + " reached ready; update confirmed ("
                                + prev + " stays on disk until the next apply).");
                        }
                    }, MinecraftInternalPlugin.INSTANCE);
                }
                case RESTORED -> loud(
                    "UPDATE ROLLED BACK: the boot after applying " + tag + " did not reach ready.",
                    guard.holdReason(),
                    "THIS process still runs the updated jar (a running JVM cannot replace its own file);",
                    "the restored previous build runs from the NEXT restart.",
                    "Automatic update checks and applies are HELD until an operator runs /update resume",
                    "(or deletes " + guard.stateFile() + "). applied.tag keeps " + tag + " from being re-offered.");
                case RESTORE_FAILED -> loud(
                    "UPDATE ROLLBACK FAILED: the boot after applying " + tag + " did not reach ready",
                    "and the previous jar could not be restored: " + guard.holdReason(),
                    "Restore it by hand (copy <jar>.prev over the launch jar), then /update resume.",
                    "Automatic update checks and applies are HELD until then.");
                case HELD -> loud(
                    "AUTO-UPDATER HELD: " + guard.holdReason(),
                    "No update is checked or applied until an operator runs /update resume",
                    "(or deletes " + guard.stateFile() + ").");
            }
        } catch (final Throwable failure) {
            SourbyLogger.error("auto-updater: rollback guard failed at boot", failure);
        }
    }

    private static void loud(final String... lines) {
        final String bar = "=".repeat(78);
        SourbyLogger.error(bar);
        for (final String line : lines) SourbyLogger.error("  " + line);
        SourbyLogger.error(bar);
    }

    /** Stop without creating an updater during shutdown. */
    public static void stopUpdater() {
        final SourbyUpdater current = instance;
        if (current != null) current.stop();
    }

    /** Live updater instance for on-demand checks ({@code /update}). Creates one when needed. */
    public static @Nullable SourbyUpdater updater() {
        if (instance == null) instance = new SourbyUpdater();
        return instance;
    }
}
