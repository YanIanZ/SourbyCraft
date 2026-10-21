package dev.iyanz.sourbycraft.testplugin;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Manual pearl probe, registered only with -Dsourby.testplugin.probes=true.
 * Player tags: pearl-cancel, pearl-redirect (near), pearl-world (another loaded world).
 */
final class PearlTeleportProbe implements Listener {
    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.ENDER_PEARL) return;
        final var player = event.getPlayer();
        final var tags = player.getScoreboardTags();
        String mode = "observe";
        if (tags.contains("pearl-cancel")) {
            event.setCancelled(true);
            mode = "cancel";
        } else if (tags.contains("pearl-world") && Bukkit.getWorlds().size() > 1) {
            final var world = Bukkit.getWorlds().stream().filter(w -> w != player.getWorld()).findFirst().orElseThrow();
            event.setTo(new Location(world, 0.5, 100.0, 0.5, 90, 20));
            mode = "world";
        } else if (tags.contains("pearl-redirect")) {
            event.setTo(event.getFrom().clone().add(3, 0, 0));
            mode = "redirect";
        }
        Bukkit.getLogger().info("PEARLPROBE cause=" + event.getCause() + " mode=" + mode
            + " owned=" + Bukkit.isOwnedByCurrentRegion(player) + " cancelled=" + event.isCancelled()
            + " to=" + event.getTo() + " thread=" + Thread.currentThread().getName());
    }
}
