package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.util.GeoUtil;
import dev.iyanz.sourbycraft.util.VirtualExecutor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static net.kyori.adventure.text.Component.text;

public class PingCommand extends Command {
    public PingCommand(String name) {
        super(name);
        this.description = "Player latency";
        this.usageMessage = "/ping [player]";
    }

    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        boolean other = args.length > 0 && !args[0].equals(s.getName());
        if (other && !s.hasPermission("sourbycraft.command.ping.other")) {
            s.sendMessage(text("No permission", SourbyCraftColors.DANGER));
            return true;
        }
        Player t = args.length > 0 ? Bukkit.getPlayer(args[0]) : (s instanceof Player p ? p : null);
        if (t == null) {
            s.sendMessage(text("Player not found", SourbyCraftColors.DANGER));
            return true;
        }
        int ping = t.getPing();
        TextColor pc = ping < 50 ? SourbyCraftColors.SUCCESS
            : ping < 100 ? SourbyCraftColors.PRIMARY
            : ping < 200 ? SourbyCraftColors.WARNING
            : SourbyCraftColors.DANGER;
        s.sendMessage(text().append(text("Ping: ", SourbyCraftColors.LABEL)).append(text(ping + "ms", pc)));
        String brand = t.getClientBrandName() != null ? t.getClientBrandName() : "vanilla";
        s.sendMessage(text().append(text("Client: ", SourbyCraftColors.LABEL))
            .append(text(brand + " | p" + t.getProtocolVersion(), SourbyCraftColors.VALUE)));
        VirtualExecutor.run(() -> {
            String geo = GeoUtil.lookup(t);
            if (geo != null) {
                net.minecraft.server.MinecraftServer.getServer().execute(() ->
                    s.sendMessage(text().append(text("Location: ", SourbyCraftColors.LABEL))
                        .append(text(geo, SourbyCraftColors.VALUE))));
            }
        });
        return true;
    }
}
