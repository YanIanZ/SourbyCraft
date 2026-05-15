package dev.iyanz.sourbycraft.mod;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.util.List;

/**
 * NeoForge FML bootstrap loader.
 * Scans mods/ folder via ModScanner.
 */
public final class FmlBootstrap {

    private static boolean initialized = false;
    private static boolean modsScanned = false;
    private static List<ModScanner.ModInfo> discoveredMods = List.of();

    public static void init() {
        if (initialized) return;

        discoveredMods = ModScanner.scan();
        modsScanned = true;

        SourbyLogger.info("Found " + discoveredMods.size() + " mods in mods/ folder");
        for (ModScanner.ModInfo mod : discoveredMods) {
            SourbyLogger.info("  - " + mod.name() + " v" + mod.version() + " [" + mod.id() + "]");
        }

        if (!discoveredMods.isEmpty()) {
            try {
                Class.forName("net.neoforged.fml.ModList");
                SourbyLogger.info("NeoForge FML detected on classpath");
                SourbyLogger.info(discoveredMods.size() + " mods detected, full FML integration pending");
            } catch (ClassNotFoundException e) {
                SourbyLogger.info("NeoForge FML not found — scanning only");
            }
        }

        initialized = true;
    }

    public static boolean isInitialized() { return initialized; }
    public static boolean modsScanned() { return modsScanned; }
    public static List<ModScanner.ModInfo> getDiscoveredMods() { return discoveredMods; }
}
