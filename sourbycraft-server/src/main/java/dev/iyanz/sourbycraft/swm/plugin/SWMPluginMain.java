package dev.iyanz.sourbycraft.swm.plugin;

import dev.iyanz.sourbycraft.swm.SlimeWorldLoader;
import dev.iyanz.sourbycraft.swm.plugin.loader.*;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.java.JavaPlugin;

public class SWMPluginMain extends JavaPlugin {

    private static SWMPluginMain instance;

    @Override
    public void onEnable() {
        instance = this;
        LoaderManager.init();
        getLogger().info("SourbyCraft SWM enabled with " + LoaderManager.all().size() + " loader(s)");
        loadAllWorlds();
    }

    @Override
    public void onDisable() {
        saveAllWorlds();
        instance = null;
    }

    public static SWMPluginMain get() { return instance; }

    public void loadAllWorlds() {
        SlimeLoader loader = LoaderManager.getDefault();
        if (loader == null) return;
        try {
            for (String name : loader.listWorlds()) {
                if (getServer().getWorld(name) != null) continue;
                try {
                    SlimeWorldLoader.extract(name);
                    World w = getServer().createWorld(WorldCreator.name(name));
                    if (w != null) getLogger().info("Loaded: " + name);
                } catch (Exception e) {
                    getLogger().warning("Failed: " + name + " - " + e.getMessage());
                }
            }
        } catch (IOException e) {
            getLogger().severe("Loader error: " + e.getMessage());
        }
    }

    public void saveAllWorlds() {
        for (World w : getServer().getWorlds()) w.save();
    }
}
