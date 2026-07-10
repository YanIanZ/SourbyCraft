package me.earthme.luminol.commands;

import me.earthme.luminol.commands.bar.BarCommand;

public class CommandRegister {
    /**
     * Register commands after config loading
     * This method is called after system configuration is fully loaded,
     * used to register commands that depend on complete configuration
     */
    public static void register() {
        new BarCommand().register();
        // SourbyCraft - register custom console commands (shadowing fix). Runs from the
        // Luminol post-config hook (ConfigManager#loadConfigFiles, invoked in
        // DedicatedServer#initServer) — the CraftServer + its SimpleCommandMap already
        // exist at this point, and this precedes syncCommands() so our entries get merged
        // into the Brigadier tree. See SourbyCraftCommands for the full rationale.
        dev.iyanz.sourbycraft.command.SourbyCraftCommands.registerAll();

        // SourbyCraft - branded startup banner + advisors (the "logs pakai hexcolor" ask).
        // Ported from the Paper tag paper-26.2-pre-folia, where these lived in an NMS patch
        // to MinecraftServer#runServer. On the Folia base we emit them from this authored
        // post-config hook instead — Paper's GlobalConfiguration is loaded by now (needed by
        // HardeningAdvisor) and we avoid churning the net/minecraft patch series.
        dev.iyanz.sourbycraft.brand.StartupBanner.printOnce();
    }
}
