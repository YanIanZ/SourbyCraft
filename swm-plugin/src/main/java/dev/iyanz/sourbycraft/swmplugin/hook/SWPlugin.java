package dev.iyanz.sourbycraft.swmplugin.hook;

import dev.iyanz.sourbycraft.swm.api.AdvancedSlimePaperAPI;
import dev.iyanz.sourbycraft.swm.api.SlimeWorld;
import dev.iyanz.sourbycraft.swm.api.SlimeWorldInstance;
import dev.iyanz.sourbycraft.swm.api.SlimePropertyMap;
import dev.iyanz.sourbycraft.swm.api.SlimeNMSBridge;
import dev.iyanz.sourbycraft.swm.api.SlimeLoader;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SWPlugin extends JavaPlugin {
    private final Map<String, SlimeWorld> worldsToLoad = new LinkedHashMap<>();
    private LoaderManager loaderManager;
    private final AtomicBoolean autoSaveRunning = new AtomicBoolean(false);
    private static SWPlugin instance;

    @Override
    public void onLoad() {
        instance = this;
        getLogger().info("SourbyCraft SWM " + getPluginMeta().getVersion() + " loading...");

        saveDefaultConfig();
        boolean enabled = getConfig().getBoolean("swm.enabled", true);
        if (!enabled) return;

        loaderManager = new LoaderManager(getConfig(), getLogger());
        SlimeLoader defaultLoader = loaderManager.getDefaultLoader();

        if (defaultLoader == null) {
            getLogger().warning("No SWM loader configured — world loading disabled");
            return;
        }

        AdvancedSlimePaperAPI api = AdvancedSlimePaperAPI.instance();

        for (String worldName : getConfig().getStringList("swm.load-worlds")) {
            try {
                SlimeWorld world = api.readWorld(
                        defaultLoader, worldName, false, new SlimePropertyMap());
                worldsToLoad.put(worldName, world);
                getLogger().info("Read slime world: " + worldName);
            } catch (Exception e) {
                getLogger().warning("Failed to read world " + worldName + ": " + e.getMessage());
            }
        }

        if (worldsToLoad.containsKey("world")) {
            try {
                SlimeNMSBridge.instance().setDefaultWorlds(
                        worldsToLoad.get("world"),
                        worldsToLoad.get("world_nether"),
                        worldsToLoad.get("world_the_end")
                );
            } catch (Exception e) {
                getLogger().warning("Failed to set default worlds: " + e.getMessage());
            }
        }
    }

    @Override
    public void onEnable() {
        if (!getConfig().getBoolean("swm.enabled", true)) {
            getLogger().info("SourbyCraft SWM disabled");
            return;
        }

        for (var entry : worldsToLoad.entrySet()) {
            try {
                AdvancedSlimePaperAPI.instance().loadWorld(entry.getValue(), true);
                getLogger().info("Loaded world: " + entry.getKey());
            } catch (Exception e) {
                getLogger().warning("Failed to load world " + entry.getKey() + ": " + e.getMessage());
            }
        }
        worldsToLoad.clear();

        PluginCommand swmCmd = getCommand("swm");
        if (swmCmd != null) {
            swmCmd.setExecutor(new SwmCommand(loaderManager));
        }

        startAutoSave();
        getLogger().info("SourbyCraft SWM " + getPluginMeta().getVersion() + " enabled");
    }

    @Override
    public void onDisable() {
        getLogger().info("Saving all loaded worlds...");
        var futures = AdvancedSlimePaperAPI.instance().getLoadedWorlds().stream()
                .map(world -> {
                    try {
                        return AdvancedSlimePaperAPI.instance().saveWorldAsync(world);
                    } catch (Exception e) {
                        getLogger().warning("Failed to initiate save for " + world.getName() + ": " + e.getMessage());
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                })
                .toArray(CompletableFuture[]::new);

        try {
            CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            getLogger().warning("World save timed out or failed: " + e.getMessage());
        }

        getLogger().info("All worlds saved");
    }

    private void startAutoSave() {
        int intervalMinutes = getConfig().getInt("swm.auto-save-interval-minutes", 5);
        if (intervalMinutes <= 0) return;

        long intervalTicks = intervalMinutes * 60L * 20L;
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!autoSaveRunning.compareAndSet(false, true)) return;
            try {
                AdvancedSlimePaperAPI api = AdvancedSlimePaperAPI.instance();
                for (SlimeWorldInstance world : api.getLoadedWorlds()) {
                    try {
                        api.saveWorldAsync(world);
                    } catch (Exception e) {
                        getLogger().warning("Auto-save failed for " + world.getName() + ": " + e.getMessage());
                    }
                }
            } finally {
                autoSaveRunning.set(false);
            }
        }, intervalTicks, intervalTicks);

        getLogger().info("Auto-save scheduled every " + intervalMinutes + " minute(s)");
    }

    public LoaderManager getLoaderManager() { return loaderManager; }
    public static SWPlugin instance() { return instance; }
}
