package dev.iyanz.sourbycraft.swmplugin.hook;

import dev.iyanz.sourbycraft.swm.api.AdvancedSlimePaperAPI;
import dev.iyanz.sourbycraft.swm.api.SlimeWorld;
import dev.iyanz.sourbycraft.swm.api.SlimeWorldInstance;
import dev.iyanz.sourbycraft.swm.api.SlimePropertyMap;
import dev.iyanz.sourbycraft.swm.api.SlimeNMSBridge;
import dev.iyanz.sourbycraft.swm.loader.FileLoader;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.LinkedHashMap;
import java.util.Map;

public class SWPlugin extends JavaPlugin {
    private final Map<String, SlimeWorld> worldsToLoad = new LinkedHashMap<>();
    private static SWPlugin instance;

    @Override
    public void onLoad() {
        instance = this;
        getLogger().info("SourbyCraft SWM " + getPluginMeta().getVersion() + " loading...");

        String fileDir = getConfig().getString("swm.file-dir", "slime_worlds");
        FileLoader fileLoader = new FileLoader(fileDir);

        boolean enabled = getConfig().getBoolean("swm.enabled", true);
        if (!enabled) return;

        for (String worldName : getConfig().getStringList("swm.load-worlds")) {
            try {
                SlimeWorld world = AdvancedSlimePaperAPI.instance().readWorld(
                    fileLoader, worldName, false, new SlimePropertyMap());
                worldsToLoad.put(worldName, world);
                getLogger().info("Read slime world: " + worldName);
            } catch (Exception e) {
                getLogger().warning("Failed to read world " + worldName + ": " + e.getMessage());
            }
        }

        if (worldsToLoad.containsKey("world")) {
            SlimeNMSBridge.instance().setDefaultWorlds(
                worldsToLoad.get("world"),
                worldsToLoad.get("world_nether"),
                worldsToLoad.get("world_the_end")
            );
        }
    }

    @Override
    public void onEnable() {
        for (var entry : worldsToLoad.entrySet()) {
            try {
                AdvancedSlimePaperAPI.instance().loadWorld(entry.getValue(), true);
                getLogger().info("Loaded world: " + entry.getKey());
            } catch (Exception e) {
                getLogger().warning("Failed to load world " + entry.getKey() + ": " + e.getMessage());
            }
        }
        worldsToLoad.clear();

        getCommand("swm").setExecutor(new SwmCommand());

        saveDefaultConfig();
        getLogger().info("SourbyCraft SWM " + getPluginMeta().getVersion() + " enabled");
    }

    @Override
    public void onDisable() {
        for (SlimeWorldInstance world : AdvancedSlimePaperAPI.instance().getLoadedWorlds()) {
            try {
                AdvancedSlimePaperAPI.instance().saveWorld(world);
            } catch (Exception e) {
                getLogger().warning("Failed to save " + world.getName() + ": " + e.getMessage());
            }
        }
    }

    public static SWPlugin instance() { return instance; }
}
