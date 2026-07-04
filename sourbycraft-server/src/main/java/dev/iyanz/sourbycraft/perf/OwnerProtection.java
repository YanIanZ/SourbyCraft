package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.plugin.Plugin;

/**
 * item.owner-protection: dropped items are pickup-locked to the dropper for
 * owner-protection-time seconds via the vanilla pickup-target (Item#setOwner),
 * then unlocked. Death drops excluded deliberately (killers loot corpses).
 */
public final class OwnerProtection implements Listener {

    private static Plugin OWNER;

    private OwnerProtection() {}

    public static void register(Plugin plugin) {
        OWNER = plugin;
        Bukkit.getPluginManager().registerEvents(new OwnerProtection(), plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (!SourbyCraftConfig.ownerProtectionEnabled) return;
        int seconds = SourbyCraftConfig.ownerProtectionTime;
        if (seconds <= 0) return;
        Item item = e.getItemDrop();
        java.util.UUID owner = e.getPlayer().getUniqueId();
        item.setOwner(owner);
        Bukkit.getScheduler().runTaskLater(OWNER, () -> {
            if (item.isValid() && owner.equals(item.getOwner())) item.setOwner(null);
        }, seconds * 20L);
    }
}
