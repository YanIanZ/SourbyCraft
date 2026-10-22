package dev.iyanz.sourbycraft.legacytest;

import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * {@code World#getChunkAt(x, z)} from a legacy async task, the way SuperiorSkyblock2 2026.3 prepares island chunks
 * (paper feature patch 0028). On Paper the async thread waits for the chunk; the bridge runs async tasks on its I/O
 * lane, which ticks nothing, so the call must return the chunk there too: once for a chunk already loaded (held by a
 * plugin ticket), once for a chunk nothing has loaded yet (loaded or generated while the task waits).
 */
final class AsyncChunkProbe {

    private static final int FAR_CHUNK = 37;

    private AsyncChunkProbe() {
    }

    /** Call with chunk (0, 0) of {@code world} already loaded and ticketed by the plugin. */
    static void run(final JavaPlugin plugin, final World world) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            final String thread = Thread.currentThread().getName();
            try {
                final Chunk loaded = world.getChunkAt(0, 0);
                final Chunk far = world.getChunkAt(FAR_CHUNK, -FAR_CHUNK);
                final boolean ok = loaded.getX() == 0 && loaded.getZ() == 0
                    && far.getX() == FAR_CHUNK && far.getZ() == -FAR_CHUNK;
                plugin.getLogger().info((ok ? "LEGACY_BRIDGE_ASYNC_GETCHUNK_OK" : "LEGACY_BRIDGE_ASYNC_GETCHUNK_WRONG")
                    + " thread=" + thread + " loaded=" + loaded.getX() + "," + loaded.getZ()
                    + " far=" + far.getX() + "," + far.getZ());
            } catch (final RuntimeException refused) {
                plugin.getLogger().severe("LEGACY_BRIDGE_ASYNC_GETCHUNK_REFUSED " + refused.getClass().getSimpleName()
                    + " thread=" + thread + ": " + refused.getMessage());
                throw refused;
            }
        });
    }
}
