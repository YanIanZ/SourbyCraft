package dev.iyanz.sourbycraft.api.world.event;

import java.time.Instant;
import java.util.Objects;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;

/**
 * Added in Build 55. A save of a loaded managed world through {@link dev.iyanz.sourbycraft.api.world.AuroraWorlds}
 * committed durably: {@code save}, or the save a saving {@code unload} makes first (this event then precedes the
 * world's {@link AwfWorldUnloadedEvent}). The engine's periodic autosave and the shutdown save do not fire it.
 * Asynchronous, on the AWF event thread; see the {@linkplain dev.iyanz.sourbycraft.api.world.event package
 * description}.
 */
@NullMarked
public class AwfWorldSavedEvent extends AwfWorldEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Instant committedAt;

    @ApiStatus.Internal
    public AwfWorldSavedEvent(final String worldName, final Instant committedAt) {
        super(worldName);
        this.committedAt = Objects.requireNonNull(committedAt, "committedAt");
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** When the commit was confirmed durable (wall clock of this server). */
    public Instant getCommittedAt() {
        return this.committedAt;
    }
}
