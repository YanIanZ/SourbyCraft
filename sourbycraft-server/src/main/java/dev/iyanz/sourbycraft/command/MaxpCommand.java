package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.execution.region.RegionOwnership;
import dev.iyanz.sourbycraft.maxplayers.MaxPlayersConfig;
import dev.iyanz.sourbycraft.util.BarUtil;
import dev.iyanz.sourbycraft.util.VirtualExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import static net.kyori.adventure.text.Component.text;

/**
 * /maxp [n] — view or set the server's max-player slot count.
 *
 * <p>With no argument it renders the current max + online count in the same
 * divider/header style as the rest of the SourbyCraft command suite. With a
 * positive integer argument it {@link org.bukkit.Server#setMaxPlayers(int) sets
 * the live cap} AND persists the value into the unified SourbyCraft config
 * ({@code players.max-players} in
 * {@code sourbycraft_config/sourbycraft_global_config.yml}) so it survives a
 * restart — {@link MaxPlayersConfig#applyAtBoot()} re-applies the persisted
 * value at every boot so it wins over {@code server.properties}.
 *
 * <p>The config write is blocking file I/O. Run on a region or the global tick
 * thread (a player, command block or the console), it goes to the shared I/O
 * executor and the result is reported through {@link SourbyReply}; on any other
 * thread it runs inline. Whichever write runs last persists the newest count
 * any {@code /maxp} asked for (the live one), so when that write succeeds the
 * file does not end behind the live value; a failed write is reported.
 *
 * <p>Console-runnable (extends {@link Command}, no {@code Player} assumption).
 * Registered with the {@code "sourbycraft"} fallback prefix so it is reachable
 * as {@code /maxp} and {@code /sourbycraft:maxp}. Permission
 * {@code sourbycraft.command.maxp} (op default).
 */
public class MaxpCommand extends Command {


    // Sane upper bound: Bukkit stores max-players as an int; a slot count in the
    // hundreds of thousands is nonsensical and almost certainly a typo. Cap at
    // 100k so a fat-fingered value can't set an absurd server list count.
    private static final int MAX_ALLOWED = 100_000;

    /** The newest count a {@code /maxp} asked for; written by whichever persist runs last. */
    private static final AtomicInteger REQUESTED = new AtomicInteger();
    /** Sets the live count and {@link #REQUESTED} together, so the newest requested count is the live one. */
    private static final Object LIVE_LOCK = new Object();
    /** Orders persists, so a write of an older count can never land after a newer one. */
    private static final Object PERSIST_LOCK = new Object();

    public MaxpCommand(String name) {
        super(name);
        this.description = "View or set the server max-player slot count (persistent)";
        this.usageMessage = "/maxp [n]";
        this.setPermission("sourbycraft.command.maxp");
    }

    /**
     * With no argument, renders the current max/online readout. With a positive integer argument,
     * validates it against {@link #MAX_ALLOWED}, applies it live via
     * {@link org.bukkit.Server#setMaxPlayers(int)}, and persists it via
     * {@link MaxPlayersConfig#persist(int)} so it survives a restart.
     */
    @Override
    public boolean execute(CommandSender s, String alias, String[] args) {
        if (!testPermission(s)) return true;

        if (args.length == 0) {
            showCurrent(s);
            return true;
        }

        int n;
        try {
            n = Integer.parseInt(args[0].trim());
        } catch (NumberFormatException ex) {
            s.sendMessage(text("Not a number: ", SourbyCraftColors.DANGER)
                .append(text(args[0], SourbyCraftColors.VALUE)));
            s.sendMessage(text("Usage: /maxp [n]", SourbyCraftColors.DIM));
            return true;
        }

        if (n <= 0) {
            s.sendMessage(text("Max players must be positive (got " + n + ")", SourbyCraftColors.DANGER));
            return true;
        }
        if (n > MAX_ALLOWED) {
            s.sendMessage(text("Max players too large (max " + MAX_ALLOWED + ")", SourbyCraftColors.DANGER));
            return true;
        }

        final int previous;
        // 1) live change (in memory, no I/O), recorded as the count the next persist writes
        synchronized (LIVE_LOCK) {
            previous = Bukkit.getServer().getMaxPlayers();
            Bukkit.getServer().setMaxPlayers(n);
            REQUESTED.set(n);
        }
        // 2) persist to the unified SourbyCraft config so it survives restart; never file I/O on a tick thread.
        if (RegionOwnership.current().isTickThread()) {
            VirtualExecutor.supply(MaxpCommand::persistNewest)
                .exceptionally(failure -> 0)
                .thenAccept(persisted -> SourbyReply.run(s, () -> report(s, previous, n, persisted)));
        } else {
            report(s, previous, n, persistNewest());
        }
        return true;
    }

    /** Persists the newest requested count; returns it, or 0 when it could not be written. */
    private static int persistNewest() {
        synchronized (PERSIST_LOCK) {
            final int newest = REQUESTED.get();
            return MaxPlayersConfig.persist(newest) ? newest : 0;
        }
    }

    private static void report(final CommandSender s, final int previous, final int n, final int persisted) {
        s.sendMessage(UiPanel.header("Player slots"));
        s.sendMessage(text()
            .append(text(BarUtil.FILLED + " ", SourbyCraftColors.PRIMARY))
            .append(text("Max players ", SourbyCraftColors.HEADER))
            .append(text(previous + " -> " + n, SourbyCraftColors.VALUE))
            .build());
        if (persisted == n) {
            s.sendMessage(text("  persisted (" + MaxPlayersConfig.KEY + ") — survives restart", SourbyCraftColors.SUCCESS));
        } else if (persisted > 0) {
            s.sendMessage(text("  persisted (" + MaxPlayersConfig.KEY + ") as " + persisted
                + ", the count of a newer /maxp — survives restart", SourbyCraftColors.SUCCESS));
        } else {
            s.sendMessage(text("  applied live, but could NOT persist (config unavailable) — will reset on restart",
                SourbyCraftColors.WARNING));
        }
        s.sendMessage(UiPanel.footer());
    }

    private static void showCurrent(CommandSender s) {
        int max = Bukkit.getServer().getMaxPlayers();
        int online = Bukkit.getOnlinePlayers().size();
        double pct = max > 0 ? Math.min(100.0, online * 100.0 / max) : 0.0;

        s.sendMessage(UiPanel.header("Player slots"));
        s.sendMessage(text()
            .append(text(BarUtil.FILLED + " ", SourbyCraftColors.PRIMARY))
            .append(text("Max players", SourbyCraftColors.HEADER))
            .build());
        s.sendMessage(text()
            .append(text("  ", SourbyCraftColors.DIM))
            .append(text(BarUtil.bar(pct, BarUtil.DEFAULT_WIDTH), SourbyCraftColors.PRIMARY))
            .append(text("  " + online + " / " + max, SourbyCraftColors.VALUE))
            .build());
        s.sendMessage(text("  Set with /maxp <n> (persists across restarts)", SourbyCraftColors.DIM));
        s.sendMessage(UiPanel.footer());
    }
}
