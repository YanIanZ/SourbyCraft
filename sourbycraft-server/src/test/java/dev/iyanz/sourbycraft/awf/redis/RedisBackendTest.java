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
        first.client().call("PEXPIRE", probe.lockKey, "1");
        Thread.sleep(20);
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
        final RedisSettings settings = new RedisSettings(this.server.uri(), "renew:", 2, 2000, 5, false);
        final RedisBackend backend = new RedisBackend(settings, new RedisClient(RedisClient.Endpoint.parse(this.server.uri()),
            2, 2000), "renewer");
        this.backends.add(backend);
        final AwfStore store = backend.open("long/region", WorldRole.VANILLA, 1);
        Thread.sleep(8000);                       // past the 5 s lease: only renewal keeps it
        // A stall past the lease with nobody taking it: the next renewal takes it back.
        final RedisStore probe = new RedisStore(backend, "long/region", WorldRole.READ_ONLY, null);
        backend.client().call("PEXPIRE", probe.lockKey, "1");
        Thread.sleep(2500);
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
        stalled.client().call("PEXPIRE", probe.lockKey, "1");      // the process stalled past its lease
        Thread.sleep(20);
        store.commit(Map.of(new ChunkKey(0, 0), b("after the stall")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL);
        assertTrue((Long) stalled.client().call("PTTL", probe.lockKey) > 0, "the lease was taken back");

        stalled.client().call("PEXPIRE", probe.lockKey, "1");
        Thread.sleep(20);
        final AwfStore other = backend(this.server.uri(), "test:", false).open("stall/region", WorldRole.VANILLA, 1);
        other.commit(Map.of(new ChunkKey(0, 0), b("other server")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL);
        other.close();                                               // released: no holder, but a newer generation
        final IOException refused = assertThrows(IOException.class, () -> store.commit(Map.of(new ChunkKey(0, 0),
            b("stale")), Set.of(), Set.of(), PersistenceMode.INCREMENTAL));
        assertTrue(refused.getMessage().contains("AWFLEASE"), refused.getMessage());
        assertArrayEquals(b("other server"), backend(this.server.uri(), "test:", false)
            .open("stall/region", WorldRole.READ_ONLY, 1).read(new ChunkKey(0, 0)).orElseThrow());
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
}
