package dev.iyanz.sourbycraft.awf.redis;

import static org.junit.jupiter.api.Assertions.*;

import dev.iyanz.sourbycraft.awf.AwfStore;
import dev.iyanz.sourbycraft.awf.WorldRole;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The Redis backend keeps every writable store in {@code leased} (and lost ones in {@code lost}) so a
 * background pass can renew their leases. A store that is closed (its world unloaded), that failed to
 * open, or that is still open at shutdown must leave that set, or the pass renews a lease nobody holds
 * and the map pins the store. Observed through the traffic the backend sends, with an in-memory client:
 * no Redis server is needed.
 */
class RedisLeaseRetentionTest {

    private final AtomicInteger renewalTrips = new AtomicInteger();
    private final AtomicInteger renewals = new AtomicInteger();
    private final AtomicInteger releases = new AtomicInteger();
    private volatile boolean failIndexLoad;
    private RedisBackend backend;

    private RedisBackend newBackend() {
        final RedisClient client = new RedisClient(RedisClient.Endpoint.parse("redis://127.0.0.1:1"), 1, 1000) {
            @Override
            public List<Object> pipeline(final List<Object[]> commands) throws IOException {
                final List<Object> replies = new ArrayList<>();
                boolean renewal = false;
                for (final Object[] command : commands) {
                    final String name = (String) command[0];
                    if ("EVAL".equals(name) && RedisBackend.RENEW_SCRIPT.equals(command[1])) {
                        renewal = true;
                        renewals.incrementAndGet();
                        replies.add(1L);
                    } else if ("EVAL".equals(name) && RedisBackend.ACQUIRE_SCRIPT.equals(command[1])) {
                        replies.add("OK");
                    } else if ("EVAL".equals(name) && RedisBackend.RELEASE_SCRIPT.equals(command[1])) {
                        releases.incrementAndGet();
                        replies.add(1L);
                    } else if ("HSCAN".equals(name)) {
                        if (failIndexLoad) throw new IOException("index unreadable");
                        replies.add(List.of("0".getBytes(), List.of()));
                    } else if ("SMEMBERS".equals(name)) {
                        replies.add(List.of());
                    } else {
                        replies.add(null);
                    }
                }
                if (renewal) renewalTrips.incrementAndGet();
                return replies;
            }
        };
        final RedisBackend created = new RedisBackend(new RedisSettings("redis://127.0.0.1:1", "ret:", 1, 1000, 30, false),
            client, "retention");
        this.backend = created;
        return created;
    }

    @AfterEach
    void stop() {
        if (this.backend != null) this.backend.shutdown();
    }

    private void pass() {
        this.renewals.set(0);
        this.renewalTrips.set(0);
        this.backend.renewAll();
    }

    @Test
    void aClosedStoreIsNoLongerRenewedAndItsLeaseIsReleasedOnce() throws Exception {
        final RedisBackend redis = newBackend();
        final List<AwfStore> stores = new ArrayList<>();
        for (int i = 0; i < 3; i++) stores.add(redis.open("w" + i + "/region", WorldRole.VANILLA, 1));
        pass();
        assertEquals(3, this.renewals.get());

        stores.get(1).close();
        stores.get(1).close(); // a second close is a no-op
        assertEquals(1, this.releases.get(), "one release for one closed store");
        pass();
        assertEquals(2, this.renewals.get(), "the closed world's lease is not renewed any more");

        stores.get(0).close();
        stores.get(2).close();
        pass();
        assertEquals(0, this.renewals.get());
        assertEquals(0, this.renewalTrips.get(), "with nothing leased a pass sends nothing at all");
        assertEquals(3, this.releases.get());
    }

    @Test
    void aStoreWhoseIndexCannotBeLoadedIsNotLeftLeased() {
        final RedisBackend redis = newBackend();
        this.failIndexLoad = true;
        assertThrows(IOException.class, () -> redis.open("broken/region", WorldRole.VANILLA, 1));

        assertEquals(1, this.releases.get(), "the lease taken for the failed open was given back");
        pass();
        assertEquals(0, this.renewals.get(), "and is not renewed");
    }

    @Test
    void shutdownReleasesWhateverIsStillOpenAndEmptiesTheSet() throws Exception {
        final RedisBackend redis = newBackend();
        redis.open("x/region", WorldRole.VANILLA, 1);
        redis.open("y/region", WorldRole.VANILLA, 1);

        redis.shutdown();

        assertEquals(2, this.releases.get());
        this.backend = null;
    }

    @Test
    void readOnlyStoresTakeNoLeaseSoThereIsNothingToReleaseOrRenew() throws Exception {
        final RedisBackend redis = newBackend();
        final AwfStore readOnly = redis.open("t/region", WorldRole.READ_ONLY, 1);
        pass();
        assertEquals(0, this.renewals.get());
        readOnly.close();
        assertEquals(0, this.releases.get());
    }
}
