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
 *
 * <p><b>Folia adaptation.</b> On the Paper line this was wired via the deleted
 * {@code SourbyCorePlugin}; on the Folia base it is registered from
 * {@link PerfEngineBootstrap#startActuators()} (which runs from the post-config
 * {@code DedicatedServer#initServer} hook, <em>before</em> any world is loaded, so the
 * boot loop over {@link Bukkit#getWorlds()} is normally empty — every world is caught
 * by the {@link #onWorldLoad} listener as it loads). Two threading concerns:
 * <ul>
 *   <li><b>Per-world config mutation</b> ({@code spigotConfig}/{@code paperConfig()} fields)
 *       runs from {@link #onWorldLoad}, which fires on the loading world's own region thread
 *       on Folia — the correct owner for that world's regionized config. The boot-loop path
 *       calls {@link #apply} for worlds already present (none at the initServer hook).</li>
 *   <li><b>Server-global mutation</b> ({@link net.minecraft.server.MinecraftServer#setPlayerIdleTimeout})
 *       must run on the global-region thread. It is dispatched via
 *       {@link Bukkit#getGlobalRegionScheduler()} so it executes on that thread once the
 *       global region starts ticking, rather than being poked directly from the boot thread.</li>
 * </ul>
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
        // Normally empty at the initServer hook (worlds load later); the WorldLoadEvent listener
        // catches each world as it loads. Kept for robustness if a world is already present.
        for (org.bukkit.World w : Bukkit.getWorlds()) bridge.apply(w, plugin);
        // server.idle-timeout is a server-global mutation: dispatch onto the global-region thread.
        if (SourbyCraftConfig.idleTimeout > 0) {
            final int idle = SourbyCraftConfig.idleTimeout;
            Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
                net.minecraft.server.MinecraftServer.getServer().setPlayerIdleTimeout(idle);
                dev.iyanz.sourbycraft.util.SourbyLogger.info("[bridge] server.idle-timeout -> " + idle + " min");
            });
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
            // Only step ALTERNATE_CURRENT back down; an explicit EIGENCRAFT choice in paper config is respected.
            if (paper.misc.redstoneImplementation == io.papermc.paper.configuration.WorldConfiguration.Misc.RedstoneImplementation.ALTERNATE_CURRENT) {
                paper.misc.redstoneImplementation = io.papermc.paper.configuration.WorldConfiguration.Misc.RedstoneImplementation.VANILLA;
                applied.append(" redstone=vanilla");
            }
        }
        if (applied.length() > 0) {
            dev.iyanz.sourbycraft.util.SourbyLogger.info("[bridge] " + world.getName() + ":" + applied);
        }
    }
}
