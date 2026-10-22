package dev.iyanz.sourbycraft.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.config.AuroraConfig;
import dev.iyanz.sourbycraft.execution.region.RegionPopulation;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The real dumper and the real recorder together over a temp directory: what an operator would find on disk. */
class StallDumperTest {

    private static final long S = TimeUnit.SECONDS.toNanos(1L);

    @TempDir
    Path tmp;

    @Test
    void aWholeStallLeavesOneReadableFileWithEveryDumpAndRotatesOldFiles() throws IOException {
        final AuroraConfig.StallRecorder settings = new AuroraConfig.StallRecorder(true, 5, 5, 3, 2);
        final StallFiles files = new StallFiles(this.tmp.resolve("logs/stalls"), settings.keepFiles());
        final AtomicLong epoch = new AtomicLong(1_790_000_000_000L);
        final AtomicLong nano = new AtomicLong(1_000L * S);
        final AtomicLong globalEnd = new AtomicLong(nano.get());
        final List<String> warns = new java.util.ArrayList<>();
        try (StallDumper dumper = new StallDumper(files, settings, "NoMarker", "NoMarker",
            () -> List.of(new RegionPopulation.WorldCounts("world", 3, 120L, 45L, 2L)), epoch::get)) {
            final StallRecorder recorder = new StallRecorder(settings, out ->
                out.accept(new StallRecorder.Reading("global tick", true, globalEnd.get(), StallRecorder.NO_ACTIVE_TICK)),
                dumper, nano::get, () -> false, warns::add, message -> { });

            // Three stalls; keep-files = 2 leaves the last two.
            for (int stall = 0; stall < 3; ++stall) {
                for (int i = 0; i < 25; ++i) {
                    nano.addAndGet(S);
                    epoch.addAndGet(1_000L);
                    recorder.poll();
                }
                globalEnd.set(nano.get()); // recovers
                nano.addAndGet(S / 20);
                recorder.poll();
                epoch.addAndGet(60_000L);
            }

            final List<Path> kept = files.list();
            assertEquals(2, kept.size(), kept.toString());
            final String text = Files.readString(kept.get(1));
            assertEquals(3, countOf(text, "=== SourbyCraft stall recorder: stall #3, dump "), text.substring(0, 500));
            assertTrue(text.contains("dump 1 of at most 3"), "first dump");
            assertTrue(text.contains("dump 3 of at most 3"), "capped at 3 even though the stall lasted 25 s");
            assertTrue(text.contains("world: 3 region(s), 120 chunks, 45 entities, 2 players"), "population section");
            assertTrue(text.contains("--- all threads ("), "thread dump");
            assertEquals(3, warns.stream().filter(w -> w.contains(".txt")).count(), "one WARN per stall: " + warns);
            assertTrue(warns.get(2).contains(kept.get(1).toString()), "the WARN names the file");
        }
    }

    @Test
    void aPopulationReadThatBlocksCostsOneLineNotTheDump() throws Exception {
        final AuroraConfig.StallRecorder settings = new AuroraConfig.StallRecorder(true, 5, 5, 3, 5);
        final StallFiles files = new StallFiles(this.tmp, 5);
        final CountDownLatch never = new CountDownLatch(1);
        try (StallDumper dumper = new StallDumper(files, settings, "x", "x", () -> {
            try {
                never.await(60, TimeUnit.SECONDS);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        }, () -> 1_790_000_000_000L)) {
            final StallRecorder.DumpRequest request = new StallRecorder.DumpRequest(1L, 1, 3, StallRecorder.Kind.FIRST,
                List.of(new StallRecorder.Stalled("global tick", true, 6 * S, -1L)), 6 * S, null);
            final Path file = dumper.write(request);
            final String text = Files.readString(file);
            assertTrue(text.contains("reading the region counters did not return"), text);
            assertTrue(text.contains("--- all threads ("), "the thread dump is still there");
            // A second dump while the probe is still stuck does not queue another behind it.
            dumper.write(new StallRecorder.DumpRequest(1L, 2, 3, StallRecorder.Kind.REPEAT, request.stalled(), 11 * S, file));
            assertEquals(2, countOf(Files.readString(file), "reading the region counters did not return"));
        } finally {
            never.countDown();
        }
    }

    private static int countOf(final String text, final String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            ++count;
        }
        return count;
    }
}
