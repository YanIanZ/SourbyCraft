package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Not a test: a hand-run measurement of the FILE backend's bulk write (the "3,364 chunks = 14.9 s"
 * number in {@code aurora-world-fabric.md}). Asserts nothing. Skipped unless the environment
 * variable {@code AWF_FSYNC_BENCH} is set or the marker file {@code <java.io.tmpdir>/awf-fsync-bench.marker}
 * exists (the marker is for Gradle runs whose configuration cache hides new environment variables).
 *
 * <p>Workload: {@value #CHUNKS} synthetic chunks of {@value #RAW_BYTES} low-entropy bytes each
 * (seeded PRNG, so every run writes the same bytes), committed INCREMENTAL into a fresh store;
 * then {@value #CHANGED} of them changed and committed again, as a periodic commit would. Wall
 * time per commit, {@value #RUNS} runs each after one warm-up, min and median. Storage-level only:
 * no server, no players; the host is shared with other work, so this is an uncertified local A/B.</p>
 */
class AwfBulkWriteBenchmarkTest {

    static final int CHUNKS = 3364;
    static final int RAW_BYTES = 22_000;
    static final int CHANGED = 336;
    static final int RUNS = 5;

    @TempDir Path dir;

    private static boolean enabled() {
        return System.getenv("AWF_FSYNC_BENCH") != null
            || Files.exists(Path.of(System.getProperty("java.io.tmpdir"), "awf-fsync-bench.marker"));
    }

    private static Map<ChunkKey, byte[]> chunks(final long seed, final int count) {
        final Random random = new Random(seed);
        final Map<ChunkKey, byte[]> chunks = new HashMap<>();
        for (int i = 0; i < count; i++) {
            final byte[] raw = new byte[RAW_BYTES];
            // Low entropy so deflate keeps a few KiB per object, like chunk NBT does.
            for (int j = 0; j < raw.length; j++) raw[j] = (byte) random.nextInt(6);
            chunks.put(new ChunkKey(i % 64, i / 64), raw);
        }
        return chunks;
    }

    private static void deleteTree(final Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            for (final Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    private static String stats(final List<Double> times) {
        final List<Double> sorted = new ArrayList<>(times);
        Collections.sort(sorted);
        return String.format("min %.0f ms, median %.0f ms, max %.0f ms (runs: %s)", sorted.get(0),
            sorted.get(sorted.size() / 2), sorted.get(sorted.size() - 1),
            times.stream().map(t -> String.format("%.0f", t)).toList());
    }

    @Test
    void measureBulkWrite() throws Exception {
        assumeTrue(enabled(), "set AWF_FSYNC_BENCH or create <tmpdir>/awf-fsync-bench.marker to run this measurement");
        final Map<ChunkKey, byte[]> initial = chunks(1L, CHUNKS);
        final Map<ChunkKey, byte[]> changed = chunks(2L, CHANGED);
        final List<Double> bulk = new ArrayList<>();
        final List<Double> incremental = new ArrayList<>();
        final List<Double> compaction = new ArrayList<>();
        final List<Double> reopen = new ArrayList<>();
        long storedBytes = 0;
        long incrementalBytes = 0;
        boolean secondWasDelta = false;
        int commitsBeforeCompaction = 0;
        for (int run = 0; run <= RUNS; run++) {
            final Path root = this.dir.resolve("run-" + run);
            final long t0 = System.nanoTime();
            final AwfWorldStore store = AwfWorldStore.open(root, WorldRole.VANILLA, 1, true); // deltas on: the AWF-7 path
            final AwfStore.CommitResult first = store.commit(initial, Set.of(), PersistenceMode.INCREMENTAL);
            final double bulkMs = (System.nanoTime() - t0) / 1e6;
            final long t1 = System.nanoTime();
            final AwfStore.CommitResult second = store.commit(changed, Set.of(), PersistenceMode.INCREMENTAL);
            final double incMs = (System.nanoTime() - t1) / 1e6;
            secondWasDelta = !store.lastCommitCompacted();
            // Keep committing fresh changes until the policy compacts (writes a full index); time that one.
            double compactionMs = Double.NaN;
            int more = 0;
            for (long seed = 3L; Double.isNaN(compactionMs) && more < 64; seed++, more++) {
                final long t2 = System.nanoTime();
                store.commit(chunks(seed, CHANGED), Set.of(), PersistenceMode.INCREMENTAL);
                if (store.lastCommitCompacted()) compactionMs = (System.nanoTime() - t2) / 1e6;
            }
            commitsBeforeCompaction = more;
            // Reopen cost after the compaction (a full index) is the floor; after deltas it replays them.
            store.commit(chunks(99L, CHANGED), Set.of(), PersistenceMode.INCREMENTAL);
            final long t3 = System.nanoTime();
            AwfWorldStore.open(root, WorldRole.READ_ONLY, 1);
            final double reopenMs = (System.nanoTime() - t3) / 1e6;
            if (run > 0) { // run 0 is the warm-up
                bulk.add(bulkMs);
                incremental.add(incMs);
                compaction.add(compactionMs);
                reopen.add(reopenMs);
            }
            storedBytes = first.bytesWritten();
            incrementalBytes = second.bytesWritten();
            deleteTree(root);
        }
        final String report = String.format(
            "AWF-FSYNC-BENCH%n"
                + "workload: %d chunks x %d raw bytes (seeded, low entropy), %d objects / %.1f MiB stored per bulk commit;"
                + " then %d changed chunks, %d objects / %.1f MiB stored%n"
                + "host: %s %s, %d cores, JDK %s, tmpdir %s; %d runs after one warm-up; uncertified local run%n"
                + "bulk commit (open + commit of all %d chunks): %s%n"
                + "incremental commit (%d changed chunks, %s): %s%n"
                + "compaction commit (%d changed chunks + full index, the %dth delta-sized commit after the bulk): %s%n"
                + "read-only reopen (full index + one delta replayed): %s%n",
            CHUNKS, RAW_BYTES, CHUNKS, storedBytes / 1048576.0, CHANGED, CHANGED, incrementalBytes / 1048576.0,
            System.getProperty("os.name"), System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors(),
            System.getProperty("java.version"), System.getProperty("java.io.tmpdir"), RUNS,
            CHUNKS, stats(bulk), CHANGED, secondWasDelta ? "a delta generation" : "a full index", stats(incremental),
            CHANGED, commitsBeforeCompaction + 1, stats(compaction), stats(reopen));
        System.out.println(report);
        Files.writeString(Path.of(System.getProperty("java.io.tmpdir"), "awf-fsync-bench.out"), report, StandardCharsets.UTF_8);
    }
}
