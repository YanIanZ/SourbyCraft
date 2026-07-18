package dev.iyanz.sourbycraft.core;

import dev.iyanz.sourbycraft.SourbyCraftConfig;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;

/**
 * Applies the raised world-size limit (30M → 33M, border 60M → 66M) to existing worlds.
 *
 * <p>The engine constants ({@code Level.MAX_LEVEL_SIZE}, {@code WorldBorder.MAX_SIZE}, …) already
 * unlock the larger cap, but a world's border is PERSISTED in its level data, so a world created
 * before the change still carries the old vanilla "unlimited" border (59,999,968). On world load this
 * bumps that specific value up to the new max (65,999,968), leaving any deliberately-set finite border
 * alone. 33M is the maximum SAFE size — it stays under the BlockPos 26-bit hard cap (±33,554,431), so
 * block packing, chunk/region addressing and the client's own BlockPos handling all remain valid.
 */
public final class WorldSizeExpander implements Listener {

    /** The vanilla default "unlimited" border a pre-change world carries. */
    private static final double OLD_VANILLA_MAX = 59999968.0;
    /** The raised max (2 × 32,999,984). Kept just under the ±33,554,431 BlockPos cap. */
    private static final double NEW_MAX = 65999968.0;
    private static final double EPS = 1.0;

    private WorldSizeExpander() {}

    /** Register the listener + expand any already-loaded worlds. */
    public static void register(final org.bukkit.plugin.Plugin plugin) {
        try {
            Bukkit.getPluginManager().registerEvents(new WorldSizeExpander(), plugin);
            for (final World w : Bukkit.getWorlds()) expand(w);
        } catch (Throwable t) {
            SourbyLogger.warn("WorldSizeExpander registration failed: " + t.getMessage());
        }
    }

    @EventHandler
    public void onWorldLoad(final WorldLoadEvent e) {
        expand(e.getWorld());
    }

    private static void expand(final World world) {
        if (!SourbyCraftConfig.expandBorderToMax) return;
        try {
            final var border = world.getWorldBorder();
            // Only bump the exact old vanilla "unlimited" value — never override an operator's finite
            // border (a survival size cap) or a border already at/above the new max.
            if (Math.abs(border.getSize() - OLD_VANILLA_MAX) < EPS) {
                border.setSize(NEW_MAX);
                SourbyLogger.info("world '" + world.getName()
                    + "': expanded border to the raised max (66M / ±33M) — the safe maximum world size.");
            }
        } catch (Throwable t) {
            SourbyLogger.warn("WorldSizeExpander failed for world '" + world.getName() + "': " + t.getMessage());
        }
    }
}
