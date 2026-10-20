package dev.iyanz.sourbycraft.awf.redis;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.awf.AwfStore;
import dev.iyanz.sourbycraft.awf.ChunkKey;
import dev.iyanz.sourbycraft.awf.PersistenceMode;
import dev.iyanz.sourbycraft.awf.WorldRole;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RedisBackendTest {

    private RedisTestServer server;
    private final List<RedisBackend> backends = new java.util.ArrayList<>();

    @AfterEach
    void stop() throws IOException {
        this.backends.forEach(RedisBackend::shutdown);
        if (this.server != null) this.server.close();
    }

    private static byte[] b(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** A backend acting as a server of its own (a distinct lease identity). */
    private RedisBackend backend(final String uri, final String prefix, final boolean aof) {
        return backend(uri, prefix, aof, java.util.UUID.randomUUID().toString());
    }

    private RedisBackend backend(final String uri, final String prefix, final boolean aof, final String serverId) {
        final RedisSettings settings = new RedisSettings(uri, prefix, 4, 2000, 30, aof);
        final RedisBackend backend = new RedisBackend(settings, new RedisClient(RedisClient.Endpoint.parse(uri),
            settings.poolSize(), settings.timeoutMillis()), serverId);
        this.backends.add(backend);
        return backend;
    }

    /**
     * Makes a lease lapse as Redis would on expiry and returns once Redis has really dropped the
     * key. Expiry is the server's clock, so the wait is bounded polling of the server's own answer
     * (a 1 ms TTL is gone by the first read after it), not a guess at how long to sleep.
     */
    private static void expireLease(final RedisBackend backend, final String lockKey)
        throws IOException, InterruptedException {
        backend.client().call("PEXPIRE", lockKey, "1");
        awaitRedis(() -> Long.valueOf(-2L).equals(callPttl(backend, lockKey)), "the lease key to expire");
    }

    private static Long callPttl(final RedisBackend backend, final String lockKey) {
        try {
            return (Long) backend.client().call("PTTL", lockKey);
        } catch (final IOException failed) {
            throw new java.io.UncheckedIOException(failed);
        }
    }

    private static void awaitRedis(final java.util.function.BooleanSupplier condition, final String what) {
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting for " + what);
            Thread.yield();
        }
    }

    private RedisBackend backend() throws Exception {
        if (this.server == null) this.server = RedisTestServer.start();
        return backend(this.server.uri(), "test:", false);
    }

    @Test
    void urisParseAndNeverPrintPasswords() {
        final RedisClient.Endpoint plain = RedisClient.Endpoint.parse("redis://cache.local");
        assertEquals("cache.local", plain.host());
        assertEquals(6379, plain.port());
        assertEquals(0, plain.database());
        final RedisClient.Endpoint full = RedisClient.Endpoint.parse("rediss://awf:p%40ss@10.0.0.2:6380/3");
        assertTrue(full.tls());
        assertEquals("awf", full.username());
        assertEquals("p@ss", full.password());
        assertEquals(3, full.database());
        assertFalse(full.toString().contains("p@ss"));
        assertNull(RedisClient.Endpoint.parse("redis://secret@h").username());
        assertEquals("secret", RedisClient.Endpoint.parse("redis://secret@h").password());
        assertFalse(RedisClient.Endpoint.redact("redis://u:secret@h:1/0").contains("secret"));
        assertThrows(IllegalArgumentException.class, () -> RedisClient.Endpoint.parse("http://h"));
        assertThrows(IllegalArgumentException.class, () -> RedisClient.Endpoint.parse("redis://h/x"));
    }

    @Test
    void settingsFallBackPerKeyAndTakeTheUriFromTheEnvironment() {
        final RedisSettings.Parsed parsed = RedisSettings.parse(Map.of(RedisSettings.POOL_KEY, 0L,
            RedisSettings.PREFIX_KEY, "a{b}"), "redis://env-host:7000");
        assertEquals("redis://env-host:7000", parsed.settings().uri());
        assertEquals(RedisSettings.DEFAULT.poolSize(), parsed.settings().poolSize());
        assertEquals(RedisSettings.DEFAULT.keyPrefix(), parsed.settings().keyPrefix());
        assertEquals(List.of(RedisSettings.PREFIX_KEY, RedisSettings.POOL_KEY), parsed.invalidKeys());
        assertEquals("redis://file-host", RedisSettings.parse(Map.of(RedisSettings.URI_KEY, "redis://file-host"),
            "redis://env-host").settings().uri(), "the config file wins over the environment");
    }

    @Test
    void commitsSurviveAReopenAndOnlyTheIndexIsReadOnOpen() throws Exception {
        final RedisBackend first = backend();
        assertFalse(first.exists("world/region"));
        final AwfStore store = first.open("world/region", WorldRole.VANILLA, 3);
        assertEquals(0, store.generation());
        final Map<ChunkKey, byte[]> changed = new HashMap<>();
        for (int i = 0; i < 500; i++) changed.put(new ChunkKey(i, -i), b("chunk " + i));
        assertEquals(1, store.commit(changed, Set.of(), Set.of(new ChunkKey(9, 9)), PersistenceMode.INCREMENTAL).generation());
        assertEquals(2, store.commit(Map.of(new ChunkKey(1, -1), b("rewritten")), Set.of(new ChunkKey(2, -2)), Set.of(),
            PersistenceMode.INCREMENTAL).generation());
        store.close();
        assertTrue(first.exists("world/region"));

        final AwfStore reopened = backend(this.server.uri(), "test:", false).open("world/region", WorldRole.VANILLA, 3);
        assertEquals(2, reopened.generation());
        assertEquals(499, reopened.keys().size());
        assertArrayEquals(b("rewritten"), reopened.read(new ChunkKey(1, -1)).orElseThrow());
        assertArrayEquals(b("chunk 400"), reopened.read(new ChunkKey(400, -400)).orElseThrow());
        assertTrue(reopened.read(new ChunkKey(2, -2)).isEmpty());
        assertFalse(reopened.has(new ChunkKey(2, -2)), "removed: falls through to the base again");
        assertTrue(reopened.has(new ChunkKey(9, 9)), "deleted: shadows the base");
        assertEquals(Set.of(new ChunkKey(9, 9)), reopened.deleted());
    }

    @Test
    void oneServerWritesAWorldAtATime() throws Exception {
        final RedisBackend first = backend();
        final RedisBackend second = backend(this.server.uri(), "test:", false);
        final AwfStore held = first.open("hub/region", WorldRole.VANILLA, 1);
        final IOException refused = assertThrows(IOException.class, () -> second.open("hub/region", WorldRole.VANILLA, 1));
        assertTrue(refused.getMessage().contains("held by another server"), refused.getMessage());
        assertFalse(second.open("hub/region", WorldRole.READ_ONLY, 1).keys().iterator().hasNext(),
            "reading needs no lease");
        assertThrows(IOException.class, () -> second.delete("hub/region"), "an open world is not deleted");
        held.close();
        second.open("hub/region", WorldRole.VANILLA, 1).close();
    }

    @Test
    void aServerThatLostItsLeaseCannotOverwriteTheNewOwner() throws Exception {
        final RedisBackend first = backend();
        final AwfStore stale = first.open("arena/region", WorldRole.VANILLA, 1);
        final RedisStore probe = new RedisStore(first, "arena/region", WorldRole.READ_ONLY, null);
        // As if this server had stalled past its lease: the lease expires and another server opens the world.
        expireLease(first, probe.lockKey);
        final AwfStore owner = backend(this.server.uri(), "test:", false).open("arena/region", WorldRole.VANILLA, 1);
        owner.commit(Map.of(new ChunkKey(0, 0), b("new owner")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL);

        final IOException refused = assertThrows(IOException.class, () -> stale.commit(Map.of(new ChunkKey(0, 0),
            b("stale")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL));
        assertTrue(refused.getMessage().contains("AWFLEASE"), refused.getMessage());
        assertArrayEquals(b("new owner"), owner.read(new ChunkKey(0, 0)).orElseThrow());
        stale.close();                       // must not release the new owner's lease
        assertThrows(IOException.class, () -> backend(this.server.uri(), "test:", false)
            .open("arena/region", WorldRole.VANILLA, 1));
    }

    @Test
    void theSameServerTakesItsLeaseBackAfterACrashButOthersWait() throws Exception {
        backend();                                   // starts the server
        final RedisBackend crashed = backend(this.server.uri(), "test:", false, "server-a");
        crashed.open("isl/region", WorldRole.VANILLA, 1);            // never closed: the process died
        assertThrows(IOException.class, () -> backend(this.server.uri(), "test:", false, "server-b")
            .open("isl/region", WorldRole.VANILLA, 1), "another server waits for the lease");
        final AwfStore restarted = backend(this.server.uri(), "test:", false, "server-a")
            .open("isl/region", WorldRole.VANILLA, 1);
        restarted.commit(Map.of(new ChunkKey(0, 0), b("after restart")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL);
        assertNotEquals(null, serverIdShape(RedisBackend.serverId()));
    }

    private static String serverIdShape(final String id) {
        assertTrue(id.matches("[0-9a-f]{16}"), id);
        return id;
    }

    @Test
    void aLeaseIsRenewedForAsLongAsTheWorldIsOpen() throws Exception {
        backend();
        // The production backend uses a 5 s minimum lease renewed every 1.67 s (the default of the
        // three-argument constructor, asserted below, and by aRenewalPassOverHalfThePeriodIsSlow).
        // This test injects a 1 s lease renewed every 100 ms: ten renewals per lease, so a renewal
        // thread stalled up to ~900 ms (GC, a loaded CI runner) does not turn into a false failure, and the proof still needs the lease
        // to outlive its own TTL, which only Redis's clock can measure.
        final long leaseMillis = 1_000L;
        final RedisSettings settings = new RedisSettings(this.server.uri(), "renew:", 2, 2000, 5, false);
        final RedisBackend backend = new RedisBackend(settings, new RedisClient(RedisClient.Endpoint.parse(this.server.uri()),
            2, 2000), "renewer", leaseMillis, 100L);
        this.backends.add(backend);
        final RedisBackend production = new RedisBackend(settings, new RedisClient(
            RedisClient.Endpoint.parse(this.server.uri()), 1, 2000), "default-lease");
        this.backends.add(production);
        assertEquals(5_000L, production.leaseMillis(), "the three-argument constructor keeps the production lease");
        final AwfStore store = backend.open("long/region", WorldRole.VANILLA, 1);
        final RedisStore probe = new RedisStore(backend, "long/region", WorldRole.READ_ONLY, null);
        // Past twice the lease: only renewal keeps it. Each poll is a Redis round trip that
        // must see the lease held, so a lapse at any point in the window fails the test.
        final long until = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(2 * leaseMillis);
        while (System.nanoTime() < until) {
            assertTrue((Long) backend.client().call("PTTL", probe.lockKey) > 0, "renewal kept the lease alive");
        }
        // A stall past the lease with nobody taking it: the next renewal takes it back. DEL, not
        // expireLease: with a 100 ms renewal the expired gap can close before a poll sees PTTL -2,
        // and to Redis an expired key and a deleted one are the same absent key.
        backend.client().call("DEL", probe.lockKey);
        awaitRedis(() -> callPttl(backend, probe.lockKey) > 0, "renewal to take the expired lease back");
        assertTrue((Long) backend.client().call("PTTL", probe.lockKey) > 0, "renewal took the expired lease back");
        store.commit(Map.of(new ChunkKey(0, 0), b("still mine")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL);
        store.close();
    }

    @Test
    void aLeaseThatExpiredUnclaimedIsTakenBackOnlyIfNobodyCommittedSince() throws Exception {
        final RedisBackend stalled = backend();
        final AwfStore store = stalled.open("stall/region", WorldRole.VANILLA, 1);
        store.commit(Map.of(new ChunkKey(0, 0), b("before")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL);
        final RedisStore probe = new RedisStore(stalled, "stall/region", WorldRole.READ_ONLY, null);
        expireLease(stalled, probe.lockKey);                       // the process stalled past its lease
        store.commit(Map.of(new ChunkKey(0, 0), b("after the stall")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL);
        assertTrue((Long) stalled.client().call("PTTL", probe.lockKey) > 0, "the lease was taken back");

        expireLease(stalled, probe.lockKey);
        final AwfStore other = backend(this.server.uri(), "test:", false).open("stall/region", WorldRole.VANILLA, 1);
        other.commit(Map.of(new ChunkKey(0, 0), b("other server")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL);
        other.close();                                               // released: no holder, but a newer generation
        final IOException refused = assertThrows(IOException.class, () -> store.commit(Map.of(new ChunkKey(0, 0),
            b("stale")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL));
        assertTrue(refused.getMessage().contains("AWFLEASE"), refused.getMessage());
        assertArrayEquals(b("other server"), backend(this.server.uri(), "test:", false)
            .open("stall/region", WorldRole.READ_ONLY, 1).read(new ChunkKey(0, 0)).orElseThrow());
    }

    /**
     * Backend failure D2 (2026-10-13): Redis applied a commit whose reply never arrived, so the
     * generation moved without the store seeing it; then the lease lapsed. No other server is
     * involved, so the store must get its lease back, by renewal and by its next commit.
     */
    @Test
    void aCommitWhoseReplyWasLostDoesNotFenceTheStoreForever() throws Exception {
        final RedisBackend backend = backend();
        final RedisStore store = (RedisStore) backend.open("lost/region", WorldRole.VANILLA, 1);
        store.commit(Map.of(new ChunkKey(0, 0), b("one")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL);
        // The same script the store runs, applied by Redis while the store saw no reply (a timeout).
        backend.client().call("EVAL", RedisStore.COMMIT_SCRIPT, "5", store.chunksKey, store.deletedKey, store.genKey,
            store.lockKey, store.writerKey, store.token(), "1", "0", "0", Long.toString(store.knownGeneration()),
            "30000", "0,0", "two");
        assertEquals(1, store.knownGeneration());
        expireLease(backend, store.lockKey);                           // Redis was gone longer than the lease

        backend.renewAll();
        assertTrue((Long) backend.client().call("PTTL", store.lockKey) > 0, "renewal took the lease back");
        backend.client().call("DEL", store.lockKey);
        assertEquals(3, store.commit(Map.of(new ChunkKey(0, 0), b("three")), Set.of(), Set.of(),
            PersistenceMode.INCREMENTAL).generation(), "the next commit takes it back too");
        assertArrayEquals(b("three"), store.read(new ChunkKey(0, 0)).orElseThrow());

        // Another server's commit in between still fences it: the last writer is not this store.
        backend.client().call("DEL", store.lockKey);
        final AwfStore other = backend(this.server.uri(), "test:", false).open("lost/region", WorldRole.VANILLA, 1);
        other.commit(Map.of(new ChunkKey(0, 0), b("other")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL);
        other.close();
        backend.renewAll();
        assertEquals(-2L, backend.client().call("PTTL", store.lockKey), "renewal refuses");
        final IOException refused = assertThrows(IOException.class, () -> store.commit(Map.of(new ChunkKey(0, 0),
            b("stale")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL));
        assertTrue(refused.getMessage().contains("AWFLEASE"), refused.getMessage());
    }

    /**
     * Backend failure D3 (2026-10-13): after the lease was lost to another holder and that holder
     * let it go without committing, a commit takes it back; renewal must keep it from then on.
     */
    @Test
    void aLeaseTakenBackByACommitIsRenewedAgain() throws Exception {
        final RedisBackend backend = backend();
        final RedisStore store = (RedisStore) backend.open("retake/region", WorldRole.VANILLA, 1);
        backend.client().call("SET", store.lockKey, "someone-else/1", "PX", "30000");
        backend.renewAll();                                          // lost: refused, but still registered
        assertThrows(IOException.class, () -> store.commit(Map.of(new ChunkKey(0, 0), b("refused")), Set.of(),
            Set.of(), PersistenceMode.INCREMENTAL));
        backend.client().call("DEL", store.lockKey);                // the other holder let go, no commit
        store.commit(Map.of(new ChunkKey(0, 0), b("retaken")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL);
        assertEquals(store.token(), RedisClient.text(backend.client().call("GET", store.lockKey)));

        backend.client().call("PEXPIRE", store.lockKey, "500");     // nearly lapsed, as between commits
        backend.renewAll();
        assertTrue((Long) backend.client().call("PTTL", store.lockKey) > 10_000, "renewed for a full lease again");
        store.close();
        assertEquals(-2L, backend.client().call("PTTL", store.lockKey), "released at close");
    }

    /**
     * Backend failure 2026-10-14 (D7): a frozen Redis stops reading; once the TCP buffers are full a
     * socket write blocks with no timeout of its own. A large commit then hung shutdown for good.
     */
    @Test
    void aWriteThatMakesNoProgressFailsWithinTheTimeout() throws Exception {
        try (java.net.ServerSocket frozen = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            final java.util.List<java.net.Socket> accepted = new java.util.concurrent.CopyOnWriteArrayList<>();
            final Thread acceptor = new Thread(() -> {
                try {
                    accepted.add(frozen.accept());       // accepted, never read: like a SIGSTOPped Redis
                } catch (final IOException closed) {
                    // test over
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            try (RedisClient client = new RedisClient(new RedisClient.Endpoint("127.0.0.1", frozen.getLocalPort(), false,
                null, null, 0), 1, 500)) {
                final long started = System.nanoTime();
                // Preemptive: before the fix this call never returned.
                final IOException failed = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                    java.time.Duration.ofSeconds(20), () -> assertThrows(IOException.class,
                        () -> client.call("HSET", "k", "f", new byte[64 * 1024 * 1024])));
                final long millis = (System.nanoTime() - started) / 1_000_000L;
                assertTrue(millis < 10_000, "failed after " + millis + " ms: " + failed);
                assertTrue(failed.getMessage().contains("accepted no request bytes"), failed.getMessage());
            } finally {
                for (final java.net.Socket socket : accepted) socket.close();
            }
        }
    }

    @Test
    void prefixesKeepServersApart() throws Exception {
        final RedisBackend a = backend();
        final RedisBackend b = backend(this.server.uri(), "other:", false);
        final AwfStore store = a.open("w/region", WorldRole.VANILLA, 1);
        store.commit(Map.of(new ChunkKey(0, 0), b("a")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL);
        assertFalse(b.exists("w/region"));
        b.open("w/region", WorldRole.VANILLA, 1).close();
        store.close();
        a.delete("w/region");
        assertFalse(a.exists("w/region"));
    }

    @Test
    void retireKeepsTheDataUnderANewName() throws Exception {
        final RedisBackend backend = backend();
        final AwfStore store = backend.open("old/region", WorldRole.VANILLA, 1);
        store.commit(Map.of(new ChunkKey(0, 0), b("x")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL);
        store.close();
        assertTrue(backend.retire("old/region").contains("exported-"));
        assertFalse(backend.exists("old/region"));
    }

    @Test
    void commitsWaitForTheAppendOnlyFileWhenRedisHasOne() throws Exception {
        this.server = RedisTestServer.start("--appendonly", "yes", "--appendfsync", "always");
        final RedisBackend backend = backend(this.server.uri(), "aof:", true);
        final AwfStore store = backend.open("w/region", WorldRole.VANILLA, 1);
        store.commit(Map.of(new ChunkKey(0, 0), b("durable")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL);
        // WAITAOF arrived in Redis 7.2; older servers take the logged fallback.
        final String info = RedisClient.text(backend.client().call("INFO", "server"));
        final String[] version = info.replaceAll("(?s).*redis_version:([0-9.]+).*", "$1").split("\\.");
        final boolean waitaof = Integer.parseInt(version[0]) > 7 || Integer.parseInt(version[0]) == 7 && Integer.parseInt(version[1]) >= 2;
        assertTrue(backend.describe().contains(waitaof ? "wait for AOF" : "acknowledged from memory"),
            backend.describe() + " on Redis " + String.join(".", version));
        store.close();
    }

    @Test
    void withoutAnAppendOnlyFileCommitsStillWorkAndSaySo() throws Exception {
        backend();                                   // starts a server without an append-only file
        final RedisBackend backend = backend(this.server.uri(), "noaof:", true);
        final AwfStore store = backend.open("w/region", WorldRole.VANILLA, 1);
        store.commit(Map.of(new ChunkKey(0, 0), b("in memory")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL);
        assertTrue(backend.describe().contains("acknowledged from memory"), backend.describe());
        store.close();
    }

    @Test
    void passwordsAreSentAndWrongOnesRefused() throws Exception {
        this.server = RedisTestServer.start("--requirepass", "s3cret");
        final String good = "redis://s3cret@127.0.0.1:" + this.server.port + "/2";
        final RedisBackend backend = backend(good, "auth:", false);
        backend.checkServer();
        backend.open("w/region", WorldRole.VANILLA, 1).close();
        final RedisBackend wrong = backend("redis://nope@127.0.0.1:" + this.server.port, "auth:", false);
        final IOException refused = assertThrows(IOException.class, wrong::checkServer);
        assertFalse(refused.getMessage().contains("nope"), refused.getMessage());
    }

    @Test
    void readOnlyStoresRefuseCommits() throws Exception {
        final AwfStore store = backend().open("r/region", WorldRole.READ_ONLY, 1);
        assertThrows(IllegalStateException.class, () -> store.commit(Map.of(new ChunkKey(0, 0), b("x")), Set.of(),
            Set.of(), PersistenceMode.INCREMENTAL));
    }

    /** A client that answers from memory and takes {@code delayMillis} per renewal round trip. */
    private static RedisClient delayingClient(final long delayMillis, final java.util.concurrent.atomic.AtomicInteger renewalTrips,
                                              final java.util.concurrent.atomic.AtomicInteger renewals) {
        return new RedisClient(RedisClient.Endpoint.parse("redis://127.0.0.1:1"), 1, 1000) {
            @Override
            public List<Object> pipeline(final List<Object[]> commands) throws IOException {
                final List<Object> replies = new java.util.ArrayList<>();
                boolean renewal = false;
                for (final Object[] command : commands) {
                    final String name = (String) command[0];
                    if (StubScripts.is(command, RedisBackend.RENEW_SCRIPT)) {
                        renewal = true;
                        renewals.incrementAndGet();
                        replies.add(1L);
                    } else if (StubScripts.is(command, RedisBackend.ACQUIRE_SCRIPT)) {
                        replies.add("OK");
                    } else if (StubScripts.isScript(command)) {
                        replies.add(1L);
                    } else if ("HSCAN".equals(name)) {
                        replies.add(List.of(b("0"), List.of()));
                    } else if ("SMEMBERS".equals(name)) {
                        replies.add(List.of());
                    } else {
                        replies.add(null);
                    }
                }
                if (renewal) {
                    renewalTrips.incrementAndGet();
                    try {
                        Thread.sleep(delayMillis);
                    } catch (final InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                return replies;
            }
        };
    }

    @Test
    void leaseRenewalIsOneRoundTripPerPassWhateverTheNumberOfStores() throws Exception {
        final var trips = new java.util.concurrent.atomic.AtomicInteger();
        final var renewals = new java.util.concurrent.atomic.AtomicInteger();
        final RedisSettings settings = new RedisSettings("redis://127.0.0.1:1", "stub:", 1, 1000, 30, false);
        final RedisBackend backend = new RedisBackend(settings, delayingClient(200, trips, renewals), "stub");
        this.backends.add(backend);
        final int stores = 25;
        for (int i = 0; i < stores; i++) backend.open("w" + i + "/region", WorldRole.VANILLA, 1);

        final long elapsed = backend.renewAll();
        assertEquals(1, trips.get(), "one pipelined round trip, not one per store");
        assertEquals(stores, renewals.get(), "every store renewed");
        assertTrue(elapsed < java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200L * stores / 2),
            "serial renewal would have taken " + (200 * stores) + " ms; took "
                + java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(elapsed) + " ms");
    }

    /** {@code calls} of one command in {@code INFO commandstats}, 0 when Redis has not run it since the last reset. */
    private static long commandCalls(final RedisBackend backend, final String command) throws IOException {
        final String stats = RedisClient.text(backend.client().call("INFO", "commandstats"));
        final java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^cmdstat_" + command + ":calls=(\\d+)").matcher(stats);
        return m.find() ? Long.parseLong(m.group(1)) : 0L;
    }

    @Test
    void scriptsRunByTheirShaAndAreSentAsTextOnlyWhenRedisLostThem() throws Exception {
        final RedisBackend backend = backend();
        assertEquals(RedisClient.text(backend.client().call("SCRIPT", "LOAD", RedisBackend.RENEW_SCRIPT)),
            RedisClient.sha1(RedisBackend.RENEW_SCRIPT), "the SHA-1 Redis names the script by");
        final AwfStore store = backend.open("world/region", WorldRole.VANILLA, 3);
        assertEquals(1, store.commit(Map.of(new ChunkKey(0, 0), b("a")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL)
            .generation());
        backend.renewAll();

        backend.client().call("CONFIG", "RESETSTAT");
        assertEquals(2, store.commit(Map.of(new ChunkKey(1, 0), b("b")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL)
            .generation());
        backend.renewAll();
        assertEquals(0L, commandCalls(backend, "eval"), "cached scripts are never sent as text");
        assertEquals(2L, commandCalls(backend, "evalsha"), "one commit, one renewal");

        backend.client().call("SCRIPT", "FLUSH");
        backend.client().call("CONFIG", "RESETSTAT");
        assertEquals(3, store.commit(Map.of(new ChunkKey(2, 0), b("c")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL)
            .generation(), "a commit refused with NOSCRIPT did not run, and runs once by text");
        backend.renewAll();
        assertEquals(4, store.commit(Map.of(new ChunkKey(3, 0), b("d")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL)
            .generation());
        backend.renewAll();
        assertEquals(2L, commandCalls(backend, "eval"), "each script is sent as text once after the flush");
        assertTrue(commandCalls(backend, "evalsha") >= 2L, "the later calls go by SHA again");
        store.close();

        final AwfStore reopened = backend(this.server.uri(), "test:", false).open("world/region", WorldRole.VANILLA, 3);
        assertEquals(4, reopened.generation());
        assertEquals(4, reopened.keys().size());
        assertArrayEquals(b("c"), reopened.read(new ChunkKey(2, 0)).orElseThrow());
    }

    @Test
    void aPipelineOfScriptCallsResendsOnlyTheRefusedOnesByText() throws Exception {
        final List<List<Object[]>> sent = new java.util.ArrayList<>();
        final RedisClient client = new RedisClient(RedisClient.Endpoint.parse("redis://127.0.0.1:1"), 1, 1000) {
            @Override
            public List<Object> pipeline(final List<Object[]> commands) {
                sent.add(commands);
                final List<Object> replies = new java.util.ArrayList<>();
                for (final Object[] command : commands) {
                    final Object value = command[3];
                    replies.add("EVALSHA".equals(command[0]) && "missing".equals(value)
                        ? new RedisClient.RedisError("NOSCRIPT No matching script. Please use EVAL.")
                        : command[0] + ":" + value);
                }
                return replies;
            }
        };
        final String script = "return ARGV[1]";
        final List<Object> replies = client.evalPipeline(script, List.of(
            new Object[] {"0", "missing"}, new Object[] {"0", "b"}, new Object[] {"0", "missing"}));
        assertEquals(List.of("EVAL:missing", "EVALSHA:b", "EVAL:missing"), replies, "in call order");
        assertEquals(2, sent.size(), "one round trip, plus one for the refused calls");
        assertEquals(RedisClient.sha1(script), sent.get(0).get(1)[1]);
        assertEquals(2, sent.get(1).size(), "only the refused calls are resent");
        for (final Object[] resent : sent.get(1)) {
            assertEquals("EVAL", resent[0]);
            assertEquals(script, resent[1]);
        }
        assertEquals("EVALSHA:x", client.eval(script, "0", "x"));
        assertEquals(3, sent.size(), "a cached script is one round trip");
        assertThrows(RedisClient.RedisError.class, () -> new RedisClient(RedisClient.Endpoint.parse("redis://127.0.0.1:1"), 1, 1000) {
            @Override
            public List<Object> pipeline(final List<Object[]> commands) {
                return List.of(new RedisClient.RedisError("ERR boom"));
            }
        }.eval(script, "0"));
    }

    @Test
    void aRenewalPassOverHalfThePeriodIsSlow() {
        final RedisSettings settings = new RedisSettings("redis://127.0.0.1:1", "x:", 1, 1000, 30, false);
        assertEquals(10_000L, RedisBackend.renewalPeriodMillis(settings));
        assertFalse(RedisBackend.slowPass(java.util.concurrent.TimeUnit.SECONDS.toNanos(4), 10_000L));
        assertTrue(RedisBackend.slowPass(java.util.concurrent.TimeUnit.SECONDS.toNanos(6), 10_000L));
    }
}
