package dev.iyanz.sourbycraft.diagnostics;

import dev.iyanz.sourbycraft.config.AuroraConfig;
import dev.iyanz.sourbycraft.execution.region.FoliaRegionBackend;
import dev.iyanz.sourbycraft.execution.region.RegionBackend;
import dev.iyanz.sourbycraft.perf.RegionMetricsRegistry;
import dev.iyanz.sourbycraft.perf.RegionTickMetrics;
import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Lifecycle of the one process-wide {@link StallRecorder}, started by {@code SourbyCraftBootstrap}. */
public final class StallRecorderRuntime {

    /** Relative to the server's working directory, like {@code logs/latest.log}. */
    public static final Path DIRECTORY = Path.of("logs", "stalls");

    private static StallRecorder recorder;

    private StallRecorderRuntime() {}

    /**
     * Starts the recorder unless disabled or already running. Failure to start is logged and never
     * thrown: this is evidence-gathering, and a server must boot without it.
     */
    public static synchronized void start(final AuroraConfig.StallRecorder settings) {
        if (!settings.enabled() || recorder != null) {
            return;
        }
        final FoliaRegionBackend backend = new FoliaRegionBackend();
        final StallFiles files = new StallFiles(DIRECTORY, settings.keepFiles());
        final StallDumper newDumper = new StallDumper(files, settings, backend.globalTickFrameMarker(),
            backend.regionTickFrameMarker(), backend::population, System::currentTimeMillis);
        final StallRecorder created = new StallRecorder(settings, liveSources(backend, RegionMetricsRegistry.INSTANCE::forEachActive),
            newDumper, System::nanoTime, StallRecorderRuntime::serverStopping, SourbyLogger::warn, SourbyLogger::info);
        created.start();
        recorder = created;
    }

    /** Stops the thread. A stall still open keeps its shutdown hook (see {@link StallRecorder#close()}). */
    public static synchronized void stop() {
        final StallRecorder closing = recorder;
        recorder = null;
        if (closing != null) {
            closing.close();
        }
    }

    /** The global tick, then every live region generation; reads two volatile markers each. */
    static StallRecorder.Sources liveSources(final RegionBackend backend,
                                             final Consumer<Consumer<RegionMetricsRegistry.ActiveGeneration>> regions) {
        return out -> {
            final RegionTickMetrics global = backend.globalTickMetricsLive();
            if (global != null) {
                out.accept(new StallRecorder.Reading("global tick", true,
                    global.lastCompletedEndNanos(), global.activeTickStartNanos()));
            }
            regions.accept(region -> out.accept(new StallRecorder.Reading(
                "region #" + region.regionId() + " (world id " + region.worldId() + ")", false,
                region.metrics().lastCompletedEndNanos(), region.metrics().activeTickStartNanos())));
        };
    }

    private static boolean serverStopping() {
        try {
            return org.bukkit.Bukkit.isStopping();
        } catch (final Throwable notReady) {
            return false;
        }
    }
}
