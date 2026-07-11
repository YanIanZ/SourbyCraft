package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.util.BarUtil;
import dev.iyanz.sourbycraft.util.GeoUtil;
import dev.iyanz.sourbycraft.util.VirtualExecutor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import static net.kyori.adventure.text.Component.text;

/**
 * Custom /ping. Renders a coloured latency bar (0-500 ms maps to 100-0 %
 * — lower latency is higher fill) so the readout reads like the rest
 * of the SourbyCraft suite. Geo lookup keeps its async path.
 */
public class PingCommand extends Command {

    private static final String DIVIDER = BarUtil.FILLED.repeat(BarUtil.DEFAULT_WIDTH);

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

        // Map 0-500ms onto 100-0% so a healthy ping shows a full bar.
        double pct = Math.max(0.0, 100.0 - Math.min(ping, 500) / 5.0);

        s.sendMessage(text(DIVIDER, SourbyCraftColors.PRIMARY));
        s.sendMessage(text()
            .append(text(BarUtil.FILLED + " ", SourbyCraftColors.PRIMARY))
            .append(text("Ping ", SourbyCraftColors.HEADER))
            .append(text(t.getName(), SourbyCraftColors.LABEL))
            .build());
        s.sendMessage(text()
            .append(text("  ", SourbyCraftColors.DIM))
            .append(text(BarUtil.bar(pct, BarUtil.DEFAULT_WIDTH), pc))
            .append(text("  " + ping + "ms", pc))
            .build());

        String brand = t.getClientBrandName() != null ? t.getClientBrandName() : "vanilla";
        s.sendMessage(text()
            .append(text("  Client: ", SourbyCraftColors.LABEL))
            .append(text(brand, SourbyCraftColors.VALUE))
            .append(text("  (protocol " + t.getProtocolVersion() + ")", SourbyCraftColors.DIM))
            .build());

        // Always emit a Location row so the panel layout is consistent regardless of geo
        // outcome. Show a "looking up..." placeholder synchronously, then the async path
        // either rewrites by sending the resolved row (city / country / ISP) or — on a
        // miss (null address, proxy-stripped IP, RFC1918, or the local db-ip.com mmdb
        // absent/unreadable) — appends a labelled "unavailable" line so operators can tell
        // at a glance whether the geo signal was empty vs the lookup never ran.
        s.sendMessage(text()
            .append(text("  Location: ", SourbyCraftColors.LABEL))
            .append(text("looking up...", SourbyCraftColors.DIM))
            .build());

        VirtualExecutor.run(() -> {
            String geo;
            try {
                geo = GeoUtil.lookup(t);
            } catch (Throwable err) {
                geo = null;
            }
            final String geoFinal = geo;
            // Folia-safe reply hop. MinecraftServer.execute() throws UnsupportedOperationException on
            // Folia (no global main thread), so bounce the reply onto the sender's own scheduler: a
            // player hops to its region scheduler; a console send is thread-safe so it goes direct.
            SourbyReply.run(s, () -> {
                if (geoFinal != null && !geoFinal.isEmpty()) {
                    s.sendMessage(text()
                        .append(text("  Location: ", SourbyCraftColors.LABEL))
                        .append(text(geoFinal, SourbyCraftColors.VALUE))
                        .build());
                } else {
                    s.sendMessage(text()
                        .append(text("  Location: ", SourbyCraftColors.LABEL))
                        .append(text("unavailable", SourbyCraftColors.DIM))
                        .append(text("  (proxy-stripped IP, RFC1918, or local geo db unavailable)", SourbyCraftColors.DIM))
                        .build());
                }
            });
        });
        return true;
    }
}
