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


    private final java.util.concurrent.Executor executor;
    private final java.util.function.Function<Player, String> geo;
    private final java.util.function.BiConsumer<CommandSender, Runnable> reply;

    public PingCommand(String name) {
        this(name, VirtualExecutor::run, GeoUtil::lookup, SourbyReply::run);
    }

    /** Test seam: the thread the geo lookup runs on, the lookup itself and the reply hop. */
    PingCommand(String name, java.util.concurrent.Executor executor,
                java.util.function.Function<Player, String> geo,
                java.util.function.BiConsumer<CommandSender, Runnable> reply) {
        super(name);
        this.executor = executor;
        this.geo = geo;
        this.reply = reply;
        this.description = "Player latency";
        this.usageMessage = "/ping [player]";
    }

    /**
     * Renders the target's latency as a coloured bar, then resolves + appends their approximate
     * location asynchronously via {@link GeoUtil#lookup}. Targets self by default; targeting
     * another player requires {@code sourbycraft.command.ping.other}.
     */
    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        boolean other = args.length > 0 && !args[0].equalsIgnoreCase(s.getName());
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

        s.sendMessage(UiPanel.header("Connection / " + t.getName()));
        s.sendMessage(text("  Latency: ", SourbyCraftColors.LABEL)
            .append(text(BarUtil.bar(pct, BarUtil.DEFAULT_WIDTH) + "  " + ping + "ms", pc)));
        s.sendMessage(UiPanel.hint("Gauge is full at 0ms and empty at 500ms or more."));

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

        try {
            this.executor.execute(() -> {
                String geo;
                try {
                    geo = this.geo.apply(t);
                } catch (Throwable err) {
                    geo = null;
                }
                final String geoFinal = geo;
                // Folia-safe reply hop. MinecraftServer.execute() throws UnsupportedOperationException on
                // Folia (no global main thread), so bounce the reply onto the sender's own scheduler: a
                // player hops to its region scheduler; a console send is thread-safe so it goes direct.
                this.reply.accept(s, () -> sendLocation(s, geoFinal));
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // The I/O executor is stopped or at capacity (VirtualExecutor throws
            // RejectedExecutionException). We are still on the sender's own thread, so answer
            // directly instead of leaving "looking up..." as the last line and throwing at the dispatcher.
            sendLocation(s, null);
        }
        return true;
    }

    private static void sendLocation(CommandSender s, String geo) {
        if (geo != null && !geo.isEmpty()) {
            s.sendMessage(text()
                .append(text("  Location: ", SourbyCraftColors.LABEL))
                .append(text(geo, SourbyCraftColors.VALUE))
                .build());
        } else {
            s.sendMessage(text()
                .append(text("  Location: ", SourbyCraftColors.LABEL))
                .append(text("unavailable", SourbyCraftColors.DIM))
                .append(text("  (proxy-stripped IP, RFC1918, or local geo db unavailable)", SourbyCraftColors.DIM))
                .build());
        }
    }
}
