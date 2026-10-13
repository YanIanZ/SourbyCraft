package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.iyanz.sourbycraft.awf.redis.RedisBackend;
import dev.iyanz.sourbycraft.awf.redis.RedisClient;
import dev.iyanz.sourbycraft.awf.redis.RedisSettings;
import dev.iyanz.sourbycraft.awf.redis.RedisTestServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** AWF-5: every store the engine opens and then drops is closed, so it releases what it holds. */
class AwfEngineStoreLifecycleTest {

    @TempDir Path dir;

    /** The FILE backend, counting opens and closes of the stores it hands out. */
    private static final class CountingBackend implements AwfBackend {
        final AtomicInteger opened = new AtomicInteger();
        final AtomicInteger closed = new AtomicInteger();

        @Override
        public String name() {
            return "counting";
        }

        @Override
        public boolean exists(final String storageId) throws IOException {
            return AwfBackend.FILE.exists(storageId);
        }

        @Override
        public AwfStore open(final String storageId, final WorldRole role, final int retained) throws IOException {
            final AwfStore store = AwfBackend.FILE.open(storageId, role, retained);
            this.opened.incrementAndGet();
            return new AwfStore() {
                @Override
                public boolean has(final ChunkKey key) {
                    return store.has(key);
                }

                @Override
                public java.util.Optional<byte[]> read(final ChunkKey key) throws IOException {
                    return store.read(key);
                }

                @Override
                public java.util.Set<ChunkKey> keys() {
                    return store.keys();
                }

                @Override
                public java.util.Set<ChunkKey> deleted() {
                    return store.deleted();
                }

                @Override
                public long generation() throws IOException {
                    return store.generation();
                }

                @Override
                public CommitResult commit(final java.util.Map<ChunkKey, byte[]> changed, final Set<ChunkKey> removed,
                                           final Set<ChunkKey> deleted, final PersistenceMode mode) throws IOException {
                    return store.commit(changed, removed, deleted, mode);
                }

                @Override
                public void close() throws IOException {
                    CountingBackend.this.closed.incrementAndGet();
                    store.close();
                }
            };
        }

        @Override
        public String retire(final String storageId) throws IOException {
            return AwfBackend.FILE.retire(storageId);
        }
    }

    private AwfEngine engine(final AwfBackend backend, final Set<String> worlds) {
        return new AwfEngine(new AwfSettings(worlds, PersistenceMode.INCREMENTAL, 30, 1024, 2, 2), Runnable::run,
            System::nanoTime, name -> backend);
    }

    @Test
    void unprepareReleasesTheStore() throws Exception {
        final CountingBackend backend = new CountingBackend();
        final AwfEngine engine = engine(backend, Set.of("isl"));
        final Path dimension = this.dir.resolve("isl");
        engine.prepareStores(dimension);
        assertEquals(AwfEngine.STORAGE_FOLDERS.size(), backend.opened.get());
        engine.unprepareStores(dimension);
        assertEquals(backend.opened.get(), backend.closed.get(), "every prepared store is closed when dropped");
        engine.unprepareStores(dimension);
        assertEquals(backend.opened.get(), backend.closed.get(), "a second unprepare closes nothing twice");
    }

    @Test
    void anAlreadyOpenStorageOpensNoSecondStore() throws Exception {
        final CountingBackend backend = new CountingBackend();
        final AwfEngine engine = engine(backend, Set.of("world"));
        final Path folder = this.dir.resolve("world").resolve("region");
        Files.createDirectories(folder);
        assertNotNull(engine.open(folder));
        assertThrows(IOException.class, () -> engine.open(folder));
        assertEquals(1, backend.opened.get(), "refused before a second store (and Redis lease) was taken");
    }

    @Test
    void anUnpreparedStoreReleasesItsLease() throws Exception {
        try (RedisTestServer server = RedisTestServer.start()) {
            final RedisSettings settings = new RedisSettings(server.uri(), "lease:", 2, 2000, 30, false);
            final RedisBackend redis = new RedisBackend(settings);
            try {
                final AwfEngine engine = engine(redis, Set.of("isl"));
                final Path dimension = this.dir.resolve("isl");
                engine.prepareStores(dimension);
                final String lock = "lease:{" + AwfEngine.storeId(dimension, "region") + "}:lock";
                try (RedisClient client = new RedisClient(RedisClient.Endpoint.parse(server.uri()), 1, 2000)) {
                    assertEquals(1L, client.call("EXISTS", lock), "prepare takes the lease");
                    engine.unprepareStores(dimension);
                    assertEquals(0L, client.call("EXISTS", lock), "unprepare releases it");
                }
            } finally {
                redis.shutdown();
            }
        }
    }
}
