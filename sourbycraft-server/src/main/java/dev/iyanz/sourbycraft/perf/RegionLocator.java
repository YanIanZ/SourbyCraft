package dev.iyanz.sourbycraft.perf;

import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Resolves the telemetry region that owns a chunk at a block coordinate, for
 * {@code /perf region <world> <x> <z>}.
 *
 * <p>Everything read here is already published for readers: the regioniser's region mapping (a
 * single-writer/multi-reader table read without the write lock, so the answer can be a transient
 * or soon-to-retire region — fine for diagnostics), the {@code ServerLevel}'s final telemetry world
 * id, and the snapshot each region's metrics last published (read lock-free through
 * {@link RegionMetricsRegistry#forEachUnexpiredOf}; no window is refreshed). No chunk, entity, block,
 * inventory or other world state is touched, and nothing is mutated, so this is safe from any
 * thread, including a command thread that owns no region.</p>
 *
 * <p>The lookup is approximate: during a region merge or split the section owner is briefly
 * unpublished, so a loaded chunk can resolve to no region. The locator re-reads once; if the
 * owner is still missing the result is {@link Status#NO_REGION_AT_CHUNK}, which the view words as
 * "no region resolved", not "not loaded".</p>
 */
public final class RegionLocator {

    /** The live server; tests pass their own {@link Topology}. */
    private static final Topology LIVE = new LiveTopology();

    private RegionLocator() {}

    /** Why {@link #locate} did not return a region with published metrics. */
    public enum Status { FOUND, NO_SERVER, UNKNOWN_WORLD, NO_REGION_AT_CHUNK, NO_TELEMETRY }

    /** The resolved region identity and its published tick snapshot. */
    public record Result(Status status, long worldId, long regionId, long generationId,
                         boolean active, RegionTickMetrics.Snapshot snapshot, int chunkX, int chunkZ) {}

    /** The generation a region id resolves to: the newest one, active or not. */
    record Generation(long generationId, boolean active, RegionTickMetrics.Snapshot snapshot) {}

    /** What the locator needs from the server: its worlds, or null when no server is running. */
    interface Topology {
        /** Null when no server is running. */
        List<WorldHandle> worlds();
    }

    /** One loaded world. */
    interface WorldHandle {
        /** Region ids start at 1 ({@code 0} is reserved for the global region), so 0 means none. */
        long NO_REGION = 0L;

        String name();

        long telemetryWorldId();

        /** The id of the region owning the chunk, or {@link #NO_REGION}. */
        long regionIdAt(int chunkX, int chunkZ);
    }

    public static Result locate(final String worldName, final int blockX, final int blockZ,
                                final long nowNanos) {
        return locate(LIVE, RegionMetricsRegistry.INSTANCE, worldName, blockX, blockZ, nowNanos);
    }

    static Result locate(final Topology topology, final RegionMetricsRegistry registry, final String worldName,
                         final int blockX, final int blockZ, final long nowNanos) {
        final List<WorldHandle> worlds = topology.worlds();
        if (worlds == null) {
            return new Result(Status.NO_SERVER, 0L, 0L, 0L, false, null, 0, 0);
        }
        final WorldHandle world = findWorld(worlds, worldName);
        if (world == null) {
            return new Result(Status.UNKNOWN_WORLD, 0L, 0L, 0L, false, null, 0, 0);
        }
        final long worldId = world.telemetryWorldId();
        final int chunkX = chunkOf(blockX);
        final int chunkZ = chunkOf(blockZ);
        long regionId = world.regionIdAt(chunkX, chunkZ);
        if (regionId == WorldHandle.NO_REGION) {
            // A merge or split unpublishes the owner for a moment; one re-read covers the common case.
            regionId = world.regionIdAt(chunkX, chunkZ);
        }
        if (regionId == WorldHandle.NO_REGION) {
            return new Result(Status.NO_REGION_AT_CHUNK, worldId, 0L, 0L, false, null, chunkX, chunkZ);
        }
        final Generation generation = bestGeneration(registry, worldId, regionId, nowNanos);
        if (generation == null) {
            return new Result(Status.NO_TELEMETRY, worldId, regionId, 0L, false, null, chunkX, chunkZ);
        }
        return new Result(Status.FOUND, worldId, regionId, generation.generationId(),
            generation.active(), generation.snapshot(), chunkX, chunkZ);
    }

    /**
     * The world named {@code name}, resolved like Bukkit's {@code Server.getWorld(String)}: Bukkit
     * keys worlds by {@code name.toLowerCase(Locale.ROOT)}, so names match case-insensitively (and
     * two worlds cannot differ by case alone). Tab completion offers names the same way.
     */
    static WorldHandle findWorld(final List<WorldHandle> worlds, final String name) {
        final String key = name.toLowerCase(Locale.ROOT);
        for (final WorldHandle world : worlds) {
            if (world.name().toLowerCase(Locale.ROOT).equals(key)) {
                return world;
            }
        }
        return null;
    }

    /** Block coordinate to chunk coordinate, matching Minecraft's {@code >> 4} floor for negatives. */
    static int chunkOf(final int block) {
        return block >> 4;
    }

    /**
     * The generation a (world, region) resolves to. A merge or an inactive/re-activate cycle keeps
     * the same region id but moves to a fresh generation, so the newest (highest id) is the one to
     * report; a retired-but-retained predecessor stays visible only while the region id has no
     * newer generation. Expired generations are skipped. Reads only published snapshots: no
     * generation, matching or not, is refreshed.
     */
    static Generation bestGeneration(final RegionMetricsRegistry registry, final long worldId,
                                     final long regionId, final long nowNanos) {
        final Generation[] best = new Generation[1];
        registry.forEachUnexpiredOf(worldId, regionId, nowNanos, view -> {
            if (best[0] == null || view.generationId() > best[0].generationId()) {
                best[0] = new Generation(view.generationId(), view.active(), view.snapshot());
            }
        });
        return best[0];
    }

    /** The running server's worlds. Upstream-internal: listed in the dependency ledger §1.2. */
    static final class LiveTopology implements Topology {
        @Override
        public List<WorldHandle> worlds() {
            final MinecraftServer server = MinecraftServer.getServer();
            if (server == null) {
                return null;
            }
            final List<WorldHandle> worlds = new ArrayList<>();
            for (final ServerLevel level : server.getAllLevels()) {
                worlds.add(new LiveWorld(level));
            }
            return worlds;
        }
    }

    private record LiveWorld(ServerLevel level) implements WorldHandle {
        @Override
        public String name() {
            return this.level.getWorld().getName();
        }

        @Override
        public long telemetryWorldId() {
            return this.level.sourbyTelemetryWorldId;
        }

        @Override
        public long regionIdAt(final int chunkX, final int chunkZ) {
            // The region table is read without the write lock (see the class javadoc).
            final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region =
                this.level.regioniser.getRegionAtUnsynchronised(chunkX, chunkZ);
            return region == null ? NO_REGION : region.getData().id;
        }
    }
}
