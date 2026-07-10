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
 *
 * <p><b>Folia adaptation (F2c).</b> The Paper tag scheduled the delayed unlock via
 * {@code Bukkit.getScheduler().runTaskLater(...)} — the global main-thread scheduler,
 * which does not exist on Folia. The unlock touches a single {@link Item} entity, so
 * on Folia it is scheduled on that entity's own region scheduler via
 * {@code item.getScheduler().runDelayed(...)}: the callback runs on the region owning
 * the item, which is the only thread allowed to mutate it. If the item has already been
 * retired (picked up / despawned) the entity scheduler's retired-callback fires and we
 * simply do nothing.
 *
 * <p>{@link #onDrop} runs on the region thread owning the dropping player (Bukkit events
 * fire on the owning region on Folia) and only reads config + calls entity-local API, so
 * it makes no main-thread assumption.
 */
public final class OwnerProtection implements Listener {

    private OwnerProtection() {}

    public static void register(Plugin plugin) {
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
        // Folia: schedule the unlock on the item's own region scheduler. The retired
        // callback (item already gone) is a no-op.
        item.getScheduler().runDelayed(
            org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE,
            task -> {
                if (item.isValid() && owner.equals(item.getOwner())) item.setOwner(null);
            },
            null,
            seconds * 20L
        );
    }
}
