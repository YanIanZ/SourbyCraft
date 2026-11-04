package dev.iyanz.sourbycraft.api.scheduler;

import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;

/**
 * Added in Build 55. A task scheduled through {@link AuroraScheduler}.
 *
 * <p>Every method may be called from any thread. A repeating task receives its own handle on each run, so it
 * can stop itself with {@link #cancel()}.</p>
 */
@NullMarked
public interface TaskHandle {

    /**
     * Stops the task: it does not run again. A run that has already started is not interrupted. Cancelling a
     * task that has finished, was already cancelled, or was never scheduled (an entity task whose entity was
     * already removed) does nothing. Never throws.
     */
    void cancel();

    /**
     * Whether the task was cancelled: through {@link #cancel()}, by the server (its plugin was disabled, or the
     * entity of an entity task was removed), or because it could not be scheduled at all. A one-shot task that ran
     * to completion is not cancelled.
     */
    boolean isCancelled();

    /** The plugin that scheduled the task. */
    Plugin owner();
}
