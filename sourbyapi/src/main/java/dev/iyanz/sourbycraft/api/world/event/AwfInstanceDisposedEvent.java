package dev.iyanz.sourbycraft.api.world.event;

import java.util.Objects;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;

/**
 * Added in Build 55. A disposable instance ({@link dev.iyanz.sourbycraft.api.world.AuroraWorlds#createInstance}) is
 * gone for good: it was unloaded and then deleted (right after an {@code unload} through the API, or by the cleanup
 * that removes disposable instances unloaded some other way or left over from before a restart). Fired after the
 * world's {@link AwfWorldDeletedEvent}. The name may be used again from now on. Asynchronous, on the AWF event
 * thread; see the {@linkplain dev.iyanz.sourbycraft.api.world.event package description}.
 */
@NullMarked
public class AwfInstanceDisposedEvent extends AwfWorldEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String template;

    @ApiStatus.Internal
    public AwfInstanceDisposedEvent(final String worldName, final String template) {
        super(worldName);
        this.template = Objects.requireNonNull(template, "template");
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** The template the instance was made from; it is not deleted. */
    public String getTemplate() {
        return this.template;
    }
}
