package dev.iyanz.sourbycraft.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Registers SourbyCraft's custom commands into the server command map so the BARE names
 * ({@code /tps}, {@code /ping}, {@code /ver}, ...) resolve to the SourbyCraft styled versions —
 * not Paper's built-ins.
 *
 * <p><b>Two-phase reclaim (Folia boot order).</b> {@link #registerAll()} runs from the Luminol
 * post-config hook (DedicatedServer#initServer, via ConfigManager#loadConfigFiles) which precedes
 * {@code PaperCommands.registerCommands(this)} — so Paper re-registers {@code tps}/{@code mspt}/
 * {@code ver}/{@code pl}/... into the command map AFTER us and steals the bare names back. An NMS
 * patch therefore calls {@link #reclaimBareNames()} right after Paper's registration, which drops
 * every non-SourbyCraft entry for our names and re-points the bare label at our command. Net
 * result: {@code /tps} is ours, {@code /minecraft:tps} / {@code /paper:tps} still reach Paper's.
 */
public final class SourbyCraftCommands {

    private static volatile boolean registered = false;
    /** Our command name -> Command, kept so reclaimBareNames() can re-point the bare label. */
    private static final Map<String, Command> OURS = new LinkedHashMap<>();

    public static synchronized void registerAll() {
        if (registered) return;
        CommandMap commandMap = Bukkit.getServer().getCommandMap();
        if (commandMap == null) {
            org.slf4j.LoggerFactory.getLogger("SourbyCraft")
                .warn("Command map not available; SourbyCraft commands not registered.");
            return;
        }

        OURS.clear();
        OURS.put("ping", new PingCommand("ping"));
        OURS.put("sys", new SysCommand("sys"));
        OURS.put("plugins", new PluginsCommand("plugins"));
        OURS.put("speedtest", new SpeedtestCommand("speedtest"));
        OURS.put("sparkview", new SparkviewCommand("sparkview"));
        OURS.put("ver", new VerCommand("ver"));
        OURS.put("perf", new PerfCommand("perf"));
        OURS.put("maxp", new MaxpCommand("maxp"));
        OURS.put("tps", new TpsCommand("tps"));
        OURS.put("mspt", new MsptCommand("mspt"));
        OURS.put("tpsbar", new HudBarCommand("tpsbar", true));
        OURS.put("rambar", new HudBarCommand("rambar", false));
        OURS.put("update", new UpdateCommand("update"));
        OURS.put("sourbycraft", new SourbyCraftCommand("sourbycraft"));

        final Map<String, Command> known = commandMap.getKnownCommands();
        for (Map.Entry<String, Command> e : OURS.entrySet()) {
            final String name = e.getKey();
            dropForeign(known, name);
            commandMap.register("sourbycraft", e.getValue()); // ensures a sourbycraft:<name> alias always exists
            known.put(name, e.getValue());                    // claim the bare name now (Paper may re-steal it -> reclaimBareNames)
        }

        try {
            Bukkit.getPluginManager().registerEvents(
                new dev.iyanz.sourbycraft.hud.HudBars.QuitListener(),
                org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE);
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("SourbyCraft").warn("HudBars quit-listener registration failed", t);
        }

        // Apply the raised world-size limit (66M border) to existing worlds on load.
        dev.iyanz.sourbycraft.core.WorldSizeExpander.register(org.leavesmc.leaves.plugin.MinecraftInternalPlugin.INSTANCE);

        registered = true;
        org.slf4j.LoggerFactory.getLogger("SourbyCraft").info(
            "Registered " + OURS.size() + " SourbyCraft commands (bare names; /minecraft:<name> or "
            + "/paper:<name> still reach the built-ins). Native /spark is provided by the bundled profiler.");
    }

    /**
     * Called from the NMS patch AFTER {@code PaperCommands.registerCommands} — reclaim the bare
     * names Paper just stole ({@code tps}, {@code mspt}, {@code ver}, {@code pl}->{@code plugins}, ...).
     */
    public static synchronized void reclaimBareNames() {
        if (!registered) return;
        CommandMap commandMap = Bukkit.getServer().getCommandMap();
        if (commandMap == null) return;
        final Map<String, Command> known = commandMap.getKnownCommands();
        int reclaimed = 0;
        for (Map.Entry<String, Command> e : OURS.entrySet()) {
            final String name = e.getKey();
            final Command current = known.get(name);
            if (current != e.getValue()) {
                dropForeign(known, name);
                known.put(name, e.getValue());
                reclaimed++;
            }
        }
        if (reclaimed > 0) {
            org.slf4j.LoggerFactory.getLogger("SourbyCraft")
                .info("Reclaimed " + reclaimed + " bare command name(s) from the built-ins.");
        }
    }

    /** Remove every command-map entry for {@code name} that is NOT one of ours (bare + all namespaces). */
    private static void dropForeign(Map<String, Command> known, String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        known.remove(lower);
        for (String ns : new String[]{"bukkit", "minecraft", "paper", "spigot"}) {
            known.remove(ns + ":" + lower);
        }
    }

    private SourbyCraftCommands() {}
}
