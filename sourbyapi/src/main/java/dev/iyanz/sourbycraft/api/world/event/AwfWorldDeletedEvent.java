package dev.iyanz.sourbycraft.api.world.event;

import java.util.Optional;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Added in Build 55. A managed world was deleted: its folder, its own AWF stores and its entry are gone (never its
 * template's). Fired for {@link dev.iyanz.sourbycraft.api.world.AuroraWorlds#delete} and for the automatic deletion of
 * a disposable instance; for the latter an {@link AwfInstanceDisposedEvent} follows. Asynchronous, on the AWF event
 * thread; see the {@linkplain dev.iyanz.sourbycraft.api.world.event package description}.
 */
@NullMarked
public class AwfWorldDeletedEvent extends AwfWorldEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final @Nullable String template;
    private final boolean disposable;

    @ApiStatus.Internal
    public AwfWorldDeletedEvent(final String worldName, final @Nullable String template, final boolean disposable) {
        super(worldName);
        this.template = template;
        this.disposable = disposable;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** The template the world was an instance of, if it was one. The template itself is not deleted. */
    public Optional<String> getTemplate() {
        return Optional.ofNullable(this.template);
    }

    /** Whether the world was a disposable instance ({@code createInstance}). */
    public boolean isDisposable() {
        return this.disposable;
    }
}
