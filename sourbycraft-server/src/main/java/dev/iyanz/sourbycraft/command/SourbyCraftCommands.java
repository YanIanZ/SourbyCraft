package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.bootstrap.MinecraftInternalPlugin;
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
 * <p><b>Single-phase claim (Canvas boot order).</b> {@link #registerAll()} runs from
 * {@link dev.iyanz.sourbycraft.core.SourbyCraftBootstrap}, called from a hand-authored
 * {@code minecraft-patch} right AFTER {@code PaperCommands.registerCommands(this)} in
 * {@code DedicatedServer#initServer} — so we claim the bare names AFTER Paper's built-ins are
 * registered and nothing re-registers over us afterwards. (The archived Folia build's two-phase
 * {@code reclaimBareNames()} dance existed only because that boot hook ran BEFORE Paper's command
 * registration; not needed here.) Net result: {@code /tps} is ours, {@code /minecraft:tps} /
 * {@code /paper:tps} still reach Paper's.
 */
public final class SourbyCraftCommands {

    private static volatile boolean registered = false;
    private static final Map<String, Command> OURS = new LinkedHashMap<>();

    /**
     * Builds every SourbyCraft styled command, drops any foreign command-map entry for the same
     * bare/namespaced name, claims the bare name in the command map, and registers the HUD
     * quit-listener. Idempotent — a second call is a no-op.
     */
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
        OURS.put("ver", new VerCommand("ver"));
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
            known.put(name, e.getValue());                    // claim the bare name (runs AFTER Paper's own registration)
        }

        try {
            Bukkit.getPluginManager().registerEvents(
                new dev.iyanz.sourbycraft.hud.HudBars.QuitListener(),
                MinecraftInternalPlugin.INSTANCE);
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("SourbyCraft").warn("HudBars quit-listener registration failed", t);
        }

        registered = true;
        org.slf4j.LoggerFactory.getLogger("SourbyCraft").info(
            "Registered " + OURS.size() + " SourbyCraft commands (bare names; /minecraft:<name> or "
            + "/paper:<name> still reach the built-ins). Native /spark is provided by Canvas.");
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
