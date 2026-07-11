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
 * registered here as a hex TPS panel. {@code /spark} is intentionally NOT
 * registered here: the bundled spark profiler is re-enabled (paper feature
 * patch 0004), so Paper's own {@code SparksFly} owns the real {@code /spark};
 * {@code /sparkview} remains the SourbyCraft one-shot quick view.
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

        // /spark is intentionally NOT registered here: the bundled spark profiler is
        // re-enabled on this Folia build (paper feature patch 0004), so Paper's own
        // io.papermc.paper.SparksFly registers the genuine /spark before plugins. We must
        // not shadow it. /sparkview remains the SourbyCraft one-shot quick view and reads
        // the same spark singleton.

        registered = true;
        org.slf4j.LoggerFactory.getLogger("SourbyCraft").info(
            "Registered 9 SourbyCraft commands (fallback prefix 'sourbycraft'). "
            + "Use /sourbycraft:<name> if a name collides with Paper's built-ins "
            + "(/ping, /version, /plugins). Native /spark is provided by the bundled profiler.");
    }

    private SourbyCraftCommands() {}
}
