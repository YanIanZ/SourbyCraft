package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.swm.loader.FileLoader;
import dev.iyanz.sourbycraft.wildstacker.WildstackerManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

import static net.kyori.adventure.text.Component.text;

public class SwmCommand extends Command {
    public SwmCommand(String n) {
        super(n);
        this.description = "SWM control";
        this.usageMessage = "/swm <list|load|unload|save|inspect|delete|status>";
        this.setPermission("sourbycraft.command.swm");
    }

    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;
        String sub = args.length > 0 ? args[0].toLowerCase(java.util.Locale.ROOT) : "status";
        try {
            switch (sub) {
                case "list" -> doList(s);
                case "load" -> doLoad(s, args);
                case "unload" -> doUnload(s, args);
                case "save" -> doSave(s, args);
                case "inspect" -> doInspect(s, args);
                case "delete" -> doDelete(s, args);
                case "help" -> doHelp(s);
                default -> doStatus(s);
            }
        } catch (Exception e) {
            s.sendMessage(text("Error: " + e.getMessage(), SourbyCraftColors.DANGER));
        }
        return true;
    }

    private void doStatus(CommandSender s) throws Exception {
        s.sendMessage(text().append(text("SWM: ", SourbyCraftColors.LABEL))
            .append(text(SourbyCraftConfig.swmEnabled ? "Enabled" : "Disabled",
                SourbyCraftConfig.swmEnabled ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DANGER))
            .append(text(" " + normalizeVersion(SourbyCraftConfig.swmVersion), SourbyCraftColors.DIM)));
        s.sendMessage(text().append(text("Worlds: ", SourbyCraftColors.LABEL))
            .append(text(new FileLoader(SourbyCraftConfig.swmFileDir).listWorlds().size() + " found", SourbyCraftColors.VALUE)));
        s.sendMessage(text("Type /swm help for subcommands", SourbyCraftColors.DIM));
    }

    private void doHelp(CommandSender s) {
        s.sendMessage(text("/swm subcommands:", SourbyCraftColors.HEADER));
        s.sendMessage(text("  list                  ", SourbyCraftColors.LABEL).append(text("— list all SWM worlds on disk", SourbyCraftColors.DIM)));
        s.sendMessage(text("  load <world>          ", SourbyCraftColors.LABEL).append(text("— load a saved world", SourbyCraftColors.DIM)));
        s.sendMessage(text("  unload <world>        ", SourbyCraftColors.LABEL).append(text("— unload (saves first)", SourbyCraftColors.DIM)));
        s.sendMessage(text("  save <world>          ", SourbyCraftColors.LABEL).append(text("— force save loaded world", SourbyCraftColors.DIM)));
        s.sendMessage(text("  inspect <world>       ", SourbyCraftColors.LABEL).append(text("— show chunk count + loaded state", SourbyCraftColors.DIM)));
        s.sendMessage(text("  delete <world>        ", SourbyCraftColors.LABEL).append(text("— remove from disk (must be unloaded)", SourbyCraftColors.DIM)));
        s.sendMessage(text("  status                ", SourbyCraftColors.LABEL).append(text("— SWM summary", SourbyCraftColors.DIM)));
    }

    private void doList(CommandSender s) throws Exception {
        var w = new FileLoader(SourbyCraftConfig.swmFileDir).listWorlds();
        if (w.isEmpty()) {
            s.sendMessage(text("No slime worlds", SourbyCraftColors.DIM));
            return;
        }
        s.sendMessage(text("Slime Worlds (" + w.size() + "):", SourbyCraftColors.HEADER));
        for (var x : w) {
            boolean loaded = Bukkit.getWorld(x) != null;
            s.sendMessage(text().append(text("  " + x, loaded ? SourbyCraftColors.SUCCESS : SourbyCraftColors.VALUE))
                .append(text(loaded ? " [LOADED]" : "", SourbyCraftColors.SUCCESS)));
        }
    }

    // Strip duplicate leading 'v' to fix "vv10" rendering when stored version
    // already includes a 'v' prefix (e.g. "v7-REL").
    private static String normalizeVersion(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String s = raw.trim();
        if (s.startsWith("v") || s.startsWith("V")) return s;
        return "v" + s;
    }

    private boolean validName(CommandSender s, String nm) {
        if (nm == null || nm.isBlank() || nm.contains("/") || nm.contains("\\") || nm.contains("..") || nm.startsWith(".")) {
            s.sendMessage(text("Invalid world name: " + nm, SourbyCraftColors.DANGER));
            return false;
        }
        return true;
    }

    private void doLoad(CommandSender s, String[] args) {
        if (args.length < 2) { s.sendMessage(text("/swm load <world>", SourbyCraftColors.DIM)); return; }
        String nm = args[1];
        if (!validName(s, nm)) return;
        if (Bukkit.getWorld(nm) != null) { s.sendMessage(text("Already loaded", SourbyCraftColors.WARNING)); return; }
        Plugin owner = WildstackerManager.ownerPlugin();
        if (owner == null) { s.sendMessage(text("Server not ready (no plugin available)", SourbyCraftColors.DANGER)); return; }
        s.sendMessage(text("Loading " + nm + "...", SourbyCraftColors.LABEL));
        Bukkit.getScheduler().runTask(owner, () -> {
            Bukkit.createWorld(WorldCreator.name(nm));
            s.sendMessage(text(nm + " loaded", SourbyCraftColors.SUCCESS));
        });
    }

    private void doUnload(CommandSender s, String[] args) {
        if (args.length < 2) { s.sendMessage(text("/swm unload <world>", SourbyCraftColors.DIM)); return; }
        String nm = args[1];
        if (!validName(s, nm)) return;
        World w = Bukkit.getWorld(nm);
        if (w == null) { s.sendMessage(text("Not loaded: " + nm, SourbyCraftColors.WARNING)); return; }
        if (!w.getPlayers().isEmpty()) {
            s.sendMessage(text("World has " + w.getPlayers().size() + " player(s) — cannot unload", SourbyCraftColors.DANGER));
            return;
        }
        boolean ok = Bukkit.unloadWorld(w, true);
        s.sendMessage(text(ok ? nm + " unloaded" : "Unload failed for " + nm,
            ok ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DANGER));
    }

    private void doSave(CommandSender s, String[] args) {
        if (args.length < 2) { s.sendMessage(text("/swm save <world>", SourbyCraftColors.DIM)); return; }
        String nm = args[1];
        if (!validName(s, nm)) return;
        World w = Bukkit.getWorld(nm);
        if (w == null) { s.sendMessage(text("Not loaded: " + nm, SourbyCraftColors.WARNING)); return; }
        w.save();
        s.sendMessage(text(nm + " saved", SourbyCraftColors.SUCCESS));
    }

    private void doInspect(CommandSender s, String[] args) throws Exception {
        if (args.length < 2) { s.sendMessage(text("/swm inspect <world>", SourbyCraftColors.DIM)); return; }
        String nm = args[1];
        if (!validName(s, nm)) return;
        FileLoader loader = new FileLoader(SourbyCraftConfig.swmFileDir);
        boolean onDisk = loader.worldExists(nm);
        World live = Bukkit.getWorld(nm);
        boolean loaded = live != null;
        s.sendMessage(text("SWM inspect: " + nm, SourbyCraftColors.HEADER));
        s.sendMessage(text().append(text("  On disk: ", SourbyCraftColors.LABEL))
            .append(text(onDisk ? "yes" : "no", onDisk ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DIM)));
        s.sendMessage(text().append(text("  Loaded:  ", SourbyCraftColors.LABEL))
            .append(text(loaded ? "yes" : "no", loaded ? SourbyCraftColors.SUCCESS : SourbyCraftColors.DIM)));
        if (loaded) {
            int liveChunks = live.getLoadedChunks().length;
            int players = live.getPlayers().size();
            s.sendMessage(text().append(text("  Loaded chunks: ", SourbyCraftColors.LABEL))
                .append(text(String.valueOf(liveChunks), SourbyCraftColors.VALUE)));
            s.sendMessage(text().append(text("  Players: ", SourbyCraftColors.LABEL))
                .append(text(String.valueOf(players), SourbyCraftColors.VALUE)));
        }
        if (onDisk) {
            try {
                byte[] data = loader.readWorld(nm);
                s.sendMessage(text().append(text("  Disk size: ", SourbyCraftColors.LABEL))
                    .append(text((data.length / 1024) + " KB", SourbyCraftColors.VALUE)));
            } catch (Exception e) {
                s.sendMessage(text("  Read error: " + e.getMessage(), SourbyCraftColors.DANGER));
            }
        }
    }

    private void doDelete(CommandSender s, String[] args) throws Exception {
        if (args.length < 2) { s.sendMessage(text("/swm delete <world>", SourbyCraftColors.DIM)); return; }
        String nm = args[1];
        if (!validName(s, nm)) return;
        if (Bukkit.getWorld(nm) != null) {
            s.sendMessage(text("World is loaded — unload first with /swm unload " + nm, SourbyCraftColors.DANGER));
            return;
        }
        FileLoader loader = new FileLoader(SourbyCraftConfig.swmFileDir);
        if (!loader.worldExists(nm)) {
            s.sendMessage(text("World does not exist on disk: " + nm, SourbyCraftColors.WARNING));
            return;
        }
        loader.deleteWorld(nm);
        s.sendMessage(text(nm + " deleted from disk", SourbyCraftColors.SUCCESS));
    }
}
