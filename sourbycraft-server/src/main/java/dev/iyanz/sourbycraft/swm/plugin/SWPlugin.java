package dev.iyanz.sourbycraft.swm.plugin;

import dev.iyanz.sourbycraft.swm.api.AdvancedSlimePaperAPI;
import dev.iyanz.sourbycraft.swm.api.SlimeNMSBridge;
import dev.iyanz.sourbycraft.swm.api.SlimeWorld;
import dev.iyanz.sourbycraft.swm.api.exceptions.CorruptedWorldException;
import dev.iyanz.sourbycraft.swm.api.exceptions.NewerFormatException;
import dev.iyanz.sourbycraft.swm.api.exceptions.UnknownWorldException;
import dev.iyanz.sourbycraft.swm.api.SlimeLoader;
import dev.iyanz.sourbycraft.swm.api.SlimePropertyMap;
import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.swm.server.SwmIoExecutor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public class SWPlugin extends JavaPlugin {

    private static final AdvancedSlimePaperAPI ASP = AdvancedSlimePaperAPI.instance();

    private final Map<String, SlimeWorld> worldsToLoad = new HashMap<>();
    private LoaderManager loaderManager;

    private static SwmIoExecutor ioExecutor;

    public static SwmIoExecutor ioExecutor() {
        return ioExecutor;
    }

    public LoaderManager getLoaderManager() {
        return loaderManager;
    }

    @Override
    public void onLoad() {
        ioExecutor = new SwmIoExecutor();

        this.loaderManager = new LoaderManager(SourbyCraftConfig.swmFileDir);

        List<String> erroredWorlds = loadWorlds();

        try {
            Properties props = new Properties();
            props.load(new FileInputStream("server.properties"));
            String defaultWorldName = props.getProperty("level-name");

            if (erroredWorlds.contains(defaultWorldName)) {
                getSLF4JLogger().error("Shutting down server, as the default world could not be loaded.");
                Bukkit.getServer().shutdown();
            } else if (getServer().getAllowNether() && erroredWorlds.contains(defaultWorldName + "_nether")) {
                getSLF4JLogger().error("Shutting down server, as the default nether world could not be loaded.");
                Bukkit.getServer().shutdown();
            } else if (getServer().getAllowEnd() && erroredWorlds.contains(defaultWorldName + "_the_end")) {
                getSLF4JLogger().error("Shutting down server, as the default end world could not be loaded.");
                Bukkit.getServer().shutdown();
            }

            SlimeWorld defaultWorld = worldsToLoad.get(defaultWorldName);
            SlimeWorld netherWorld = getServer().getAllowNether() ? worldsToLoad.get(defaultWorldName + "_nether") : null;
            SlimeWorld endWorld = getServer().getAllowEnd() ? worldsToLoad.get(defaultWorldName + "_the_end") : null;

            SlimeNMSBridge.instance().setDefaultWorlds(defaultWorld, netherWorld, endWorld);
        } catch (IOException ex) {
            getSLF4JLogger().error("Failed to retrieve default world name", ex);
        }
    }

    @Override
    public void onEnable() {
        PluginCommand command = getCommand("swm");
        if (command != null) {
            SwmCommand cmd = new SwmCommand(loaderManager);
            command.setExecutor(cmd);
            command.setTabCompleter(cmd);
        }

        worldsToLoad.values().stream()
                .filter(slimeWorld -> Objects.isNull(Bukkit.getWorld(slimeWorld.getName())))
                .forEach(slimeWorld -> {
                    try {
                        ASP.loadWorld(slimeWorld, true);
                    } catch (RuntimeException exception) {
                        getSLF4JLogger().error("Failed to load world: {}", slimeWorld.getName(), exception);
                    }
                });

        worldsToLoad.clear();
    }

    @Override
    public void onDisable() {
        List<CompletableFuture<Void>> saves = new ArrayList<>();
        for (SlimeWorld world : ASP.getLoadedWorlds()) {
            if (!world.isReadOnly()) {
                saves.add(ASP.saveWorldAsync(world));
            }
        }
        // Wait for all saves to complete
        for (CompletableFuture<Void> save : saves) {
            try {
                save.join();
            } catch (Exception ex) {
                getLogger().severe("Failed to save world: " + ex.getMessage());
            }
        }
        // Unload all worlds
        for (SlimeWorld world : ASP.getLoadedWorlds()) {
            try {
                Bukkit.unloadWorld(world.getName(), false);
            } catch (Exception ignored) {}
        }

        if (ioExecutor != null) {
            ioExecutor.shutdown();
            ioExecutor = null;
        }
    }

    private List<String> loadWorlds() {
        List<String> erroredWorlds = new ArrayList<>();
        SlimeLoader loader = loaderManager.getDefault();

        if (loader == null) return erroredWorlds;

        try {
            for (String worldName : loader.listWorlds()) {
                try {
                    SlimePropertyMap propertyMap = new SlimePropertyMap();
                    SlimeWorld world = ASP.readWorld(loader, worldName, false, propertyMap);
                    worldsToLoad.put(worldName, world);
                } catch (IllegalArgumentException | UnknownWorldException | NewerFormatException |
                         CorruptedWorldException | IOException ex) {
                    String message;
                    if (ex instanceof UnknownWorldException) {
                        message = "world does not exist";
                    } else if (ex instanceof NewerFormatException) {
                        message = "world is serialized in a newer Slime Format version";
                    } else if (ex instanceof CorruptedWorldException) {
                        message = "world seems to be corrupted";
                    } else {
                        message = ex.getMessage();
                    }

                    getSLF4JLogger().error("Failed to load world {}{}", worldName, message.isEmpty() ? "." : ": " + message);
                    erroredWorlds.add(worldName);
                }
            }
        } catch (IOException e) {
            getSLF4JLogger().error("Failed to list worlds", e);
        }

        return erroredWorlds;
    }
}
