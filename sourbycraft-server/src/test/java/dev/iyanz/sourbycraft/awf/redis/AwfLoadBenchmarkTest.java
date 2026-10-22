package dev.iyanz.sourbycraft.awf.redis;

import dev.iyanz.sourbycraft.awf.AwfStore;
import dev.iyanz.sourbycraft.awf.AwfWorldFile;
import dev.iyanz.sourbycraft.awf.AwfWorldStore;
import dev.iyanz.sourbycraft.awf.ChunkKey;
import dev.iyanz.sourbycraft.awf.ChunkSource;
import dev.iyanz.sourbycraft.awf.PersistenceMode;
import dev.iyanz.sourbycraft.awf.WorldRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * Not a test: a measurement, run by hand with {@code AWF_BENCH_FILE=<world.awf>} (and optionally
 * {@code AWF_BENCH_SLIME=<file.slime>}). Prints medians; asserts nothing. Storage level only: it
 * times opening a world's chunk store and reading every chunk, not a server loading a world.
 */
@EnabledIfEnvironmentVariable(named = "AWF_BENCH_FILE", matches = ".+")
class AwfLoadBenchmarkTest {

    @TempDir Path dir;
    static final int RUNS = 7;

    interface Op {
        long run() throws Exception;
    }

    /** Median wall time in ms of {@code RUNS} runs, after one warm-up. */
    static double median(final Op op) throws Exception {
        op.run();
        final List<Double> times = new ArrayList<>();
        for (int i = 0; i < RUNS; i++) {
            final long start = System.nanoTime();
            op.run();
            times.add((System.nanoTime() - start) / 1e6);
        }
        Collections.sort(times);
        return times.get(RUNS / 2);
    }

    static long readAll(final ChunkSource source) throws Exception {
        long bytes = 0;
        for (final ChunkKey key : source.keys()) bytes += source.read(key).orElseThrow().length;
        return bytes;
    }

    @Test
    void measure() throws Exception {
        final Path awf = Path.of(System.getenv("AWF_BENCH_FILE"));
        final Map<ChunkKey, byte[]> chunks = new HashMap<>();
        try (AwfWorldFile file = AwfWorldFile.open(awf)) {
            final ChunkSource region = file.stream("region");
            for (final ChunkKey key : region.keys()) chunks.put(key, region.read(key).orElseThrow());
        }
        final long raw = chunks.values().stream().mapToLong(b -> b.length).sum();
        final StringBuilder out = new StringBuilder();
        out.append(String.format("data: %s, %d region chunks, %.1f MiB uncompressed NBT, %.1f MiB file%n",
            awf.getFileName(), chunks.size(), raw / 1048576.0, Files.size(awf) / 1048576.0));
        out.append(String.format("host: %s %s, %d cores, JDK %s; median of %d runs after a warm-up%n",
            System.getProperty("os.name"), System.getProperty("os.arch"), Runtime.getRuntime().availableProcessors(),
            System.getProperty("java.version"), RUNS));

        // .awf world file
        final double awfOpen = median(() -> {
            try (AwfWorldFile f = AwfWorldFile.open(awf)) {
                return f.stream("region").keys().size();
            }
        });
        final double awfAll = median(() -> {
            try (AwfWorldFile f = AwfWorldFile.open(awf)) {
                return readAll(f.stream("region"));
            }
        });

        // FILE backend store
        final Path store = this.dir.resolve("region.awf");
        final long fileWrite = System.nanoTime();
        AwfWorldStore.open(store, WorldRole.VANILLA, 1).commit(chunks, Set.of(), PersistenceMode.INCREMENTAL);
        final double fileWriteMs = (System.nanoTime() - fileWrite) / 1e6;
        final double fileOpen = median(() -> AwfWorldStore.open(store, WorldRole.READ_ONLY, 1).keys().size());
        final double fileAll = median(() -> readAll(AwfWorldStore.open(store, WorldRole.READ_ONLY, 1)));

        // Redis backend store, local server over loopback, AOF everysec
        try (RedisTestServer server = RedisTestServer.start("--appendonly", "yes", "--appendfsync", "everysec")) {
            final RedisBackend backend = new RedisBackend(new RedisSettings(server.uri(), "bench:", 8, 10_000, 60, true),
                new RedisClient(RedisClient.Endpoint.parse(server.uri()), 8, 10_000), "bench");
            try {
                final long redisWrite = System.nanoTime();
                final AwfStore writer = backend.open("bench/region", WorldRole.VANILLA, 1);
                writer.commit(chunks, Set.of(), Set.of(), PersistenceMode.INCREMENTAL);
                writer.close();
                final double redisWriteMs = (System.nanoTime() - redisWrite) / 1e6;
                final double redisOpen = median(() -> backend.open("bench/region", WorldRole.READ_ONLY, 1).keys().size());
                final double redisAll = median(() -> readAll(backend.open("bench/region", WorldRole.READ_ONLY, 1)));
                out.append(String.format("%-34s %10s %12s %14s%n", "", "open (ms)", "read all (ms)", "per chunk (us)"));
                out.append(row(".awf world file (zstd)", awfOpen, awfAll, chunks.size()));
                out.append(row("FILE store (region.awf dir)", fileOpen, fileAll, chunks.size()));
                out.append(row("Redis store (loopback)", redisOpen, redisAll, chunks.size()));
                out.append(String.format("write all once: FILE %.0f ms, Redis %.0f ms (one commit, WAITAOF)%n",
                    fileWriteMs, redisWriteMs));
            } finally {
                backend.shutdown();
            }
        }

        final String slime = System.getenv("AWF_BENCH_SLIME");
        if (slime != null) {
            final byte[] bytes = Files.readAllBytes(Path.of(slime));
            final Path converted = this.dir.resolve("slime.awf");
            dev.iyanz.sourbycraft.awf.world.AuroraWorldFiles.convertSlime(bytes, "normal", "bench", converted);
            final double slimeAll = median(() -> dev.iyanz.sourbycraft.awf.world.SlimeImporter.convert(
                Files.readAllBytes(Path.of(slime)), -4).region().size());
            final double slimeAwf = median(() -> {
                try (AwfWorldFile f = AwfWorldFile.open(converted)) {
                    return readAll(f.stream("region"));
                }
            });
            out.append(String.format("same island, read every chunk: .slime %.2f ms (%d bytes), .awf %.2f ms (%d bytes)%n",
                slimeAll, bytes.length, slimeAwf, Files.size(converted)));
        }
        System.out.println("AWF-BENCH\n" + out);
        Files.writeString(Path.of(System.getenv().getOrDefault("AWF_BENCH_OUT", "build/awf-bench.txt")), out.toString());
    }

    private static String row(final String name, final double open, final double all, final int chunks) {
        return String.format("%-34s %10.1f %12.1f %14.1f%n", name, open, all, all * 1000 / chunks);
    }
}
