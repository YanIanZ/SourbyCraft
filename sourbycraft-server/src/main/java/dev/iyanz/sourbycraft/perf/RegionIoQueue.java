package dev.iyanz.sourbycraft.perf;

import ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The chunk system's storage backlog: I/O tasks each world's Moonrise controllers have accepted
 * and not finished (reads and writes, queued or running), per data type.
 *
 * <p>Read from Moonrise's own counters rather than duplicated, which makes this one of the files
 * the dependency ledger lists. Reading them is a counter load per controller; safe from any
 * thread.</p>
 */
public final class RegionIoQueue {

    public record WorldQueue(String world, long chunk, long poi, long entity) {
        public long total() {
            return this.chunk + this.poi + this.entity;
        }
    }

    private RegionIoQueue() {}

    /** One entry per loaded world; empty before the server exists. */
    public static List<WorldQueue> sample() {
        final MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return List.of();
        final List<WorldQueue> out = new ArrayList<>();
        for (final ServerLevel level : server.getAllLevels()) {
            out.add(new WorldQueue(level.getWorld().getName(),
                tasks(level, MoonriseRegionFileIO.RegionFileType.CHUNK_DATA),
                tasks(level, MoonriseRegionFileIO.RegionFileType.POI_DATA),
                tasks(level, MoonriseRegionFileIO.RegionFileType.ENTITY_DATA)));
        }
        return out;
    }

    private static long tasks(final ServerLevel level, final MoonriseRegionFileIO.RegionFileType type) {
        return MoonriseRegionFileIO.getControllerFor(level, type).getTotalWorkingTasks();
    }
}
