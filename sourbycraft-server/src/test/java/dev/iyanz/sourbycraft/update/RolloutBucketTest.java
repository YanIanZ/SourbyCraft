package dev.iyanz.sourbycraft.update;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deterministic staged-rollout buckets from a local, random server id. */
class RolloutBucketTest {

    @TempDir Path dir;

    @Test
    void theIdIsCreatedOnceAndReusedAcrossCalls() throws Exception {
        String id = RolloutBucket.serverId(dir);
        assertNotNull(id);
        assertTrue(id.matches("[0-9a-f]{32}"), id);
        assertEquals(id, RolloutBucket.serverId(dir), "stable across restarts");
        assertEquals(id, Files.readString(dir.resolve(RolloutBucket.ID_FILE)).trim());
        assertFalse(Files.exists(dir.resolve(RolloutBucket.ID_FILE + ".part")));
    }

    @Test
    void aMalformedIdIsReplacedAndAnUnwritableDirectoryFallsBack() throws Exception {
        Files.writeString(dir.resolve(RolloutBucket.ID_FILE), "not-an-id");
        String id = RolloutBucket.serverId(dir);
        assertNotNull(id);
        assertTrue(id.matches("[0-9a-f]{32}"));
        Path notADir = Files.writeString(dir.resolve("file"), "x");
        assertNull(RolloutBucket.serverId(notADir));
        assertEquals(RolloutBucket.FALLBACK_BUCKET, RolloutBucket.bucket(null, "v26.2-r48"));
        assertFalse(RolloutBucket.included(null, "v26.2-r48", 99), "an unknown server only gets full rollouts");
        assertTrue(RolloutBucket.included(null, "v26.2-r48", 100));
    }

    @Test
    void bucketsAreDeterministicInRangeAndVaryByTag() {
        String id = "0123456789abcdef0123456789abcdef";
        int b = RolloutBucket.bucket(id, "v26.2-r48");
        assertEquals(b, RolloutBucket.bucket(id, "v26.2-r48"));
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            int x = RolloutBucket.bucket(id, "v26.2-r" + i);
            assertTrue(x >= 0 && x < 100, String.valueOf(x));
            seen.add(x);
        }
        assertTrue(seen.size() > 50, "per-tag salt spreads one server over the buckets: " + seen.size());
    }

    @Test
    void theRolloutBoundaryIsBucketLessThanPercent() {
        String id = "fedcba9876543210fedcba9876543210";
        String tag = "v26.2-r48";
        int b = RolloutBucket.bucket(id, tag);
        assertFalse(RolloutBucket.included(id, tag, b), "bucket == percent is outside");
        assertTrue(RolloutBucket.included(id, tag, b + 1), "bucket < percent is inside");
        assertFalse(RolloutBucket.included(id, tag, 0), "0 % reaches nobody");
        assertTrue(RolloutBucket.included(id, tag, 100), "100 % reaches everybody");
    }

    @Test
    void aboutTheConfiguredShareOfServersIsIncluded() {
        int in = 0;
        for (int i = 0; i < 10_000; i++) {
            if (RolloutBucket.included(String.format("%032x", i), "v26.2-r48", 25)) in++;
        }
        assertTrue(in > 2_200 && in < 2_800, "25% of 10k ids: " + in);
    }

    @Test
    void concurrentFirstUseAgreesOnOnePersistedId() throws Exception {
        for (int round = 0; round < 20; round++) {
            Path fresh = dir.resolve("round-" + round);
            int threads = 8;
            java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(threads);
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
            try {
                java.util.List<java.util.concurrent.Future<String>> ids = new java.util.ArrayList<>();
                for (int i = 0; i < threads; i++) {
                    ids.add(pool.submit(() -> {
                        start.await();
                        return RolloutBucket.serverId(fresh);
                    }));
                }
                Set<String> seen = new HashSet<>();
                for (var f : ids) seen.add(f.get());
                assertEquals(1, seen.size(), "every caller returns the same id: " + seen);
                assertEquals(seen.iterator().next(), Files.readString(fresh.resolve(RolloutBucket.ID_FILE)).trim(),
                    "and it is the id on disk");
                try (var files = Files.list(fresh)) {
                    assertEquals(1, files.count(), "no temporary file left");
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    void anExistingIdIsNeverReplaced() throws Exception {
        String id = "00112233445566778899aabbccddeeff";
        Files.writeString(dir.resolve(RolloutBucket.ID_FILE), id + "\n");
        assertEquals(id, RolloutBucket.serverId(dir));
    }
}
