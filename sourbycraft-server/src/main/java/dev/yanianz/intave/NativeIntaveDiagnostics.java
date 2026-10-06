package dev.yanianz.intave;

import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

/** Server command remains available when the private engine is disabled, absent or failed. */
public final class NativeIntaveDiagnostics extends Command {
    private NativeIntaveDiagnostics() {
        super("intave-native", "Native anticheat initialization status", "/intave-native status", List.of());
        setPermission("sourbycraft.command.intave");
    }

    public static void register() {
        var manager = Bukkit.getPluginManager();
        if (manager.getPermission("sourbycraft.command.intave") == null) {
            manager.addPermission(new Permission("sourbycraft.command.intave", PermissionDefault.OP));
        }
        Bukkit.getCommandMap().register("sourbycraft", new NativeIntaveDiagnostics());
    }

    @Override public boolean execute(CommandSender sender, String label, String[] args) {
        if (!testPermission(sender)) return true;
        if (args.length > 1 || args.length == 1 && !args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("Usage: /intave-native status");
            return true;
        }
        var status = NativeIntave.status();
        sender.sendMessage("Intave native: " + status.state());
        sender.sendMessage(status.detail());
        return true;
    }

    @Override public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return testPermissionSilent(sender) && args.length == 1 && "status".startsWith(args[0].toLowerCase(java.util.Locale.ROOT))
            ? List.of("status") : List.of();
    }
}
