package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.plugin.Plugin;

/**
 * Pushes SourbyCraft entity.* master values into the per-world Spigot/Paper
 * config engines. A value is applied ONLY when the operator changed it from
 * the SourbyCraft compiled default — otherwise Spigot/Paper settings win.
 * Zero hot-path cost: runs once per world load.
 */
public final class ConfigBridge implements Listener {

    // Compiled defaults (mirror SourbyCraftConfig initializers).
    private static final int DEF_ITEM_DESPAWN = 6000;
    private static final int DEF_MERGE_RADIUS = 3;
    private static final int DEF_MOB_TICK_DISTANCE = 32;

    private ConfigBridge() {}

    public static void register(Plugin plugin) {
        ConfigBridge bridge = new ConfigBridge();
        Bukkit.getPluginManager().registerEvents(bridge, plugin);
        for (org.bukkit.World w : Bukkit.getWorlds()) bridge.apply(w, plugin);
        if (SourbyCraftConfig.idleTimeout > 0) {
            net.minecraft.server.MinecraftServer.getServer().setPlayerIdleTimeout(SourbyCraftConfig.idleTimeout);
            plugin.getLogger().info("[bridge] server.idle-timeout -> " + SourbyCraftConfig.idleTimeout + " min");
        }
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent e) {
        apply(e.getWorld(), null);
    }

    private void apply(org.bukkit.World world, Plugin plugin) {
        net.minecraft.server.level.ServerLevel level = ((org.bukkit.craftbukkit.CraftWorld) world).getHandle();
        org.spigotmc.SpigotWorldConfig spigot = level.spigotConfig;
        StringBuilder applied = new StringBuilder();

        if (SourbyCraftConfig.itemDespawnRate != DEF_ITEM_DESPAWN) {
            spigot.itemDespawnRate = Math.max(20, SourbyCraftConfig.itemDespawnRate);
            applied.append(" itemDespawnRate=").append(spigot.itemDespawnRate);
        }
        if (!SourbyCraftConfig.itemMergeOptimize) {
            spigot.itemMerge = 0.0;
            applied.append(" itemMerge=off");
        } else if (SourbyCraftConfig.itemMergeRadius != DEF_MERGE_RADIUS) {
            spigot.itemMerge = Math.max(0, SourbyCraftConfig.itemMergeRadius);
            applied.append(" itemMerge=").append(spigot.itemMerge);
        }
        if (SourbyCraftConfig.hopperBatch && spigot.hopperCheck < 4) {
            spigot.hopperCheck = 4;
            applied.append(" hopperCheck=4");
        }
        if (SourbyCraftConfig.mobTickDistance != DEF_MOB_TICK_DISTANCE && SourbyCraftConfig.mobTickDistance > 0) {
            int cap = SourbyCraftConfig.mobTickDistance;
            spigot.animalActivationRange = Math.min(spigot.animalActivationRange, cap);
            spigot.monsterActivationRange = Math.min(spigot.monsterActivationRange, cap);
            spigot.raiderActivationRange = Math.min(spigot.raiderActivationRange, cap);
            spigot.miscActivationRange = Math.min(spigot.miscActivationRange, cap);
            spigot.flyingMonsterActivationRange = Math.min(spigot.flyingMonsterActivationRange, cap);
            spigot.waterActivationRange = Math.min(spigot.waterActivationRange, cap);
            spigot.villagerActivationRange = Math.min(spigot.villagerActivationRange, cap);
            applied.append(" activationRanges<=").append(cap);
        }
        io.papermc.paper.configuration.WorldConfiguration paper = level.paperConfig();
        if (SourbyCraftConfig.redstoneOptimize) {
            if (paper.misc.redstoneImplementation == io.papermc.paper.configuration.WorldConfiguration.Misc.RedstoneImplementation.VANILLA) {
                paper.misc.redstoneImplementation = io.papermc.paper.configuration.WorldConfiguration.Misc.RedstoneImplementation.ALTERNATE_CURRENT;
                applied.append(" redstone=alternate-current");
            }
        } else {
            paper.misc.redstoneImplementation = io.papermc.paper.configuration.WorldConfiguration.Misc.RedstoneImplementation.VANILLA;
            applied.append(" redstone=vanilla");
        }
        if (applied.length() > 0) {
            dev.iyanz.sourbycraft.util.SourbyLogger.info("[bridge] " + world.getName() + ":" + applied);
        }
    }
}
