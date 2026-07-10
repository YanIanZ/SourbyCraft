package dev.iyanz.sourbycraft.perf;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.Plugin;

/**
 * Full-server join bypass (F1-6 feature 2).
 *
 * <p>A player holding {@code sourbycraft.maxplayers.bypass} — or a server op —
 * may connect even when the server is at/over its max-player cap. Implemented as
 * a {@link PlayerLoginEvent} listener: when the login result is
 * {@link PlayerLoginEvent.Result#KICK_FULL KICK_FULL} and the joining player has
 * the bypass permission (or is op), the result is flipped to
 * {@link PlayerLoginEvent.Result#ALLOWED ALLOWED}. Any other kick reason
 * (banned, whitelist, …) is left untouched.
 *
 * <p><b>Folia safety.</b> {@code PlayerLoginEvent} fires at login time, before the
 * player is assigned to a region, so this is a safe, region-free hook. The handler
 * only reads a permission flag and flips an enum field — it touches no entity, no
 * world, and schedules nothing. Registered against
 * {@link org.leavesmc.leaves.plugin.MinecraftInternalPlugin#INSTANCE} like the
 * other SourbyCraft actuators.
 *
 * <p>Runs at {@link EventPriority#HIGH} so it observes the fullness decision made
 * by the server / lower-priority plugins, but still leaves {@code MONITOR}
 * observers an accurate final result.
 */
public final class MaxPlayersBypass implements Listener {

    public static final String BYPASS_PERMISSION = "sourbycraft.maxplayers.bypass";

    private MaxPlayersBypass() {}

    public static void register(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new MaxPlayersBypass(), plugin);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(PlayerLoginEvent e) {
        if (e.getResult() != PlayerLoginEvent.Result.KICK_FULL) return;
        // Folia-safe: permission read + isOp() only; no entity/world access.
        if (e.getPlayer().hasPermission(BYPASS_PERMISSION) || e.getPlayer().isOp()) {
            e.setResult(PlayerLoginEvent.Result.ALLOWED);
        }
    }
}
