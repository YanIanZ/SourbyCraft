package dev.iyanz.sourbycraft.example;

import dev.iyanz.sourbycraft.api.profile.event.ProfileSwitchedEvent;
import dev.iyanz.sourbycraft.api.scheduler.AuroraScheduler;
import dev.iyanz.sourbycraft.api.world.event.AwfInstanceDisposedEvent;
import dev.iyanz.sourbycraft.api.world.event.AwfWorldCreatedEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * One AWF event and one profile event (there are six AWF world events and four profile events; see the guide).
 *
 * <p>Both families are asynchronous Bukkit events. They are fired after the outcome is durable, from a thread that is
 * neither a region, an entity, the global tick nor a network thread, and they are not cancellable. A handler therefore
 * reads the event, may log, and hands any world, entity or player access to {@link AuroraScheduler}; it returns quickly
 * because the events of every world (or every profile) wait behind it.</p>
 */
final class EventsDemo implements Listener {

    private final Plugin plugin;
    private final AuroraScheduler scheduler;

    EventsDemo(final Plugin plugin, final AuroraScheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
    }

    /** An AWF world was created through AuroraWorlds. Logging is safe on the event thread; touching the world is not. */
    @EventHandler
    public void onWorldCreated(final AwfWorldCreatedEvent event) {
        this.plugin.getLogger().info("AWF world " + event.getWorldName() + " created (" + event.getOrigin()
            + event.getTemplate().map(template -> ", template " + template).orElse("") + ")"
            + (event.isDisposable() ? "; it is deleted once unloaded" : ""));
    }

    /** A disposable instance is gone for good; its name may be reused. */
    @EventHandler
    public void onInstanceDisposed(final AwfInstanceDisposedEvent event) {
        this.plugin.getLogger().info("instance " + event.getWorldName() + " of template " + event.getTemplate() + " disposed");
    }

    /**
     * A profile switch committed. The player may already have left or switched again, and this thread owns no player, so
     * the message is scheduled on the player's own region; {@code retired} runs instead if the player is removed first.
     */
    @EventHandler
    public void onProfileSwitched(final ProfileSwitchedEvent event) {
        final Player player = Bukkit.getPlayer(event.getPlayerId()); // a lookup by id is safe from any thread
        if (player == null) return;
        final String text = "Now playing profile " + event.getProfile().id();
        this.scheduler.runFor(this.plugin, player, () -> player.sendMessage(text), null);
    }
}
