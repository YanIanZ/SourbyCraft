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

        // SourbyCraft - perf-engine F2b: initialise the config registry + start the self-tune loop.
        // The Paper tag wired SourbyCraftConfig.init() into a DedicatedServer NMS patch and drove
        // the sensor from MinecraftServer.tickChildren. Folia has no global tick loop, so on this
        // base we (1) init the config registry here (CraftServer + Bukkit API are already live at
        // this post-config hook — SourbyCraftCommands.registerAll above proves that) and (2) start
        // the sensor's Folia global-region-scheduler sampler. Both are idempotent / double-start
        // guarded. Wrapped so a failure here cannot abort the boot sequence.
        dev.iyanz.sourbycraft.perf.PerfEngineBootstrap.start();
    }
}
