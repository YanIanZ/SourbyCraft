package dev.iyanz.sourbycraft.api.world.event;

import dev.iyanz.sourbycraft.api.world.UnloadResult;
import java.util.Objects;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;

/**
 * Added in Build 55. A managed world was unloaded through {@link dev.iyanz.sourbycraft.api.world.AuroraWorlds#unload}:
 * the engine reported the unload done and its storages closed. {@link #getResult()} is
 * {@link UnloadResult#SUCCESS}, or {@link UnloadResult#FAILED} when the world is gone but its final AWF commit, made as
 * its storages closed, was not confirmed (the server log names the storage). An unload the engine refused (players
 * present, cancelled by a plugin, server stopping, ...) leaves the world loaded and fires nothing.
 *
 * <p>For a disposable instance this event comes before its {@link AwfWorldDeletedEvent} and
 * {@link AwfInstanceDisposedEvent}. Asynchronous, on the AWF event thread; see the
 * {@linkplain dev.iyanz.sourbycraft.api.world.event package description}.</p>
 */
@NullMarked
public class AwfWorldUnloadedEvent extends AwfWorldEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UnloadResult result;

    @ApiStatus.Internal
    public AwfWorldUnloadedEvent(final String worldName, final UnloadResult result) {
        super(worldName);
        this.result = Objects.requireNonNull(result, "result");
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** {@link UnloadResult#SUCCESS}, or {@link UnloadResult#FAILED} when the final commit was not confirmed. */
    public UnloadResult getResult() {
        return this.result;
    }
}
