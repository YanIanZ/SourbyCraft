package dev.iyanz.sourbycraft.awf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.awf.redis.RedisBackend;
import dev.iyanz.sourbycraft.awf.redis.RedisSettings;
import dev.iyanz.sourbycraft.awf.redis.RedisTestServer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Audit D6: one AWF shutdown deadline ({@code aurora.awf.shutdown-deadline-ms}) for every AWF wait
 * at shutdown. A lock held by a running commit, or a socket a frozen Redis never answers, cannot
 * extend it; what missed it is reported by storage with exact counts and is never reported as
 * saved; what was confirmed before it is kept.
 */
class AwfShutdownDeadlineTest {

    @TempDir Path dir;
    private final List<CountDownLatch> gates = new ArrayList<>();
    private final List<ExecutorService> lanes = new ArrayList<>();

    @AfterEach
    void releaseGates() {
        this.gates.forEach(CountDownLatch::countDown);
        this.lanes.forEach(ExecutorService::shutdownNow);
        AwfRegionStorage.resetFailFast();
    }

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private Path region(final String world) throws IOException {
        final Path folder = this.dir.resolve(world).resolve("region");
        Files.createDirectories(folder);
        return folder;
    }

    private static AwfSettings settings(final Set<String> worlds, final int deadlineMillis) {
        return new AwfSettings(worlds, Set.of(), "file", PersistenceMode.INCREMENTAL, 30, 1024, 2, 3, false, 0,
            deadlineMillis);
    }

    /** A FILE store whose commits wait on a gate (when gated) and ignore interrupts, like a hung disk. */
    private final class GatedStore implements AwfStore {
        final AwfStore real;
        final boolean gated;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean closed;
        volatile ChunkKey unreadable;

        GatedStore(final AwfStore real, final boolean gated) {
            this.real = real;
            this.gated = gated;
            AwfShutdownDeadlineTest.this.gates.add(this.release);
        }

        @Override public Optional<byte[]> read(final ChunkKey key) throws IOException {
            if (key.equals(this.unreadable)) throw new IOException("unreadable for the test");
            return this.real.read(key);
        }
        @Override public Set<ChunkKey> keys() { return this.real.keys(); }
        @Override public boolean has(final ChunkKey key) { return key.equals(this.unreadable) || this.real.has(key); }
        @Override public Set<ChunkKey> deleted() { return this.real.deleted(); }
        @Override public long generation() throws IOException { return this.real.generation(); }

        @Override
        public CommitResult commit(final Map<ChunkKey, byte[]> changed, final Set<ChunkKey> removed,
                                   final Set<ChunkKey> deleted, final PersistenceMode mode) throws IOException {
            if (this.gated) {
                this.entered.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        if (this.release.await(30, TimeUnit.SECONDS)) break;
                    } catch (final InterruptedException ignored) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            return this.real.commit(changed, removed, deleted, mode);
        }

        @Override
        public void close() throws IOException {
            this.closed = true;
            this.real.close();
        }
    }

    /** An engine on the FILE backend whose stores are wrapped; {@code gated} names the worlds whose commits hang. */
    private AwfEngine engine(final Set<String> worlds, final Set<String> gated, final int deadlineMillis,
                             final java.util.concurrent.Executor lane, final Map<String, GatedStore> opened) {
        final AwfBackend file = AwfBackend.FILE;
        final AwfBackend wrapping = new AwfBackend() {
            @Override public String name() { return "file"; }
            @Override public boolean exists(final String id) throws IOException { return file.exists(id); }
            @Override public String retire(final String id) throws IOException { return file.retire(id); }

            @Override
            public AwfStore open(final String id, final WorldRole role, final int retained) throws IOException {
                final String world = Path.of(id).getParent().getFileName().toString();
                final GatedStore store = new GatedStore(file.open(id, role, retained), gated.contains(world));
                opened.put(world, store);
                return store;
            }
        };
        final Function<String, AwfBackend> backends = name -> wrapping;
        return new AwfEngine(settings(worlds, deadlineMillis), lane, System::nanoTime, backends);
    }

    private ExecutorService lane() {
        final ExecutorService lane = Executors.newSingleThreadExecutor(task -> {
            final Thread thread = new Thread(task, "SourbyCraft-Storage-test-lane");
            thread.setDaemon(true);
            return thread;
        });
        this.lanes.add(lane);
        return lane;
    }

    @Test
    void oneBudgetIsSharedAndABlockedCommitLockCannotExtendIt() throws Exception {
        final Map<String, GatedStore> stores = new java.util.concurrent.ConcurrentHashMap<>();
        final AwfEngine engine = engine(Set.of("ok", "hung", "late1", "late2"), Set.of("hung"), 400, lane(), stores);
        final AwfRegionStorage ok = engine.open(region("ok"));
        final AwfRegionStorage hung = engine.open(region("hung"));
        final AwfRegionStorage late1 = engine.open(region("late1"));
        final AwfRegionStorage late2 = engine.open(region("late2"));
        ok.write(0, 0, b("kept"));
        hung.write(1, 1, b("h"));
        late1.write(2, 2, b("l1"));
        late2.write(3, 3, b("l2"));
        final CompletableFuture<AwfRegionStorage.FlushResult> hanging = hung.flushBarrier(); // a commit, now hanging in the store
        assertTrue(stores.get("hung").entered.await(10, TimeUnit.SECONDS));

        final AwfShutdownBudget budget = engine.beginShutdownBudget();
        assertEquals(400, budget.totalMillis());
        ok.close();                                                // well within the budget: confirmed
        final long started = System.nanoTime();
        hung.close();                                              // waits for the commit lock, but only until the deadline
        final long hungMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        late1.close();
        late2.close();
        final int unconfirmed = engine.shutdownAll();
        final long totalMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(budget.expired());
        assertTrue(totalMillis < 400 + 600, "the stores after the hung one got no budget of their own: " + totalMillis + " ms");
        assertTrue(hungMillis >= 150, "the lock wait used the remaining budget: " + hungMillis + " ms");
        assertEquals(3, unconfirmed);
        final Map<String, AwfShutdownReport.StorageResult> byWorld = byWorld(engine.shutdownReport().results());
        assertEquals(AwfShutdownReport.Outcome.CONFIRMED, byWorld.get("ok").outcome());
        assertEquals(AwfShutdownReport.Outcome.UNCERTAIN, byWorld.get("hung").outcome());
        assertFalse(byWorld.get("hung").released(), "never released under a running commit");
        assertFalse(stores.get("hung").closed);
        assertEquals(AwfShutdownReport.Outcome.REFUSED, byWorld.get("late1").outcome());
        assertEquals(AwfShutdownReport.Outcome.REFUSED, byWorld.get("late2").outcome());
        assertEquals(1, byWorld.get("late1").dirty());
        assertTrue(stores.get("late1").closed, "a store with no commit running is still closed");

        final String alert = engine.shutdownReport().alert(400, 999).orElseThrow();
        assertTrue(alert.startsWith(AwfShutdownReport.INCOMPLETE_MARKER + ":"), alert);
        assertTrue(alert.contains("Not confirmed: 3 storage(s), 3 dirty chunk position(s), 0 queued commit(s) fenced,"
            + " 0 refused write(s), 1 uncertain commit(s) [" + hung.name() + "]"), alert);
        assertFalse(alert.contains(ok.name() + ":"), "a confirmed storage is not listed");

        // Confirmed data is kept: a fresh engine reads what the ok world committed before the deadline.
        final AwfEngine reopened = new AwfEngine(settings(Set.of("ok"), 30_000), Runnable::run, System::nanoTime);
        assertArrayEquals(b("kept"), reopened.open(region("ok")).read(0, 0));
        stores.get("hung").release.countDown();                    // let the hung commit finish before cleanup
        hanging.get(10, TimeUnit.SECONDS);
    }

    @Test
    void theAlertHasExactCountsForAStorageCutOffByTheDeadline() throws Exception {
        final Map<String, GatedStore> stores = new java.util.concurrent.ConcurrentHashMap<>();
        final ExecutorService lane = lane();
        final AwfEngine engine = engine(Set.of("w"), Set.of("w"), 300, lane, stores);
        final AwfRegionStorage storage = engine.open(region("w"));
        stores.get("w").unreadable = new ChunkKey(9, 9);
        // A failed read fences the position; the regenerated chunk's write is refused.
        onRegionThread(() -> assertThrows(IOException.class, () -> storage.read(9, 9)));
        storage.write(9, 9, b("regenerated"));
        storage.write(1, 0, b("a"));
        storage.write(2, 0, b("b"));
        storage.write(3, 0, b("c"));
        final AwfWorld world = worldOf(storage);
        final CompletableFuture<AwfStore.CommitResult> running = world.save(PersistenceMode.INCREMENTAL, lane, 1).toCompletableFuture();
        assertTrue(stores.get("w").entered.await(10, TimeUnit.SECONDS));
        final CompletableFuture<AwfStore.CommitResult> queued1 = world.save(PersistenceMode.INCREMENTAL, lane, 1).toCompletableFuture();
        final CompletableFuture<AwfStore.CommitResult> queued2 = world.save(PersistenceMode.INCREMENTAL, lane, 1).toCompletableFuture();

        engine.beginShutdownBudget();
        assertEquals(1, engine.shutdownAll());

        final AwfShutdownReport.StorageResult result = engine.shutdownReport().results().getFirst();
        assertEquals(AwfShutdownReport.Outcome.UNCERTAIN, result.outcome());
        assertEquals(3, result.dirty());
        assertEquals(List.of(new ChunkKey(1, 0), new ChunkKey(2, 0), new ChunkKey(3, 0)), result.dirtySample());
        assertEquals(2, result.queuedCommits());
        assertEquals(1, result.refusedWrites());
        assertFalse(result.released());
        final String line = result.describe();
        assertTrue(line.contains("3 unconfirmed dirty position(s) [1,0 2,0 3,0], 2 queued commit(s) fenced, 1 refused write(s)"), line);
        assertTrue(line.contains("may or may not have applied it"), line);
        assertFalse(line.contains("lost"), "an uncertain commit is never called lost: " + line);

        // The deadline fenced the world: once the hung commit returns, the queued ones commit nothing.
        stores.get("w").release.countDown();
        running.get(10, TimeUnit.SECONDS);
        for (final CompletableFuture<AwfStore.CommitResult> queued : List.of(queued1, queued2)) {
            final ExecutionException refused = assertThrows(ExecutionException.class, () -> queued.get(10, TimeUnit.SECONDS));
            assertInstanceOf(AwfWorld.FencedException.class, refused.getCause());
        }
    }

    @Test
    void aCommitCutOffAfterItWasSentIsUncertainAndOneNeverSentIsRefused() throws Exception {
        for (final boolean sent : new boolean[] {true, false}) {
            final AwfStore cut = new AwfStore() {
                @Override public Optional<byte[]> read(final ChunkKey key) { return Optional.empty(); }
                @Override public Set<ChunkKey> keys() { return Set.of(); }
                @Override public boolean has(final ChunkKey key) { return false; }
                @Override public Set<ChunkKey> deleted() { return Set.of(); }
                @Override public long generation() { return 0; }
                @Override
                public CommitResult commit(final Map<ChunkKey, byte[]> changed, final Set<ChunkKey> removed,
                                           final Set<ChunkKey> deleted, final PersistenceMode mode) throws IOException {
                    throw new AwfDeadlineExceeded("cut by the test", sent);
                }
            };
            final List<AwfShutdownReport.StorageResult> results = new ArrayList<>();
            final AwfShutdownBudget budget = new AwfShutdownBudget(10_000, System::nanoTime);
            final AwfWorld world = new AwfWorld("w", WorldRole.VANILLA, null, cut);
            final AwfRegionStorage storage = new AwfRegionStorage("w", world, settings(Set.of(), 10_000), Runnable::run,
                0L, () -> { }, () -> budget, results::add);
            storage.write(0, 0, b("x"));
            storage.write(0, 1, b("y"));
            storage.close();
            final AwfShutdownReport.StorageResult result = results.getFirst();
            assertEquals(sent ? AwfShutdownReport.Outcome.UNCERTAIN : AwfShutdownReport.Outcome.REFUSED, result.outcome());
            assertEquals(sent ? 2 : 0, result.uncertainChunks(), "one attempt only: a cut commit is not retried");
            assertEquals(2, result.dirty());
        }
    }

    @Test
    void anExpiredBudgetStartsNoAttemptAndNeverReportsSuccess() throws Exception {
        final java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong();
        final AwfShutdownBudget budget = new AwfShutdownBudget(100, now::get);
        final java.util.concurrent.atomic.AtomicInteger commits = new java.util.concurrent.atomic.AtomicInteger();
        final java.util.concurrent.atomic.AtomicInteger expiries = new java.util.concurrent.atomic.AtomicInteger();
        budget.onExpiry(expiries::incrementAndGet);
        final AwfStore counting = new AwfStore() {
            @Override public Optional<byte[]> read(final ChunkKey key) { return Optional.empty(); }
            @Override public Set<ChunkKey> keys() { return Set.of(); }
            @Override public boolean has(final ChunkKey key) { return false; }
            @Override public Set<ChunkKey> deleted() { return Set.of(); }
            @Override public long generation() { return 0; }
            @Override
            public CommitResult commit(final Map<ChunkKey, byte[]> changed, final Set<ChunkKey> removed,
                                       final Set<ChunkKey> deleted, final PersistenceMode mode) {
                commits.incrementAndGet();
                return new CommitResult(1, changed.size(), changed.size(), 1, 0);
            }
        };
        final List<AwfShutdownReport.StorageResult> results = new ArrayList<>();
        final AwfRegionStorage storage = new AwfRegionStorage("w", new AwfWorld("w", WorldRole.VANILLA, null, counting),
            settings(Set.of(), 100), Runnable::run, 0L, () -> { }, () -> budget, results::add);
        storage.write(0, 0, b("x"));
        now.set(TimeUnit.MILLISECONDS.toNanos(100));
        assertFalse(storage.maintain(Long.MAX_VALUE / 2), "no periodic commit after the deadline");
        storage.flush();                                           // returns, attempts nothing
        storage.close();
        assertEquals(0, commits.get());
        assertEquals(1, expiries.get(), "expiry actions run once");
        assertEquals(AwfShutdownReport.Outcome.REFUSED, results.getFirst().outcome());
        assertEquals(1, results.getFirst().dirty());
    }

    @Test
    void aConfirmedShutdownReturnsZeroAndKeepsEveryCommit() throws Exception {
        final Map<String, GatedStore> stores = new java.util.concurrent.ConcurrentHashMap<>();
        final AwfEngine engine = engine(Set.of("a", "b"), Set.of(), 30_000, Runnable::run, stores);
        engine.open(region("a")).write(0, 0, b("a"));
        engine.open(region("b")).write(0, 0, b("b"));
        assertEquals(0, engine.shutdownAll());
        assertEquals(2, engine.shutdownReport().results().size());
        assertTrue(engine.shutdownReport().unconfirmed().isEmpty());
        assertTrue(engine.shutdownReport().alert(30_000, 1).isEmpty());
        assertTrue(engine.storages().isEmpty());
        assertArrayEquals(b("a"), new AwfEngine(settings(Set.of("a"), 30_000), Runnable::run, System::nanoTime)
            .open(region("a")).read(0, 0));
    }

    @Test
    void theDeadlineSettingIsReadAndValidated() {
        assertEquals(30_000, AwfSettings.DEFAULT.shutdownDeadlineMillis());
        assertTrue(AwfSettings.KEYS.contains(AwfSettings.SHUTDOWN_DEADLINE_KEY));
        assertEquals(5_000, AwfSettings.parse(Map.of(AwfSettings.SHUTDOWN_DEADLINE_KEY, 5_000)).settings()
            .shutdownDeadlineMillis());
        for (final Object wrong : List.of(0, -1, 1.5, "30000", 3_000_000_000L)) {
            final AwfSettings.Parsed parsed = AwfSettings.parse(Map.of(AwfSettings.SHUTDOWN_DEADLINE_KEY, wrong));
            assertEquals(List.of(AwfSettings.SHUTDOWN_DEADLINE_KEY), parsed.invalidKeys(), String.valueOf(wrong));
            assertEquals(30_000, parsed.settings().shutdownDeadlineMillis());
        }
    }

    /**
     * The live case: a real redis-server frozen with SIGSTOP during shutdown. Before D6 this took
     * stores x (commit-attempts + 1) x timeout-ms (here 3 x 4 x 2 s = 24 s); now it ends at the
     * deadline. What was committed before the freeze is still in Redis after it resumes.
     */
    @Test
    void aFrozenRedisCannotExtendTheBudget() throws Exception {
        try (RedisTestServer server = RedisTestServer.start()) {
            final RedisBackend redis = new RedisBackend(new RedisSettings(server.uri(), "d6:", 4, 2000, 30, false));
            try {
                final AwfEngine engine = new AwfEngine(
                    new AwfSettings(Set.of("r1", "r2", "r3"), Set.of(), "redis", PersistenceMode.INCREMENTAL, 30, 1024, 2,
                        3, false, 0, 1500), Runnable::run, System::nanoTime, name -> redis);
                final AwfRegionStorage r1 = engine.open(region("r1"));
                final AwfRegionStorage r2 = engine.open(region("r2"));
                final AwfRegionStorage r3 = engine.open(region("r3"));
                r1.write(0, 0, b("confirmed"));
                r1.flush();                                        // committed before the freeze
                r1.write(1, 1, b("frozen1"));
                r2.write(2, 2, b("frozen2"));
                r3.write(3, 3, b("frozen3"));
                server.freeze();
                engine.beginShutdownBudget();
                final long started = System.nanoTime();
                r1.close();
                r2.close();
                r3.close();
                final int unconfirmed = engine.shutdownAll();
                final long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertTrue(millis < 1500 + 1000, "shutdown took " + millis + " ms with a 1500 ms deadline");
                assertEquals(3, unconfirmed);
                final Map<String, AwfShutdownReport.StorageResult> byWorld = byWorld(engine.shutdownReport().results());
                assertEquals(AwfShutdownReport.Outcome.UNCERTAIN, byWorld.get("r1").outcome(), byWorld.toString());
                assertEquals(AwfShutdownReport.Outcome.REFUSED, byWorld.get("r2").outcome());
                assertEquals(AwfShutdownReport.Outcome.REFUSED, byWorld.get("r3").outcome());
                assertEquals(List.of(new ChunkKey(2, 2)), byWorld.get("r2").dirtySample());

                server.thaw();
                final RedisBackend check = new RedisBackend(new RedisSettings(server.uri(), "d6:", 1, 2000, 30, false));
                try {
                    final AwfStore one = check.open(AwfEngine.storageId(region("r1")), WorldRole.READ_ONLY, 1);
                    assertArrayEquals(b("confirmed"), one.read(new ChunkKey(0, 0)).orElseThrow());
                    final AwfStore two = check.open(AwfEngine.storageId(region("r2")), WorldRole.READ_ONLY, 1);
                    assertFalse(two.has(new ChunkKey(2, 2)), "a refused commit never reached Redis");
                    assertEquals(0L, two.generation());
                } finally {
                    check.shutdown();
                }
            } finally {
                redis.shutdown();
            }
        }
    }

    private static Map<String, AwfShutdownReport.StorageResult> byWorld(final List<AwfShutdownReport.StorageResult> results) {
        final Map<String, AwfShutdownReport.StorageResult> byWorld = new java.util.HashMap<>();
        for (final AwfShutdownReport.StorageResult result : results) {
            byWorld.put(Path.of(result.storage()).getParent().getFileName().toString(), result);
        }
        return byWorld;
    }

    private static AwfWorld worldOf(final AwfRegionStorage storage) throws Exception {
        final java.lang.reflect.Field field = AwfRegionStorage.class.getDeclaredField("world");
        field.setAccessible(true);
        return (AwfWorld) field.get(storage);
    }

    private static void onRegionThread(final Runnable task) throws Exception {
        final java.util.concurrent.FutureTask<Void> future = new java.util.concurrent.FutureTask<>(task, null);
        final Thread thread = new Thread(future, "Folia Region Scheduler Thread #9");
        thread.start();
        thread.join();
        future.get();
    }
}
