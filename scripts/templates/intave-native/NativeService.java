package dev.yanianz.intave.integration;

import dev.yanianz.intave.NativeIntave;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginBase;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import java.util.logging.Logger;

/** Legacy API owner facade, never scanned, loaded, enabled or listed by a plugin manager. */
public abstract class NativeService extends PluginBase {
    private final Logger nativeLogger = Logger.getLogger("Intave Native");
    private final File directory = new File("sourbycraft_config/intave");
    private final YamlConfiguration nativeConfig = new YamlConfiguration();
    private final PluginDescriptionFile description;
    private final NativeCommand command = new NativeCommand();
    private final List<Permission> permissions = new ArrayList<>();
    private boolean naggable;
    private volatile boolean enabled = true;

    protected NativeService() {
        try {
            description = new PluginDescriptionFile(new StringReader(
                "name: Intave\nversion: private-native-c67af7f4\n"
                + "main: dev.yanianz.intave.IntaveEngine\napi-version: '1.13'\nfolia-supported: true\n"
                + "authors: [DarkAndBlue, Jpx3, vento, vxcus, lennoxlotl, NotLucky, Trattue]\n"));
        } catch (org.bukkit.plugin.InvalidDescriptionException failure) {
            throw new IllegalStateException(failure);
        }
    }

    public void registerNativeCommand() {
        for (String suffix : List.of("trust.green", "command", "command.notify", "command.verbose",
                "command.combatmodifiers", "command.cps", "command.check", "command.cloud",
                "command.diagnostics.performance", "command.diagnostics.statistics")) {
            registerPermission(suffix, PermissionDefault.OP, Map.of());
        }
        for (String suffix : List.of("bypass", "trust.yellow", "trust.orange", "trust.red", "trust.darkred",
                "command.proxy", "command.noupdate", "command.internals.delay", "command.internals.rejoinblock",
                "command.internals.sendnotify", "command.internals.collectivekick", "command.internals.bot")) {
            registerPermission(suffix, PermissionDefault.FALSE, Map.of());
        }
        registerPermission("command.diagnostics", PermissionDefault.OP, Map.of(
            "intave.command.diagnostics.performance", true, "intave.command.diagnostics.statistics", true));
        registerPermission("command.internals", PermissionDefault.FALSE, Map.of(
            "intave.command.internals.delay", true, "intave.command.internals.rejoinblock", true,
            "intave.command.internals.sendnotify", true, "intave.command.internals.collectivekick", true,
            "intave.command.internals.bot", true));
        Bukkit.getCommandMap().register("intave", this.command);
    }
    private void registerPermission(String suffix, PermissionDefault defaults, Map<String, Boolean> children) {
        String name = "intave." + suffix;
        if (Bukkit.getPluginManager().getPermission(name) != null) return;
        Permission permission = new Permission(name, "Native Intave access", defaults, children);
        Bukkit.getPluginManager().addPermission(permission);
        permissions.add(permission);
    }
    public void closeNativeOwner() {
        enabled = false;
        command.unregister(Bukkit.getCommandMap());
        Bukkit.getCommandMap().getKnownCommands().values().removeIf(value -> value == command);
        org.bukkit.event.HandlerList.unregisterAll(this);
        Bukkit.getGlobalRegionScheduler().cancelTasks(this);
        Bukkit.getAsyncScheduler().cancelTasks(this);
        permissions.forEach(permission -> {
            if (Bukkit.getPluginManager().getPermission(permission.getName()) == permission) {
                Bukkit.getPluginManager().removePermission(permission);
            }
        });
        permissions.clear();
    }
    public void stopNativeAdmission() { enabled = false; }
    public NativeCommand getCommand(String name) {
        if (!"intave".equals(name)) throw new IllegalArgumentException(name);
        return command;
    }
    @Override public File getDataFolder() { return directory; }
    @Override public PluginDescriptionFile getDescription() { return description; }
    @Override public PluginMeta getPluginMeta() { return description; }
    @Override public FileConfiguration getConfig() { return nativeConfig; }
    @Override public InputStream getResource(String name) { return getClass().getClassLoader().getResourceAsStream(name); }
    @Override public void saveConfig() {
        try { Files.createDirectories(directory.toPath()); nativeConfig.save(new File(directory, "config.yml")); }
        catch (Exception failure) { throw new IllegalStateException("Could not save native Intave config", failure); }
    }
    @Override public void saveDefaultConfig() { /* Intave's ConfigurationService owns its defaults. */ }
    @Override public void reloadConfig() {
        try { nativeConfig.load(new File(directory, "config.yml")); }
        catch (Exception failure) { throw new IllegalStateException("Could not load native Intave config", failure); }
    }
    @Override public void saveResource(String name, boolean replace) {
        throw new UnsupportedOperationException("Native resources are managed by ConfigurationService");
    }
    @Override public PluginLoader getPluginLoader() { throw new UnsupportedOperationException("Native engine has no plugin loader"); }
    @Override public Server getServer() { return Bukkit.getServer(); }
    @Override public boolean isEnabled() { return enabled; }
    @Override public void onLoad() {}
    @Override public void onEnable() {}
    @Override public void onDisable() {}
    @Override public boolean isNaggable() { return naggable; }
    @Override public void setNaggable(boolean value) { naggable = value; }
    @Override public Logger getLogger() { return nativeLogger; }
    @Override public ChunkGenerator getDefaultWorldGenerator(String world, String id) { return null; }
    @Override public BiomeProvider getDefaultBiomeProvider(String world, String id) { return null; }
    @Override public LifecycleEventManager<Plugin> getLifecycleManager() {
        throw new UnsupportedOperationException("Native engine does not register plugin lifecycle handlers");
    }
    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) { return false; }
    @Override public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) { return List.of(); }

    public static final class NativeCommand extends Command {
        private CommandExecutor executor;
        NativeCommand() {
            super("intave", "Native Intave diagnostics and commands", "/intave native", List.of("iac"));
            setPermission("intave.command");
        }
        public void setExecutor(CommandExecutor executor) { this.executor = executor; }
        @Override public boolean execute(CommandSender sender, String label, String[] args) {
            if (!testPermission(sender)) return true;
            if (args.length > 0 && "native".equalsIgnoreCase(args[0])) {
                var status = NativeIntave.status();
                sender.sendMessage("Intave native: " + status.state() + " — " + status.detail());
                return true;
            }
            if (!NativeIntave.status().active()) {
                sender.sendMessage("Intave native is " + NativeIntave.status().state() + "; use /intave-native status");
                return true;
            }
            return executor != null && executor.onCommand(sender, this, label, args);
        }
    }
}
