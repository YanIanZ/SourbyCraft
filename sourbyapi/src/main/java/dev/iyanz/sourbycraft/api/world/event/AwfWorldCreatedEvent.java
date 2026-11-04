package dev.iyanz.sourbycraft.api.world.event;

import java.util.Objects;
import java.util.Optional;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.ApiStatus;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Added in Build 55. A new world was created through {@link dev.iyanz.sourbycraft.api.world.AuroraWorlds} and is
 * loaded: its entry is registered, its stores exist and the creating future completed successfully. An
 * {@link AwfWorldLoadedEvent} with {@link AwfWorldLoadedEvent#isCreated()} true follows it. Asynchronous, on the AWF
 * event thread; see the {@linkplain dev.iyanz.sourbycraft.api.world.event package description}.
 */
@NullMarked
public class AwfWorldCreatedEvent extends AwfWorldEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /** How the world came to be. */
    public enum Origin {
        /** {@code create}: a new world with the environment's terrain or a named generator. */
        CREATE,
        /** {@code createFromTemplate}: a persistent copy-on-write instance of a template. */
        TEMPLATE_INSTANCE,
        /** {@code createInstance}: a disposable instance of a template, deleted once unloaded. */
        DISPOSABLE_INSTANCE,
        /** {@code cloneWorld}: a copy of another managed world. */
        CLONE,
        /** {@code importWorld}: an {@code .awf} file. */
        IMPORT_AWF,
        /** {@code importSlime}: a Slime world file. */
        IMPORT_SLIME
    }

    private final Origin origin;
    private final @Nullable String template;

    @ApiStatus.Internal
    public AwfWorldCreatedEvent(final String worldName, final Origin origin, final @Nullable String template) {
        super(worldName);
        this.origin = Objects.requireNonNull(origin, "origin");
        this.template = template;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    /** Which operation created the world. */
    public Origin getOrigin() {
        return this.origin;
    }

    /** The template the world is an instance of, if it is one (also for a clone of an instance). */
    public Optional<String> getTemplate() {
        return Optional.ofNullable(this.template);
    }

    /** Whether the world is a disposable instance, deleted once it is unloaded. */
    public boolean isDisposable() {
        return this.origin == Origin.DISPOSABLE_INSTANCE;
    }
}
