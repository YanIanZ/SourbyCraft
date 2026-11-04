package dev.iyanz.sourbycraft.api.world.event;

import java.util.Objects;
import org.bukkit.event.Event;
import org.jspecify.annotations.NullMarked;

/**
 * Added in Build 55. Base of the AWF world lifecycle events; see the {@linkplain dev.iyanz.sourbycraft.api.world.event
 * package description} for timing, thread and delivery. Listen to a concrete subclass: this class has no handler list.
 */
@NullMarked
public abstract class AwfWorldEvent extends Event {

    private final String worldName;

    protected AwfWorldEvent(final String worldName) {
        super(true);
        this.worldName = Objects.requireNonNull(worldName, "worldName");
    }

    /**
     * The world's name, as {@link dev.iyanz.sourbycraft.api.world.AuroraWorlds} knows it. Look the world up with
     * {@code Bukkit.getWorld(name)} where needed; this event carries no {@code World}, which may already be gone.
     */
    public String getWorldName() {
        return this.worldName;
    }
}
