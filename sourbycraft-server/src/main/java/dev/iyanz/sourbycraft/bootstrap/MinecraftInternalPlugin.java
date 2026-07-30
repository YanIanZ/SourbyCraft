/*
 * Originally part of Leaves (https://github.com/LeavesMC/Leaves), LGPL-3.0.
 *
 * SourbyCraft — kept as the internal/synthetic Plugin handle used to own our own
 * scheduled tasks + event listeners (Bukkit/Paper scheduler APIs require a Plugin owner).
 * The rest of the vendored Leaves tree (command framework, lithium hopper utils, plugin
 * provider) was dropped on the Canvas re-platform (feat/canvas-engine) — Canvas provides
 * its own engine and none of SourbyCraft's utility layer needs Leaves' command tree.
 * Relocated from org.leavesmc.leaves.plugin to dev.iyanz.sourbycraft.bootstrap so no
 * "leftover single-file package" remains after that removal.
 */

package dev.iyanz.sourbycraft.bootstrap;

import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.InputStream;
import java.util.List;

/**
 * The always-enabled, synthetic {@link Plugin} handle SourbyCraft's own actuators register
 * against — server-internal code (a {@code minecraft-patch} boot hook, not a loaded plugin) has no
 * real plugin instance to hand to the Bukkit/Paper scheduler and event APIs, which require a
 * {@link Plugin} owner. {@link #INSTANCE} is that owner for every SourbyCraft-registered listener
 * and scheduled task ({@code HudBars}, {@code SourbyJoinLeaveListener}, {@code MaxPlayersBypass},
 * the update notifier, ...).
 *
 * <p>Only identity/state is real: {@link #getDescription()}, {@link #getPluginMeta()},
 * {@link #getLogger()}, {@link #getServer()} and the {@link #isEnabled()}/{@link #setEnabled(boolean)}
 * pair. Everything else a full plugin lifecycle would need — config/resource I/O, world
 * generation, command dispatch, {@code onLoad}/{@code onEnable}/{@code onDisable} — is
 * intentionally unimplemented ({@code throw new UnsupportedOperationException}) because this
 * handle is never loaded through {@code PluginManager}; it exists purely to be passed as the
 * {@code owner} argument of scheduler/event-registration calls.
 */
public class MinecraftInternalPlugin extends PluginBase {

    /** The single shared instance every SourbyCraft utility-layer listener/task registers against. */
    public static final MinecraftInternalPlugin INSTANCE = new MinecraftInternalPlugin();

    private boolean enabled = true;

    private final PluginDescriptionFile pdf;
    private final PluginLogger logger;

    /** Builds the synthetic "Minecraft" plugin description backing {@link #INSTANCE}. */
    public MinecraftInternalPlugin() {
        String pluginName = "Minecraft";
        pdf = new PluginDescriptionFile(pluginName, "1.0", "nms");
        logger = new PluginLogger(this);
    }

    /** Sets the enabled flag returned by {@link #isEnabled()}. No lifecycle side effects. */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** Returns the synthetic {@code "Minecraft"} plugin description backing this handle. */
    @Override
    public @NotNull PluginDescriptionFile getDescription() {
        return pdf;
    }

    /** Returns the same synthetic description, as the {@link PluginMeta} view Paper's APIs expect. */
    @Override
    public @NotNull PluginMeta getPluginMeta() {
        return pdf;
    }

    /** Returns this handle's {@link PluginLogger} (used by the utility-layer listeners it owns). */
    @Override
    public @NotNull PluginLogger getLogger() {
        return logger;
    }

    /** Returns the live {@link Bukkit#getServer()} instance. */
    @Override
    public @NotNull Server getServer() {
        return Bukkit.getServer();
    }

    /** True unless {@link #setEnabled(boolean)} was called with {@code false}; defaults to {@code true}. */
    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /** Not supported — this handle has no real {@link PluginLoader}; it is never loaded by one. */
    @Override
    public @NotNull PluginLoader getPluginLoader() {
        throw new UnsupportedOperationException("Not supported.");
    }

    /** Not supported — this handle owns no data folder. */
    @Override
    public @NotNull File getDataFolder() {
        throw new UnsupportedOperationException("Not supported.");
    }

    /** Not supported — this handle has no plugin config; SourbyCraft settings live in {@code SourbyCraftConfig}. */
    @Override
    public @NotNull FileConfiguration getConfig() {
        throw new UnsupportedOperationException("Not supported.");
    }

    /** Not supported — this handle bundles no plugin jar resources. */
    @Override
    public InputStream getResource(@NotNull String filename) {
        throw new UnsupportedOperationException("Not supported.");
    }

    /** Not supported — see {@link #getConfig()}. */
    @Override
    public void saveConfig() {
        throw new UnsupportedOperationException("Not supported.");
    }

    /** Not supported — see {@link #getConfig()}. */
    @Override
    public void saveDefaultConfig() {
        throw new UnsupportedOperationException("Not supported.");
    }

    /** Not supported — see {@link #getResource(String)}. */
    @Override
    public void saveResource(@NotNull String resourcePath, boolean replace) {
        throw new UnsupportedOperationException("Not supported.");
    }

    /** Not supported — see {@link #getConfig()}. */
    @Override
    public void reloadConfig() {
        throw new UnsupportedOperationException("Not supported.");
    }

    /** Not supported — this handle has no plugin lifecycle; it is never disabled by a manager. */
    @Override
    public void onDisable() {
        throw new UnsupportedOperationException("Not supported.");
    }

    /** Not supported — see {@link #onDisable()}. */
    @Override
    public void onLoad() {
        throw new UnsupportedOperationException("Not supported.");
    }

    /** Not supported — see {@link #onDisable()}. */
    @Override
    public void onEnable() {
        throw new UnsupportedOperationException("Not supported.");
    }

    /** Not supported — this handle is never shown a "slow plugin" nag. */
    @Override
    public boolean isNaggable() {
        throw new UnsupportedOperationException("Not supported.");
    }

    /** Not supported — see {@link #isNaggable()}. */
    @Override
    public void setNaggable(boolean canNag) {
        throw new UnsupportedOperationException("Not supported.");
    }

    /** Not supported — this handle provides no custom world generator. */
    @Override
    public ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, String id) {
        throw new UnsupportedOperationException("Not supported.");
    }

    /** Not supported — see {@link #getDefaultWorldGenerator(String, String)}. */
    @Override
    public @Nullable BiomeProvider getDefaultBiomeProvider(@NotNull String worldName, @Nullable String id) {
        throw new UnsupportedOperationException("Not supported.");
    }

    /** Not supported — this handle registers no commands of its own (SourbyCraft's are registered directly via the command map). */
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        throw new UnsupportedOperationException("Not supported.");
    }

    /** Not supported — see {@link #onCommand(CommandSender, Command, String, String[])}. */
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        throw new UnsupportedOperationException("Not supported.");
    }

    /** Not supported — this handle has no {@code paper-plugin.yml} lifecycle events to manage. */
    @Override
    public @NotNull LifecycleEventManager<@NotNull Plugin> getLifecycleManager() {
        throw new UnsupportedOperationException("Not supported.");
    }
}
