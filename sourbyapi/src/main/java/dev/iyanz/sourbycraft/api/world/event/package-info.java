/**
 * Added in Build 55. Events for the lifecycle of worlds managed through
 * {@link dev.iyanz.sourbycraft.api.world.AuroraWorlds}.
 *
 * <p><b>Timing.</b> Each event is fired after the outcome it reports is final: a creation, load, save, unload or
 * deletion that completed (for a save, durably committed), never one that failed or is still running. Only operations
 * made through {@code AuroraWorlds} (by a plugin, the {@code /awf} command, or the server's own autoload and
 * disposable-instance cleanup) fire them; a world unloaded with {@code Bukkit.unloadWorld} and the engine's periodic
 * autosave do not.</p>
 *
 * <p><b>Thread.</b> Every event here is asynchronous ({@code isAsynchronous()} is true) and is fired from one
 * dedicated SourbyCraft thread, never from a region, entity, global-tick or network thread, in the order the outcomes
 * were reached. A handler must not touch world, block, entity or player state directly (schedule that through
 * {@link dev.iyanz.sourbycraft.api.SourbyAPI#scheduler()}) and should return quickly: the events of every world wait
 * behind it.</p>
 *
 * <p><b>Delivery.</b> Not cancellable and not a transaction. The queue in front of the event thread is bounded; when
 * it is full an event is dropped, counted and logged rather than slowing the world operation. A crash after an outcome
 * and before its event loses the event. {@code AuroraWorlds} itself ({@code list}, {@code isLoaded},
 * {@code exists}) is the source of truth, not the event stream.</p>
 */
package dev.iyanz.sourbycraft.api.world.event;
