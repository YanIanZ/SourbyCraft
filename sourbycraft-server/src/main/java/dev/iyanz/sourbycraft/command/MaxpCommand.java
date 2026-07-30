package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.maxplayers.MaxPlayersConfig;
import dev.iyanz.sourbycraft.util.BarUtil;
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
 * ({@code sourbycraft.max-players} in
 * {@code sourbycraft_config/sourbycraft_global_config.toml}) so it survives a
 * restart — {@link MaxPlayersConfig#applyAtBoot()} re-applies the persisted
 * value at every boot so it wins over {@code server.properties}.
 *
 * <p>Console-runnable (extends {@link Command}, no {@code Player} assumption).
 * Registered with the {@code "sourbycraft"} fallback prefix so it is reachable
 * as {@code /maxp} and {@code /sourbycraft:maxp}. Permission
 * {@code sourbycraft.command.maxp} (op default).
 */
public class MaxpCommand extends Command {

    private static final String DIVIDER = BarUtil.FILLED.repeat(BarUtil.DEFAULT_WIDTH);

    // Sane upper bound: Bukkit stores max-players as an int; a slot count in the
    // hundreds of thousands is nonsensical and almost certainly a typo. Cap at
    // 100k so a fat-fingered value can't set an absurd server list count.
    private static final int MAX_ALLOWED = 100_000;

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

        int previous = Bukkit.getServer().getMaxPlayers();
        // 1) live change
        Bukkit.getServer().setMaxPlayers(n);
        // 2) persist to the unified SourbyCraft config so it survives restart.
        boolean persisted = MaxPlayersConfig.persist(n);

        s.sendMessage(text(DIVIDER, SourbyCraftColors.PRIMARY));
        s.sendMessage(text()
            .append(text(BarUtil.FILLED + " ", SourbyCraftColors.PRIMARY))
            .append(text("Max players ", SourbyCraftColors.HEADER))
            .append(text(previous + " -> " + n, SourbyCraftColors.VALUE))
            .build());
        if (persisted) {
            s.sendMessage(text("  persisted (sourbycraft.max-players) — survives restart", SourbyCraftColors.SUCCESS));
        } else {
            s.sendMessage(text("  applied live, but could NOT persist (config unavailable) — will reset on restart",
                SourbyCraftColors.WARNING));
        }
        s.sendMessage(text(DIVIDER, SourbyCraftColors.DIM));
        return true;
    }

    private static void showCurrent(CommandSender s) {
        int max = Bukkit.getServer().getMaxPlayers();
        int online = Bukkit.getOnlinePlayers().size();
        double pct = max > 0 ? Math.min(100.0, online * 100.0 / max) : 0.0;

        s.sendMessage(text(DIVIDER, SourbyCraftColors.PRIMARY));
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
        s.sendMessage(text(DIVIDER, SourbyCraftColors.DIM));
    }
}
