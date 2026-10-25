package dev.iyanz.sourbycraft.guards;

import java.util.Objects;

/**
 * Every call that region threading refuses with an {@link UnsupportedOperationException}, with the
 * supported alternative in its message. The base threw the same bare "Unsupported in region
 * threading" for all of them, which tells a plugin author nothing. The text starts with that
 * legacy phrase so existing log searches keep matching; what follows names the call and what to use
 * instead. Only alternatives that exist in this build are named; a test pins each name that must
 * stay in the text.
 *
 * <p>Nothing here changes which calls are refused. Refusals that are a correctness invariant (no
 * main thread, no single server tick) stay refusals; they are only explained.</p>
 */
public enum RegionRefusal {
    /** {@code Server#reload()}. */
    PLUGIN_RELOAD("Server#reload()",
        "Plugins cannot be reloaded while regions tick. Restart the server to reload plugins; "
            + "/sourbycraft reload re-reads SourbyCraft's own configuration"),
    /** {@code Server#reloadData()}. */
    DATA_RELOAD("Server#reloadData()",
        "Registries and datapacks cannot be reloaded while regions tick. Restart the server to apply datapack changes"),
    /** {@code Datapack#setEnabled(boolean)}. */
    DATAPACK_TOGGLE("Datapack#setEnabled(boolean)",
        "Datapacks cannot be enabled or disabled while regions tick. Change the selected packs and restart the server"),
    /** {@code Server#unloadWorld(...)}. */
    UNLOAD_WORLD("Server#unloadWorld(...)",
        "Use Server#unloadWorldAsync(world, save, callback) from the global region, "
            + "or AuroraWorlds#unload(name, save), which returns a future"),
    /** {@code Server#restart()}, {@code /restart}. */
    RESTART("Server#restart() and /restart",
        "A restart script cannot run under region threading. Stop the server with /stop and let your "
            + "process supervisor (systemd, Docker, a panel) start it again"),
    /** {@code MinecraftServer#submit/execute/schedule/executeBlocking} and the task-loop internals. */
    SERVER_QUEUE("Queueing work on the server main thread (MinecraftServer#submit, #execute, #schedule, #executeBlocking)",
        "There is no server main thread to queue to. Run the work on the region that owns it "
            + "(Server#getRegionScheduler or Entity#getScheduler), on the global region "
            + "(Server#getGlobalRegionScheduler) or asynchronously (Server#getAsyncScheduler)"),
    /** {@code MinecraftServer#processQueue.add}. */
    UNDRAINED_QUEUE("MinecraftServer#processQueue.add",
        "Nothing drains this queue under region threading, so the task would never run. Run the work on the "
            + "region that owns it (Server#getRegionScheduler or Entity#getScheduler), on the global region "
            + "(Server#getGlobalRegionScheduler) or asynchronously (Server#getAsyncScheduler)"),
    /** {@code MinecraftServer#getTickCount()}. */
    TICK_COUNT("MinecraftServer#getTickCount()",
        "There is no single server tick. Use Server#getCurrentTick() for the current region's tick, "
            + "or run on the global region for server-wide state"),
    /** The vanilla single-threaded tick loop. */
    TICK_LOOP("The vanilla server tick loop",
        "The region scheduler and the global tick replace it. Schedule work with Server#getRegionScheduler "
            + "or Server#getGlobalRegionScheduler"),
    /** {@code MinecraftServer#addTickable(Runnable)}. */
    ADD_TICKABLE("MinecraftServer#addTickable(Runnable)",
        "There is no single server tick to hook. Use Server#getGlobalRegionScheduler() for server-wide periodic work"),
    /** {@code MCUtil#ensureMain}. */
    ENSURE_MAIN("MCUtil#ensureMain",
        "There is no main thread to hop to. Schedule on the owning region "
            + "(Server#getRegionScheduler or Entity#getScheduler) or on the global region (Server#getGlobalRegionScheduler)"),
    /**
     * {@code org.bukkit.ServerTickManager}. No longer thrown: since Paper feature 0023 the tick manager is
     * implemented by {@link ServerTickControl}. Kept because Paper feature 0019,
     * which the 0023 lines replace, still names it.
     */
    TICK_MANAGER("ServerTickManager (/tick freeze, step, sprint, rate)",
        "Use Server#getRegionThreadingTickRateManager() for the region-aware tick manager; "
            + "Server#getTPS() and Server#getAverageTickTime() report the rates"),
    /** {@code BukkitScheduler} for a plugin the Aurora Bridge has not admitted. */
    BUKKIT_SCHEDULER("The Bukkit scheduler (BukkitScheduler)",
        "Use Server#getGlobalRegionScheduler(), Server#getRegionScheduler(), Entity#getScheduler() or "
            + "Server#getAsyncScheduler(); legacy plugins the Aurora Bridge admits keep the Bukkit scheduler");

    /** The phrase the base used for every refusal; kept as the message prefix. */
    public static final String PREFIX = "Unsupported in region threading: ";

    private final String what;
    private final String advice;

    RegionRefusal(final String what, final String advice) {
        this.what = what;
        this.advice = advice;
    }

    /** The call or feature that is refused. */
    public String what() {
        return this.what;
    }

    /** What to do instead. */
    public String advice() {
        return this.advice;
    }

    /** The full message: legacy prefix, the refused call, then the alternative. */
    public String message() {
        return PREFIX + this.what + ". " + this.advice + ".";
    }

    /** The message with a detail appended (for example the caller of a queue). */
    public String message(final String detail) {
        Objects.requireNonNull(detail, "detail");
        return this.message() + " (" + detail + ")";
    }

    /** The exception the guarded method throws. */
    public Refused error() {
        return new Refused(this, this.message());
    }

    /** As {@link #error()}, with a detail. */
    public Refused error(final String detail) {
        return new Refused(this, this.message(detail));
    }

    /**
     * What {@link #error()} throws: still an {@link UnsupportedOperationException} for every caller, typed so the
     * Aurora Bridge recognises an owned refusal by its class rather than by its message text
     * ({@code bridge/ViolationClassifier}).
     */
    public static final class Refused extends UnsupportedOperationException {
        private static final long serialVersionUID = 1L;

        private final RegionRefusal refusal;

        private Refused(final RegionRefusal refusal, final String message) {
            super(message);
            this.refusal = refusal;
        }

        /** The refused call. */
        public RegionRefusal refusal() {
            return this.refusal;
        }
    }
}
