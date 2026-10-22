package dev.iyanz.sourbycraft.diagnostics;

import dev.iyanz.sourbycraft.config.AuroraConfig;
import dev.iyanz.sourbycraft.execution.region.RegionPopulation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** The production {@link StallRecorder.Dumper}: renders the report and appends it to the stall's file. */
final class StallDumper implements StallRecorder.Dumper, AutoCloseable {

    private static final long POPULATION_TIMEOUT_MILLIS = 2_000L;

    private final StallFiles files;
    private final AuroraConfig.StallRecorder settings;
    private final String globalFrameMarker;
    private final String regionFrameMarker;
    private final Supplier<List<RegionPopulation.WorldCounts>> population;
    private final LongSupplier epochClock;
    private final BoundedProbe probe = new BoundedProbe();

    StallDumper(final StallFiles files, final AuroraConfig.StallRecorder settings, final String globalFrameMarker,
                final String regionFrameMarker, final Supplier<List<RegionPopulation.WorldCounts>> population,
                final LongSupplier epochClock) {
        this.files = files;
        this.settings = settings;
        this.globalFrameMarker = globalFrameMarker;
        this.regionFrameMarker = regionFrameMarker;
        this.population = population;
        this.epochClock = epochClock;
    }

    @Override
    public Path write(final StallRecorder.DumpRequest request) throws IOException {
        final long now = this.epochClock.getAsLong();
        final Path file = request.file() != null ? request.file() : this.files.create(now);
        final List<String> lines = this.probe.call(this::populationLines, POPULATION_TIMEOUT_MILLIS,
            List.of("(unavailable: reading the region counters did not return within "
                + POPULATION_TIMEOUT_MILLIS + " ms; this probe runs on a helper thread)"));
        this.files.append(file, StallReport.render(new StallReport.Context(request, now,
            this.settings.afterSeconds(), this.settings.repeatSeconds(),
            this.globalFrameMarker, this.regionFrameMarker, lines)));
        return file;
    }

    private List<String> populationLines() {
        final List<String> lines = new ArrayList<>();
        for (final RegionPopulation.WorldCounts world : this.population.get()) {
            lines.add("  " + world.world() + ": " + world.regions() + " region(s), " + world.chunks() + " chunks, "
                + world.entities() + " entities, " + world.players() + " players");
        }
        return lines;
    }

    @Override
    public void close() {
        this.probe.close();
    }
}
