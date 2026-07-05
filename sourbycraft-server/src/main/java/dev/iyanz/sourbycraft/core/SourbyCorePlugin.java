package dev.iyanz.sourbycraft.core;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * SourbyCraft 26.2 survival host plugin.
 *
 * <p>Replaces the 26.1.2 SWM-based SWPlugin as the bundled paper-plugin main.
 * Hosts the MT1 module lifecycle and the ML1 native mod loader; carries NO
 * Slime World Manager (26.2 is the vanilla-region survival line). Worlds use
 * vanilla region storage.</p>
 */
public final class SourbyCorePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        // Emoji shortcode chat translator (runtime-gated via emoji.shortcodes.enabled).
        Bukkit.getPluginManager().registerEvents(new dev.iyanz.sourbycraft.chat.EmojiChatListener(), this);

        // MT1: centralized per-world cleanup — one listener evicts all PerWorldHolder maps.
        PerWorldHolder.registerCleanup(this);

        // MT1: enroll first-party features as isolated modules. clear() guards same-classloader reloads.
        ModuleRegistry.clear();
        ModuleRegistry.add("SignSanitizer", p -> dev.iyanz.sourbycraft.chat.SignSanitizer.register(p));
        ModuleRegistry.add("EntityStacker", p -> dev.iyanz.sourbycraft.wildstacker.EntityStacker.register(p));
        ModuleRegistry.add("OreReveal", p -> dev.iyanz.sourbycraft.antixray.OreReveal.register(p));
        ModuleRegistry.add("ConfigBridge", p -> dev.iyanz.sourbycraft.perf.ConfigBridge.register(p));
        ModuleRegistry.add("LagLimits", p -> dev.iyanz.sourbycraft.perf.LagLimits.register(p));
        ModuleRegistry.add("OwnerProtection", p -> dev.iyanz.sourbycraft.perf.OwnerProtection.register(p));
        ModuleRegistry.add("ViewThrottle", p -> dev.iyanz.sourbycraft.perf.ViewThrottle.register(p));
        if (SourbyCraftConfig.ymlBool("branding.motd-suffix", false)) {
            ModuleRegistry.add("motd-suffix", p -> Bukkit.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
                @org.bukkit.event.EventHandler
                public void onPing(com.destroystokyo.paper.event.server.PaperServerListPingEvent event) {
                    event.motd(event.motd().append(
                        net.kyori.adventure.text.Component.text(" | SourbyCraft",
                            net.kyori.adventure.text.format.NamedTextColor.GRAY)));
                }
            }, p));
        }

        // ML1: enroll native mods (loaded by ModLoader.bootstrap at DedicatedServer.initServer)
        // AFTER first-party adds and BEFORE enableAll, so they survive the clear() above.
        dev.iyanz.sourbycraft.mod.ModLoader.enrollInto();
        ModuleRegistry.enableAll(this);
    }

    @Override
    public void onDisable() {
        ModuleRegistry.disableAll();
    }
}
