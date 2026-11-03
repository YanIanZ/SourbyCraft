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
 * <p><b>Single-phase claim (server boot order).</b> {@link #registerAll()} runs from
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
     * bare name, claims the bare name in the command map, and registers the HUD
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
        OURS.put("spec", new SpecCommand("spec"));
        OURS.put("plugins", new PluginsCommand("plugins"));
        OURS.put("speedtest", new SpeedtestCommand("speedtest"));
        OURS.put("ver", new VerCommand("ver"));
        OURS.put("maxp", new MaxpCommand("maxp"));
        OURS.put("tps", new TpsCommand("tps"));
        OURS.put("mspt", new MsptCommand("mspt"));
        OURS.put("ram", new PerfCommand("ram"));
        OURS.put("perf", new PerfCommand("perf"));
        OURS.put("awf", new AwfCommand("awf"));
        OURS.put("tpsbar", new HudBarCommand("tpsbar", true));
        OURS.put("rambar", new HudBarCommand("rambar", false));
        OURS.put("perfbar", new PerfBarCommand());
        OURS.put("update", new UpdateCommand("update"));
        OURS.put("sourbycraft", new SourbyCraftCommand("sourbycraft"));
        OURS.put("aurora", new SourbyCraftCommand("aurora"));

        for (Command command : OURS.values()) claim(commandMap, command);
        registerCoop(commandMap);

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
            + "/paper:<name> still reach the built-ins). Native /spark is provided by the server integration."
            + " /coop: " + (dev.iyanz.sourbycraft.awf.multiprofile.ProfileRuntime.settingsOrDisable().coopEnabled()
                ? "/sourbycraft:coop now; the bare name is claimed after the plugins enable when no plugin holds it."
                : "not registered (co-op disabled)."));
    }

    /**
     * {@code /coop} exists only while co-op is enabled ({@code aurora.awf.multiprofile.coop.enabled}, RESTART_REQUIRED;
     * the runtime started in an earlier boot stage): with co-op off (the default) nothing is registered and the server
     * behaves as Build 51, so a plugin's {@code /coop} (a skyblock plugin's, say) is untouched. With co-op on, this
     * boot stage (which runs before plugins are enabled) registers {@code /sourbycraft:coop} only; the bare name is
     * claimed at {@code ServerLoadEvent(STARTUP)}, after every plugin enabled and registered its commands, and only
     * when no plugin holds it; otherwise an INFO names the holder and {@code /sourbycraft:coop} stays the way in. The
     * operator form {@code /awf multiprofile coop} is part of {@code /awf} either way.
     */
    private static void registerCoop(CommandMap commandMap) {
        if (!dev.iyanz.sourbycraft.awf.multiprofile.ProfileRuntime.settingsOrDisable().coopEnabled()) return;
        final CoopCommand coop = new CoopCommand("coop");
        final Map<String, Command> known = commandMap.getKnownCommands();
        // The namespaced entry only: commandMap.register() would take the free bare name now, ahead of the plugins.
        known.put("sourbycraft:coop", coop);
        coop.register(commandMap);
        registerDefaultTrue(CoopCommand.PERMISSION, "Use /coop (AWF MultiProfile co-op)");
        try {
            Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
                @org.bukkit.event.EventHandler
                public void onServerLoad(org.bukkit.event.server.ServerLoadEvent event) {
                    if (event.getType() != org.bukkit.event.server.ServerLoadEvent.LoadType.STARTUP) return;
                    claimBareCoop(commandMap, coop);
                }
            }, MinecraftInternalPlugin.INSTANCE);
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("SourbyCraft").warn("/coop stays /sourbycraft:coop: the server-load listener was not registered", t);
        }
    }

    /** After the plugins enabled: the bare {@code /coop} when it is free, else an INFO naming the holder. */
    static void claimBareCoop(CommandMap commandMap, CoopCommand coop) {
        final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger("SourbyCraft");
        final Map<String, Command> known = commandMap.getKnownCommands();
        final Command holder = known.get("coop");
        if (holder != null && holder != coop) {
            log.info("/coop is held by " + describe(holder) + "; SourbyCraft co-op is reachable as /sourbycraft:coop (and /awf multiprofile coop)");
            return;
        }
        known.put("coop", coop);
        known.putIfAbsent("sourbycraft:coop", coop);
        OURS.put("coop", coop);
        try {
            // The Brigadier tree the clients see is rebuilt from the command map (no player is online at STARTUP).
            ((org.bukkit.craftbukkit.CraftServer) Bukkit.getServer()).syncCommands();
        } catch (RuntimeException failed) {
            log.warn("/coop was registered but the client command tree was not resynced", failed);
        }
        log.info("Registered /coop (AWF MultiProfile co-op; the bare name was free after the plugins enabled)");
    }

    private static String describe(Command command) {
        if (command instanceof org.bukkit.command.PluginIdentifiableCommand plugin && plugin.getPlugin() != null) {
            return "plugin " + plugin.getPlugin().getName() + " (" + command.getClass().getName() + ")";
        }
        return command.getClass().getName();
    }

    /** Registers a permission every player has unless a permissions plugin removes it. */
    private static void registerDefaultTrue(String node, String description) {
        try {
            if (Bukkit.getPluginManager().getPermission(node) == null) {
                Bukkit.getPluginManager().addPermission(new org.bukkit.permissions.Permission(node, description,
                    org.bukkit.permissions.PermissionDefault.TRUE));
            }
        } catch (RuntimeException failed) {
            org.slf4j.LoggerFactory.getLogger("SourbyCraft").warn("Registering permission " + node + " failed", failed);
        }
    }

    /** Claim the bare name and aliases, preserving upstream namespaced commands. */
    static void claim(CommandMap commandMap, Command command) {
        Map<String, Command> known = commandMap.getKnownCommands();
        known.remove(command.getName().toLowerCase(Locale.ROOT));
        for (String alias : command.getAliases()) known.remove(alias.toLowerCase(Locale.ROOT));
        commandMap.register("sourbycraft", command);
        known.put(command.getName().toLowerCase(Locale.ROOT), command);
        for (String alias : command.getAliases()) {
            known.put(alias.toLowerCase(Locale.ROOT), command);
            known.put("sourbycraft:" + alias.toLowerCase(Locale.ROOT), command);
        }
    }

    private SourbyCraftCommands() {}
}
