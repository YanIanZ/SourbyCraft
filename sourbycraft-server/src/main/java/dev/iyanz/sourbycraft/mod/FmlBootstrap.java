package dev.iyanz.sourbycraft.mod;

import java.util.List;

/**
 * NeoForge FML bootstrap loader.
 *
 * Currently placeholder — loaded after server starts.
 * Will initialize FML classloader, scan mods/ folder,
 * and fire dual event bus (Bukkit + NeoForge).
 *
 * Dependencies needed (commented in build.gradle.kts):
 *   net.neoforged:neoforge:21.1.0:server
 *   cpw.mods:modlauncher:11.0.0
 */
public final class FmlBootstrap {

    private static boolean initialized = false;
    private static boolean modsLoaded = false;

    public static void init() {
        if (initialized) return;

        List<ModScanner.ModInfo> mods = ModScanner.scan();
        System.out.println("[SourbyCraft] Found " + mods.size() + " mods in mods/ folder");
        for (ModScanner.ModInfo mod : mods) {
            System.out.println("  - " + mod.name() + " v" + mod.version() + " (" + mod.fileName() + ")");
        }

        // TODO: Initialize FML classloader
        // TODO: Load mod classes
        // TODO: Fire FML pre-init events
        // TODO: Initialize mod instances
        // TODO: Fire FML post-init events

        initialized = true;
        System.out.println("[SourbyCraft] FML bootstrap initialized (mods loaded: " + mods.size() + ")");
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static boolean modsLoaded() {
        return modsLoaded;
    }
}
