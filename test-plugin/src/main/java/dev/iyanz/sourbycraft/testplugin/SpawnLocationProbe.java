package dev.iyanz.sourbycraft.testplugin;

import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

/**
 * Logs {@code SPAWNPROBE} lines for the legacy {@code PlayerSpawnLocationEvent} and moves the join
 * 64 blocks east at y=150, then logs where {@code PlayerJoinEvent} sees the player, so a
 * protocol-probe join on the test server shows that the event fires, on which thread, how often,
 * and that the changed location is honoured. Registered only with
 * {@code -Dsourby.testplugin.probes=true}.
 */
@SuppressWarnings("deprecation")
final class SpawnLocationProbe implements Listener {

    @EventHandler
    public void onSpawnLocation(final PlayerSpawnLocationEvent event) {
        final Location proposed = event.getSpawnLocation();
        final Location moved = new Location(proposed.getWorld(), proposed.getBlockX() + 64.5, 150.0, proposed.getBlockZ() + 0.5);
        event.setSpawnLocation(moved);
        Bukkit.getLogger().info("SPAWNPROBE event player=" + event.getPlayer().getName()
            + " thread=" + Thread.currentThread().getName() + " globalTick=" + Bukkit.isGlobalTickThread() + " proposed=" + fmt(proposed) + " set=" + fmt(moved));
    }

    /** Paper's async event runs after the legacy one and must see the legacy event's location. */
    @EventHandler
    public void onAsyncSpawnLocation(final io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent event) {
        Bukkit.getLogger().info("SPAWNPROBE async-event sees=" + fmt(event.getSpawnLocation())
            + " thread=" + Thread.currentThread().getName());
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        Bukkit.getLogger().info("SPAWNPROBE join player=" + event.getPlayer().getName()
            + " at=" + fmt(event.getPlayer().getLocation()));
    }

    private static String fmt(final Location l) {
        return String.format(Locale.ROOT, "%s %.1f %.1f %.1f", l.getWorld() == null ? "null" : l.getWorld().getName(),
            l.getX(), l.getY(), l.getZ());
    }
}
