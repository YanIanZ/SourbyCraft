package dev.iyanz.sourbycraft.lang;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/**
 * Varied SourbyCraft join/leave broadcast messages (F1-7 optional touchpoint).
 *
 * <p>Overrides the vanilla "&lt;player&gt; joined the game" / "left the game" broadcast with a
 * random SourbyCraft-flavored, hex-colored variant from {@link SourbyMessages} (keys
 * {@link SourbyMessages#JOIN} / {@link SourbyMessages#LEAVE}). The joining/leaving player's name
 * is supplied via the MiniMessage {@code <player>} placeholder, so operator variants opt in simply
 * by writing {@code <player>} in their string.
 *
 * <p><b>Folia safety.</b> {@code PlayerJoinEvent}/{@code PlayerQuitEvent} fire on the owning
 * region thread. The handler only reads the player's name (a String) and replaces the event's
 * broadcast {@link net.kyori.adventure.text.Component} via {@code joinMessage(...)} /
 * {@code quitMessage(...)} — it schedules nothing, touches no other entity and no world state, so
 * it is region-safe. {@link SourbyMessages#get} uses {@link java.util.concurrent.ThreadLocalRandom}
 * for variant selection, so concurrent joins on different regions are safe.
 *
 * <p>Registered against {@link org.leavesmc.leaves.plugin.MinecraftInternalPlugin#INSTANCE} from
 * {@link dev.iyanz.sourbycraft.perf.PerfEngineBootstrap}, like the other SourbyCraft actuators.
 * {@link EventPriority#NORMAL} — after most plugins have set their own message but before MONITOR
 * observers read the final one.
 */
public final class SourbyJoinLeaveListener implements Listener {

    private SourbyJoinLeaveListener() {}

    public static void register(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new SourbyJoinLeaveListener(), plugin);
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onJoin(PlayerJoinEvent e) {
        // Only override when a broadcast would have been sent (null = another plugin suppressed it).
        if (e.joinMessage() == null) return;
        e.joinMessage(SourbyMessages.get(
            SourbyMessages.JOIN,
            Placeholder.unparsed("player", e.getPlayer().getName())));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent e) {
        if (e.quitMessage() == null) return;
        e.quitMessage(SourbyMessages.get(
            SourbyMessages.LEAVE,
            Placeholder.unparsed("player", e.getPlayer().getName())));
    }
}
