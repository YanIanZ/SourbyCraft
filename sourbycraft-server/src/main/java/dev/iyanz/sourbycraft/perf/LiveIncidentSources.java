package dev.iyanz.sourbycraft.perf;

import dev.iyanz.sourbycraft.awf.AwfEngine;
import dev.iyanz.sourbycraft.awf.AwfRegionStorage;
import dev.iyanz.sourbycraft.bridge.AuroraBridge;
import dev.iyanz.sourbycraft.bridge.BridgeTelemetry;
import dev.iyanz.sourbycraft.execution.GovernedLane;
import dev.iyanz.sourbycraft.execution.ResourceGovernor;
import dev.iyanz.sourbycraft.perf.IncidentDetector.Counter;
import dev.iyanz.sourbycraft.perf.IncidentDetector.External;
import dev.iyanz.sourbycraft.perf.IncidentDetector.Gauge;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The live readings behind {@link IncidentDetector}: the same public counters {@code /perf governor},
 * {@code /perf async}, {@code /perf storage}, {@code /perf awf} and {@code /perf plugins} render.
 * Each is read on the collector thread; none takes a region lock or touches region-owned state.
 * A source that throws contributes nothing to this pass instead of failing the others.
 */
final class LiveIncidentSources implements IncidentDetector.Sources {

    @Override
    public External read() {
        return new External(
            quietly(LiveIncidentSources::laneRejections, List.of()),
            // Saturation mostly shows as declined (the offload checks capacity before it submits), as refused
            // only when the queue fills between that check and the submission.
            quietly(() -> {
                final AsyncPathProcessor.PathStats stats = AsyncPathProcessor.stats();
                return stats.refused() + stats.declined();
            }, -1L),
            quietly(LiveIncidentSources::storagePending, List.of()),
            quietly(LiveIncidentSources::awfFailures, List.of()),
            quietly(LiveIncidentSources::bridgeViolations, List.of()));
    }

    private static List<Counter> laneRejections() {
        final List<Counter> out = new ArrayList<>();
        for (final GovernedLane.Stats lane : ResourceGovernor.GLOBAL.stats()) {
            if (lane.name().equals(ResourceGovernor.Lane.PATHFINDING.display())) {
                continue; // already the async path's refused reading; counting it here doubles it
            }
            out.add(new Counter(lane.name(), lane.rejected()));
        }
        return out;
    }

    private static List<Gauge> storagePending() {
        final List<Gauge> out = new ArrayList<>();
        for (final StorageBacklog.WorldQueue world : RegionIoQueue.INSTANCE.sample()) {
            out.add(new Gauge(world.world(), world.total()));
        }
        return out;
    }

    private static List<Counter> awfFailures() {
        final AwfEngine engine = AwfEngine.ifStarted();
        if (engine == null) {
            return List.of();
        }
        final List<Counter> out = new ArrayList<>();
        for (final AwfRegionStorage storage : engine.storages()) {
            final AwfRegionStorage.Stats stats = storage.stats();
            out.add(new Counter(stats.name(), stats.world().failures()));
        }
        return out;
    }

    private static List<Counter> bridgeViolations() {
        final List<Counter> out = new ArrayList<>();
        for (final BridgeTelemetry.PluginStats plugin : AuroraBridge.allStats()) {
            out.add(new Counter(plugin.plugin(), plugin.fatalViolations()));
        }
        return out;
    }

    private static <T> T quietly(final Supplier<T> read, final T fallback) {
        try {
            final T value = read.get();
            return value == null ? fallback : value;
        } catch (final RuntimeException | LinkageError failure) {
            return fallback;
        }
    }
}
