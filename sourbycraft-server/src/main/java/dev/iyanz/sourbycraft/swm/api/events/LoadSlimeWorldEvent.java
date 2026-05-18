package dev.iyanz.sourbycraft.swm.api.events;

import dev.iyanz.sourbycraft.swm.api.SlimeWorldInstance;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

public class LoadSlimeWorldEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final SlimeWorldInstance world;

    public LoadSlimeWorldEvent(SlimeWorldInstance world) { this.world = world; }
    public SlimeWorldInstance getSlimeWorld() { return world; }
    public @NotNull HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
