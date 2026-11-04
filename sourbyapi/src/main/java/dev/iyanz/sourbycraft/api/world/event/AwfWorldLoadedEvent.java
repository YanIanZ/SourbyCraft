package dev.iyanz.sourbycraft.api.world.event;

import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;

/**
 * Added in Build 55. A managed world finished loading through {@link dev.iyanz.sourbycraft.api.world.AuroraWorlds}:
 * a {@code load} (including the server's autoload at startup) that found the world unloaded and completed, or a
 * creation ({@link #isCreated()}, after its {@link AwfWorldCreatedEvent}). A {@code load} of a world that was already
 * loaded fires nothing. Asynchronous, on the AWF event thread; the world may have been unloaded again by the time a
 * handler runs. See the {@linkplain dev.iyanz.sourbycraft.api.world.event package description}.
 */
@NullMarked
public class AwfWorldLoadedEvent extends AwfWorldEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final boolean created;

    @ApiStatus.Internal
    public AwfWorldLoadedEvent(final String worldName, final boolean created) {
        super(worldName);
        this.created = created;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** Whether the world was created by the operation that loaded it, rather than loaded from its stores. */
    public boolean isCreated() {
        return this.created;
    }
}
