package dev.iyanz.sourbycraft.swm.plugin;

import dev.iyanz.sourbycraft.swm.api.AdvancedSlimePaperAPI;
import dev.iyanz.sourbycraft.swm.api.SlimeWorld;
import dev.iyanz.sourbycraft.swm.api.SlimeWorldInstance;
import dev.iyanz.sourbycraft.swm.api.SlimePropertyMap;
import dev.iyanz.sourbycraft.swm.loader.FileLoader;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.List;

public class SwmCommand implements TabExecutor {
    private final FileLoader fileLoader = new FileLoader("slime_worlds");
    private final AdvancedSlimePaperAPI api = AdvancedSlimePaperAPI.instance();

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase() : "list";
        switch (sub) {
            case "list" -> handleList(sender);
            case "load" -> handleLoad(sender, args);
            case "save" -> handleSave(sender, args);
            case "info" -> handleInfo(sender);
            default -> sender.sendMessage("§7/swm <list|load|save|info>");
        }
        return true;
    }

    private void handleList(CommandSender s) {
        try {
            var worlds = fileLoader.listWorlds();
            if (worlds.isEmpty()) { s.sendMessage("§7No slime worlds"); return; }
            s.sendMessage("§bSlime Worlds §7(" + worlds.size() + "):");
            for (String w : worlds) {
                boolean loaded = api.getLoadedWorld(w) != null;
                s.sendMessage("  " + (loaded ? "§a" : "§e") + w + (loaded ? " §7[LOADED]" : ""));
            }
        } catch (Exception e) {
            s.sendMessage("§cError: " + e.getMessage());
        }
    }

    private void handleLoad(CommandSender s, String[] args) {
        if (args.length < 2) { s.sendMessage("§7/swm load <world>"); return; }
        String name = args[1];
        if (api.getLoadedWorld(name) != null) { s.sendMessage("§eAlready loaded"); return; }
        try {
            SlimeWorld world = api.readWorld(fileLoader, name, false, new SlimePropertyMap());
            api.loadWorld(world, true);
            s.sendMessage("§a" + name + " loaded");
        } catch (Exception e) {
            s.sendMessage("§cFailed: " + e.getMessage());
        }
    }

    private void handleSave(CommandSender s, String[] args) {
        if (args.length < 2) { s.sendMessage("§7/swm save <world>"); return; }
        SlimeWorldInstance inst = api.getLoadedWorld(args[1]);
        if (inst == null) { s.sendMessage("§cNot loaded"); return; }
        try {
            api.saveWorld(inst);
            s.sendMessage("§aSaved");
        } catch (Exception e) {
            s.sendMessage("§cSave failed: " + e.getMessage());
        }
    }

    private void handleInfo(CommandSender s) {
        int loaded = api.getLoadedWorlds().size();
        try {
            int found = fileLoader.listWorlds().size();
            s.sendMessage("§bSWM §7| §fLoaded: §a" + loaded + " §7| Found: §e" + found);
        } catch (Exception e) {
            s.sendMessage("§bSWM §7| §fLoaded: §a" + loaded);
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) return List.of("list", "load", "save", "info");
        if (args.length == 2 && (args[0].equals("load") || args[0].equals("save"))) {
            try {
                return fileLoader.listWorlds();
            } catch (Exception e) {
                return List.of();
            }
        }
        return List.of();
    }
}
