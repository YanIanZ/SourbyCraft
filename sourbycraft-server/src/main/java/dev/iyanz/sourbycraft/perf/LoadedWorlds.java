package dev.iyanz.sourbycraft.perf;

/** Whether a world name is loaded, for the per-world telemetry maps that drop unloaded idle worlds (audit PM-308). */
final class LoadedWorlds {

    private LoadedWorlds() {}

    /**
     * Whether Bukkit has a loaded world of this name. True without a running server (unit tests), so
     * nothing is dropped there. A map read; no I/O and no region state.
     */
    static boolean byName(final String name) {
        final org.bukkit.Server server = org.bukkit.Bukkit.getServer();
        return server == null || server.getWorld(name) != null;
    }
}
