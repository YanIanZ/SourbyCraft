package dev.iyanz.sourbycraft.startup;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Holds a stop that the server thread asks for while the server is still starting, until startup has
 * ended on that thread (MC feature patch 0076).
 *
 * <p>A plugin that calls {@code Bukkit.shutdown()} from {@code onEnable} runs on the server thread in the
 * middle of {@code DedicatedServer#initServer}. The region-threading stop path
 * ({@code MinecraftServer#stopServer}) starts the "Region shutdown thread" at once, which disables
 * plugins, closes every world's chunk storage, saves and calls {@code System.exit}, while the server
 * thread keeps running the rest of startup: enabling the remaining plugins, its own
 * {@code saveEverything} (whose region-file flush then hits the closed storage:
 * {@code ClosedChannelException}), starting the region scheduler and logging {@code Done (}. Which
 * thread gets where first is up to timing; on 2026-10-22 both orders were seen.</p>
 *
 * <p>With this gate the server thread's own stop request during startup only marks the stop. Startup
 * then goes on as on Paper (where {@code halt} only clears {@code running}): the other plugins are
 * enabled and the worlds stay loaded. At the end of {@code initServer} the server skips what only an
 * open server needs (query/rcon listeners, the startup save, the "server started" notification), and
 * {@code runServer} neither starts the region scheduler nor logs {@code Done (}: it logs
 * {@link #ABANDONED_MESSAGE} and starts the normal stop, which saves every world once, with its
 * storage still open, after the server thread has stopped touching it.</p>
 *
 * <p>Only the server thread's own requests are held. A stop from any other thread (console,
 * signal/shutdown hook, an async plugin task) during startup keeps the old behaviour, since the
 * server thread may be blocked for a long time and that caller must not wait on it.</p>
 */
public final class StartupStopGate {

    /** Logged instead of {@code Done (} when a stop was asked for during startup. Machine-readable prefix. */
    public static final String ABANDONED_MESSAGE =
        "Startup stopped: the server was asked to stop while it was starting (Bukkit.shutdown() on the server thread); "
            + "not opening the server, saving the worlds once and stopping";

    private static final int STARTING = 0;
    private static final int STARTING_STOP_HELD = 1;
    private static final int STARTED = 2;

    private final AtomicInteger state = new AtomicInteger(STARTING);

    /**
     * Called first thing in {@code stopServer}.
     *
     * @param onServerThread whether the caller is the server thread
     * @return {@code true} when the stop is held until startup ends (the caller must return without
     *     doing anything else); {@code false} when the stop goes on now
     */
    public boolean holdIfStarting(final boolean onServerThread) {
        if (!onServerThread) {
            return false;
        }
        while (true) {
            final int current = this.state.get();
            if (current == STARTED) {
                return false;
            }
            if (current == STARTING_STOP_HELD || this.state.compareAndSet(STARTING, STARTING_STOP_HELD)) {
                return true;
            }
        }
    }

    /** Whether a stop is being held, so the rest of startup can skip what only an open server needs. */
    public boolean stopHeld() {
        return this.state.get() == STARTING_STOP_HELD;
    }

    /**
     * Ends startup (called once by the server thread, after {@code initServer} returned or threw). From
     * now on no stop is held.
     *
     * @return {@code true} when a stop was held: the caller must now run it ({@code stopServer})
     */
    public boolean finishStartup() {
        return this.state.getAndSet(STARTED) == STARTING_STOP_HELD;
    }

    /** Whether startup has ended. */
    public boolean started() {
        return this.state.get() == STARTED;
    }
}
