package dev.iyanz.sourbycraft.wildstacker;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Minimal helper kept after wildstacker module deletion (89ca0ae).
 *
 * Provides {@link #ownerPlugin()} for commands that need a Plugin instance to
 * schedule Bukkit tasks. Paper 26.1.2 separates PaperPluginManagerImpl from the
 * legacy SimplePluginManager, so {@code Bukkit.getPluginManager().getPlugins()}
 * can return an empty array even when plugins are loaded. This bridge tries
 * both managers and returns any enabled plugin.
 */
public final class WildstackerManager {

    private WildstackerManager() {}

    /**
     * Returns an enabled plugin for scheduler registration. Tries legacy
     * SimplePluginManager first, then PaperPluginManagerImpl. Returns
     * {@code null} if no enabled plugin is available — callers must
     * null-check and bail out rather than schedule against a disabled
     * plugin (the Bukkit scheduler throws IllegalPluginAccessException
     * on disabled owners).
     */
    public static Plugin ownerPlugin() {
        Plugin[] legacy = Bukkit.getPluginManager().getPlugins();
        for (Plugin p : legacy) {
            if (p != null && p.isEnabled()) return p;
        }
        try {
            Plugin[] paper =
                io.papermc.paper.plugin.manager.PaperPluginManagerImpl.getInstance().getPlugins();
            for (Plugin p : paper) {
                if (p != null && p.isEnabled()) return p;
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
