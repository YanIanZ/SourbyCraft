package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.swm.loader.FileLoader;
import dev.iyanz.sourbycraft.wildstacker.WildstackerManager;
import org.bukkit.Bukkit;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import static net.kyori.adventure.text.Component.text;

public class SwmCommand extends Command {
    public SwmCommand(String n) {
        super(n);
        this.description = "SWM control";
        this.usageMessage = "/swm <list|load|status>";
        this.setPermission("sourbycraft.command.swm");
    }

    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;
        String sub = args.length > 0 ? args[0].toLowerCase() : "status";
        try {
            switch (sub) {
                case "list" -> {
                    var w = new FileLoader(SourbyCraftConfig.swmFileDir).listWorlds();
                    if (w.isEmpty()) {
                        s.sendMessage(text("No slime worlds", SourbyCraftColors.DIM));
                        return true;
                    }
                    s.sendMessage(text("Slime Worlds (" + w.size() + "):", SourbyCraftColors.HEADER));
                    for (var x : w) {
                        boolean loaded = Bukkit.getWorld(x) != null;
                        s.sendMessage(text().append(text("  " + x, loaded ? SourbyCraftColors.SUCCESS : SourbyCraftColors.VALUE))
                            .append(text(loaded ? " [LOADED]" : "", SourbyCraftColors.SUCCESS)));
                    }
                }
                case "load" -> {
                    if (args.length < 2) {
                        s.sendMessage(text("/swm load <world>", SourbyCraftColors.DIM));
                        return true;
                    }
                    String nm = args[1];
                    if (Bukkit.getWorld(nm) != null) {
                        s.sendMessage(text("Already loaded", SourbyCraftColors.WARNING));
                        return true;
                    }
                    Plugin owner = WildstackerManager.ownerPlugin();
                    if (owner == null) {
                        s.sendMessage(text("Server not ready (no plugin available)", SourbyCraftColors.DANGER));
                        return true;
                    }
                    s.sendMessage(text("Loading " + nm + "...", SourbyCraftColors.LABEL));
                    Bukkit.getScheduler().runTask(owner, () -> {
                        Bukkit.createWorld(WorldCreator.name(nm));
                        s.sendMessage(text(nm + " loaded", SourbyCraftColors.SUCCESS));
                    });
                }
                default -> {
                    s.sendMessage(text().append(text("SWM: ", SourbyCraftColors.LABEL))
                        .append(text(SourbyCraftConfig.swmEnabled ? "Enabled" : "Disabled",
                            SourbyCraftConfig.swmEnabled ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DANGER))
                        .append(text(" v" + SourbyCraftConfig.swmVersion, SourbyCraftColors.DIM)));
                    s.sendMessage(text().append(text("Worlds: ", SourbyCraftColors.LABEL))
                        .append(text(new FileLoader(SourbyCraftConfig.swmFileDir).listWorlds().size() + " found", SourbyCraftColors.VALUE)));
                }
            }
        } catch (Exception e) {
            s.sendMessage(text("Error: " + e.getMessage(), SourbyCraftColors.DANGER));
        }
        return true;
    }
}
