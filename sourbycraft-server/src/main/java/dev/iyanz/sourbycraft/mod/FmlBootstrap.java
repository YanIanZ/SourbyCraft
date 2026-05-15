package dev.iyanz.sourbycraft.mod;

import java.util.List;

/**
 * NeoForge FML bootstrap loader.
 * Scans mods/ folder via ModScanner. Attempts FML integration
 * if NeoForge 21.1.230+ is on the classpath.
 */
public final class FmlBootstrap {

    private static boolean initialized = false;
    private static boolean modsScanned = false;
    private static List<ModScanner.ModInfo> discoveredMods = List.of();

    public static void init() {
        if (initialized) return;

        discoveredMods = ModScanner.scan();
        modsScanned = true;

        System.out.println("[SourbyCraft] Found " + discoveredMods.size() + " mods in mods/ folder");
        for (ModScanner.ModInfo mod : discoveredMods) {
            System.out.println("  - " + mod.name() + " v" + mod.version() + " [" + mod.id() + "]");
        }

        if (!discoveredMods.isEmpty()) {
            tryInitFml();
        }

        initialized = true;
    }

    private static void tryInitFml() {
        try {
            Class<?> modListClass = Class.forName("net.neoforged.fml.ModList");
            System.out.println("[SourbyCraft] NeoForge FML detected on classpath");
            // FML mod loading requires FMLCommonLaunchHandler bootstrap
            // which needs server-side-only initialization
            System.out.println("[SourbyCraft] " + discoveredMods.size() + " mods detected, full FML integration pending");
        } catch (ClassNotFoundException e) {
            System.out.println("[SourbyCraft] NeoForge FML not found — scanning only");
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static boolean modsScanned() {
        return modsScanned;
    }

    public static List<ModScanner.ModInfo> getDiscoveredMods() {
        return discoveredMods;
    }
}
