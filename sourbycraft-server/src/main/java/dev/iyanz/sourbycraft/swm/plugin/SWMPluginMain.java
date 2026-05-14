package dev.iyanz.sourbycraft.swm.plugin;

import dev.iyanz.sourbycraft.swm.SlimeWorldLoader;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.java.JavaPlugin;

public class SWMPluginMain extends JavaPlugin {

    private static SWMPluginMain instance;

    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("SourbyCraft SWM enabled");
        loadAllWorlds();
    }

    @Override
    public void onDisable() {
        saveAllWorlds();
        instance = null;
    }

    public static SWMPluginMain get() { return instance; }

    public void loadAllWorlds() {
        for (var info : SlimeWorldLoader.discoverWorlds()) {
            if (getServer().getWorld(info.name()) != null) continue;
            try {
                SlimeWorldLoader.extract(info.name());
                World w = getServer().createWorld(WorldCreator.name(info.name()));
                if (w != null) getLogger().info("Loaded: " + info.name());
            } catch (Exception e) {
                getLogger().warning("Failed: " + info.name());
            }
        }
    }

    public void saveAllWorlds() {
        for (World w : getServer().getWorlds()) {
            w.save();
        }
    }
}
