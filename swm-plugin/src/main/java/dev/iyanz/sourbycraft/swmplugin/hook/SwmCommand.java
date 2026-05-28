package dev.iyanz.sourbycraft.swmplugin.hook;

import dev.iyanz.sourbycraft.swm.api.AdvancedSlimePaperAPI;
import dev.iyanz.sourbycraft.swm.api.SlimeWorld;
import dev.iyanz.sourbycraft.swm.api.SlimeWorldInstance;
import dev.iyanz.sourbycraft.swm.api.SlimePropertyMap;
import dev.iyanz.sourbycraft.swm.api.SlimeLoader;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SwmCommand implements TabExecutor {
    private final LoaderManager loaderManager;
    private final AdvancedSlimePaperAPI api = AdvancedSlimePaperAPI.instance();

    public SwmCommand(LoaderManager loaderManager) {
        this.loaderManager = loaderManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ENGLISH) : "list";
        switch (sub) {
            case "list" -> handleList(sender, args);
            case "load" -> handleLoad(sender, args);
            case "save" -> handleSave(sender, args);
            case "unload" -> handleUnload(sender, args);
            case "info" -> handleInfo(sender);
            default -> sender.sendMessage("§7/swm <list|load|save|unload|info>");
        }
        return true;
    }

    private SlimeLoader resolveLoader(String[] args) {
        if (args.length > 1) {
            SlimeLoader loader = loaderManager.getLoader(args[args.length - 1]);
            if (loader != null) return loader;
        }
        return loaderManager.getDefaultLoader();
    }

    private void handleList(CommandSender s, String[] args) {
        SlimeLoader loader = resolveLoader(args);
        Logger logger = Bukkit.getLogger();
        try {
            var worlds = loader.listWorlds();
            if (worlds.isEmpty()) { s.sendMessage("§7No slime worlds found"); return; }
            s.sendMessage("§bSlime Worlds §7(" + worlds.size() + "):");
            for (String w : worlds) {
                try {
                    boolean loaded = api.getLoadedWorld(w) != null;
                    s.sendMessage("  " + (loaded ? "§a" : "§e") + w + (loaded ? " §7[LOADED]" : ""));
                } catch (Exception e) {
                    s.sendMessage("  §e" + w + " §7→ §c?");
                    logger.log(Level.WARNING, "Failed to check loaded status for world " + w, e);
                }
            }
        } catch (Exception e) {
            s.sendMessage("§cError: " + e.getMessage());
            logger.log(Level.WARNING, "Failed to list worlds", e);
        }
    }

    private void handleLoad(CommandSender s, String[] args) {
        if (args.length < 2) { s.sendMessage("§7/swm load <world> [loader]"); return; }
        String name = args[1];
        if (api.getLoadedWorld(name) != null) { s.sendMessage("§eAlready loaded"); return; }

        SlimeLoader loader = args.length > 2 ? loaderManager.getLoader(args[2]) : loaderManager.getDefaultLoader();
        if (loader == null) { s.sendMessage("§cNo loader configured"); return; }

        try {
            SlimeWorld world = api.readWorld(loader, name, false, new SlimePropertyMap());
            api.loadWorld(world, true);
            s.sendMessage("§a" + name + " loaded");
        } catch (Exception e) {
            s.sendMessage("§cFailed: " + e.getMessage());
            Bukkit.getLogger().log(Level.WARNING, "Failed to load world " + name, e);
        }
    }

    private void handleSave(CommandSender s, String[] args) {
        if (args.length < 2) { s.sendMessage("§7/swm save <world>"); return; }
        SlimeWorldInstance inst = api.getLoadedWorld(args[1]);
        if (inst == null) { s.sendMessage("§cNot loaded"); return; }
        try {
            api.saveWorld(inst);
            s.sendMessage("§aSaved " + args[1]);
        } catch (Exception e) {
            s.sendMessage("§cSave failed: " + e.getMessage());
        }
    }

    private void handleUnload(CommandSender s, String[] args) {
        if (args.length < 2) { s.sendMessage("§7/swm unload <world>"); return; }
        String name = args[1];
        SlimeWorldInstance inst = api.getLoadedWorld(name);
        if (inst == null) { s.sendMessage("§cNot loaded"); return; }
        try {
            api.saveWorld(inst);
        } catch (Exception e) {
            s.sendMessage("§cSave before unload failed: " + e.getMessage());
            return;
        }
        if (Bukkit.unloadWorld(inst.getBukkitWorld(), false)) {
            s.sendMessage("§a" + name + " unloaded");
        } else {
            s.sendMessage("§cFailed to unload " + name);
        }
    }

    private void handleInfo(CommandSender s) {
        int loaded = api.getLoadedWorlds().size();
        s.sendMessage("§bSWM v5.0 §7| §fLoaded: §a" + loaded);
        s.sendMessage("§7Loaders:");
        for (Map.Entry<String, SlimeLoader> entry : loaderManager.getLoaders().entrySet()) {
            try {
                int count = entry.getValue().listWorlds().size();
                s.sendMessage("  §e" + entry.getKey() + " §7→ " + count + " world(s)");
            } catch (Exception e) {
                s.sendMessage("  §e" + entry.getKey() + " §7→ §c" + e.getMessage());
            }
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ENGLISH);
            return List.of("list", "load", "save", "unload", "info").stream()
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        }
        String sub = args[0].toLowerCase(Locale.ENGLISH);
        if (args.length == 2 && (sub.equals("load") || sub.equals("save") || sub.equals("unload"))) {
            try {
                SlimeLoader loader = loaderManager.getDefaultLoader();
                return loader != null ? loader.listWorlds() : List.of();
            } catch (Exception e) {
                return List.of();
            }
        }
        if (args.length == 3 && sub.equals("load")) {
            return new ArrayList<>(loaderManager.getLoaders().keySet());
        }
        return List.of();
    }
}
