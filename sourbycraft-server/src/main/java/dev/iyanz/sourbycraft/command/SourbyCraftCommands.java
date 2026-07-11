package dev.iyanz.sourbycraft.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;

import java.util.Locale;
import java.util.Map;

/**
 * Registers SourbyCraft's custom console commands into the server command map
 * with a "sourbycraft" fallback prefix, shadowing Paper's built-ins.
 *
 * <p>Ported from the Paper tag {@code paper-26.2-pre-folia}, where the same
 * block lived in a paper-server patch to {@code CraftServer}. On the Folia base
 * this runs from Luminol's post-config command hook
 * ({@link me.earthme.luminol.commands.CommandRegister#register()}, invoked by
 * {@code ConfigManager#loadConfigFiles} inside {@code DedicatedServer#initServer}).
 * At that point the {@code CraftServer} and its {@code SimpleCommandMap} already
 * exist, and this precedes {@code syncCommands()}, so the entries registered here
 * are merged into the Brigadier command tree exposed to players + console.
 *
 * <p>Paper claims the bare names /ping, /plugins, /version via its Brigadier
 * command tree, so a plain {@code register("", ...)} gets shadowed. We drop any
 * pre-existing SimpleCommandMap entry first, then register with the
 * "sourbycraft" fallback prefix, so {@code /sourbycraft:<name>} always reaches us
 * (and the bare {@code /<name>} lands on us too whenever Paper has not already
 * claimed the slot). SWM / tpsbar / rambar are intentionally omitted — the bars
 * are handled by Luminol and SWM is gone on this Folia line. {@code /tps} is
 * registered here as a hex TPS panel; {@code /spark} is registered as a
 * status/entry shim (full spark is provided by dropping the spark plugin jar,
 * and this shim never shadows a real spark plugin's {@code /spark}).
 */
public final class SourbyCraftCommands {

    private static volatile boolean registered = false;

    public static synchronized void registerAll() {
        if (registered) return;

        CommandMap commandMap = Bukkit.getServer().getCommandMap();
        if (commandMap == null) {
            org.slf4j.LoggerFactory.getLogger("SourbyCraft")
                .warn("Command map not available; SourbyCraft commands not registered.");
            return;
        }

        // Drop any pre-existing SimpleCommandMap entries so our fallback registration
        // is not shadowed by an earlier bukkit/vanilla entry under the same name.
        String[] names = {"ping", "sys", "plugins", "speedtest", "sparkview", "ver", "perf", "maxp", "tps"};
        Map<String, Command> known = commandMap.getKnownCommands();
        for (String n : names) {
            String lower = n.toLowerCase(Locale.ROOT);
            known.remove(lower);
            known.remove("bukkit:" + lower);
        }

        commandMap.register("sourbycraft", new PingCommand("ping"));
        commandMap.register("sourbycraft", new SysCommand("sys"));
        commandMap.register("sourbycraft", new PluginsCommand("plugins"));
        commandMap.register("sourbycraft", new SpeedtestCommand("speedtest"));
        commandMap.register("sourbycraft", new SparkviewCommand("sparkview"));
        commandMap.register("sourbycraft", new VerCommand("ver"));
        commandMap.register("sourbycraft", new PerfCommand("perf"));
        commandMap.register("sourbycraft", new MaxpCommand("maxp"));
        commandMap.register("sourbycraft", new TpsCommand("tps"));

        // /spark: only claim the bare name when no real spark plugin has taken it. The
        // standalone spark plugin (dropped into plugins/) is honoured — isPluginPreferred()
        // is true — and registers its own /spark; in that case we register ONLY the
        // sourbycraft:spark alias so /spark still reaches the real profiler and we never
        // shadow it. When no spark plugin is present, our shim owns the bare /spark and
        // points operators at how to enable full spark.
        int commandCount = 9;
        Command existingSpark = commandMap.getCommand("spark");
        boolean realSpark = existingSpark != null && !(existingSpark instanceof SparkCommand);
        if (!realSpark) {
            commandMap.register("sourbycraft", new SparkCommand("spark"));
            commandCount = 10;
        } else {
            org.slf4j.LoggerFactory.getLogger("SourbyCraft").info(
                "A spark plugin already owns /spark — leaving it in place; use /sparkview for the "
                + "SourbyCraft quick view.");
        }

        registered = true;
        org.slf4j.LoggerFactory.getLogger("SourbyCraft").info(
            "Registered " + commandCount + " SourbyCraft commands (fallback prefix 'sourbycraft'). "
            + "Use /sourbycraft:<name> if a name collides with Paper's built-ins "
            + "(/ping, /version, /plugins).");
    }

    private SourbyCraftCommands() {}
}
