package me.earthme.luminol.config.modules.misc;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import dev.iyanz.sourbycraft.update.SourbyUpdater;
import me.earthme.luminol.config.IConfigModule;
import me.earthme.luminol.config.flags.ConfigClassInfo;
import me.earthme.luminol.config.flags.ConfigInfo;
import me.earthme.luminol.config.flags.DoNotLoad;
import me.earthme.luminol.enums.EnumConfigCategory;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * SourbyCraft auto-update config surface (F1-8).
 *
 * <p>This is the operator-facing config module for the advanced SourbyCraft updater
 * ({@link dev.iyanz.sourbycraft.update.SourbyUpdater}). It replaces the Folia base's
 * archived-Luminol updater: keys/namespace, comments and the target repo are all SourbyCraft now.
 * The heavy lifting (channel gating, download verification, safe apply modes, hex op-notification,
 * Folia async scheduling) lives in {@code SourbyUpdater}; this class only declares the config
 * fields (materialised into {@code misc.auto_update.*} in the unified config by the Luminol
 * {@code @ConfigClassInfo} reflection) and holds the updater instance.
 *
 * <p><b>Boot ordering.</b> {@link #onLoaded} runs during config finalise, before the internal
 * plugin handle / Folia schedulers are ready, so it does NOT start the scheduler here. The
 * scheduler is started from the authored post-config boot hook
 * ({@code PerfEngineBootstrap.start()}), which calls {@link #startUpdater()} once the plugin and
 * async scheduler are available. {@link #onUnloaded} stops it on reload/shutdown.
 */
@ConfigClassInfo(
        category = EnumConfigCategory.MISC,
        name = "auto_update",
        comments = """
                SourbyCraft auto-updater. Checks the SourbyCraft GitHub repo's Releases for a newer
                jar on the SAME release channel as this build (REL/DEV/EXP) on a daily schedule.
                Downloads are verified (size + SHA-256 when published + valid-jar check) before use.
                apply_mode controls what happens: 'notify' (default) only logs + notifies operators;
                'stage' also places the verified jar under auto_update/sourbycraft and writes
                auto_update/core.path for the launcher to swap on the NEXT restart (never mid-run);
                'off' disables checks entirely."""
)
public class AutoUpdateConfig implements IConfigModule {
    @ConfigInfo(name = "enabled", comments = "Whether SourbyCraft should check for updates automatically.")
    public static boolean enabled = false;

    @ConfigInfo(name = "repo", comments = "SourbyCraft GitHub repository to check, as owner/name. Default: YanIanZ/SourbyCraft.")
    public static String repo = "YanIanZ/SourbyCraft";

    @ConfigInfo(name = "channel", comments = """
            Release channel to track: REL, DEV or EXP. Leave blank to auto-detect from THIS build's
            version suffix (e.g. a 26.2-REL build tracks REL). A REL server never auto-updates to a
            DEV/EXP build and vice-versa.""")
    public static String channel = "";

    @ConfigInfo(name = "apply_mode", comments = """
            What to do when a newer release is found: 'notify' (default, safest — log + op banner
            only), 'stage' (download + verify + stage for next-restart swap) or 'off' (disable).""")
    public static String applyMode = "notify";

    @ConfigInfo(name = "check_times", comments = "List of daily check times in HH:mm, based on the server's local time zone.")
    public static List<String> checkTimes = List.of("06:00");

    @ConfigInfo(name = "allow_prerelease", comments = "Whether GitHub prereleases on the tracked channel are eligible.")
    public static boolean allowPrerelease = false;

    @DoNotLoad
    public SourbyUpdater instance = null;

    @Override
    public void onLoaded(CommentedFileConfig configInstance, @Nullable Set<Exception> exs) {
        // Do not start here: config finalise runs before the plugin handle / Folia async scheduler
        // are ready. The boot hook (PerfEngineBootstrap.start) calls startUpdater() when they are.
    }

    @Override
    public void onUnloaded(CommentedFileConfig configInstance) {
        if (instance != null) {
            instance.stop();
            instance = null;
        }
    }

    /**
     * Start the updater from the authored post-config boot hook (plugin + Folia async scheduler
     * are ready by then). No-op when disabled. Idempotent — re-invocation restarts cleanly.
     */
    public static void startUpdater() {
        if (!enabled) return;
        AutoUpdateConfig module = holder();
        if (module == null) return;
        if (module.instance == null) {
            module.instance = new SourbyUpdater();
        }
        module.instance.start();
    }

    /**
     * The live config-module instance the Luminol config manager created for this class (fields are
     * static, but the manager also holds one object per module and invokes lifecycle on it). We
     * keep a self-reference so the static boot hook can reach the per-instance {@link #instance}.
     */
    private static volatile AutoUpdateConfig SELF;

    public AutoUpdateConfig() {
        SELF = this;
    }

    private static @Nullable AutoUpdateConfig holder() {
        return SELF;
    }
}
