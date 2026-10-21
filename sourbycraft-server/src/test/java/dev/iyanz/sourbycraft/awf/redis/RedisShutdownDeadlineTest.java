package dev.iyanz.sourbycraft.awf.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.iyanz.sourbycraft.awf.AwfDeadlineExceeded;
import dev.iyanz.sourbycraft.awf.AwfShutdownBudget;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Audit D6 at the socket: once the AWF shutdown deadline is set on the client, no Redis call can
 * outlast it, a call after it sends nothing, and the error says whether the request may have been
 * applied.
 */
class RedisShutdownDeadlineTest {

    /** Accepts connections and reads requests but never answers, like a Redis frozen with SIGSTOP. */
    private static final class SilentServer implements AutoCloseable {
        final ServerSocket socket;
        final AtomicInteger accepted = new AtomicInteger();
        final List<Socket> sockets = new CopyOnWriteArrayList<>();

        SilentServer() throws IOException {
            this.socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
            final Thread acceptor = new Thread(() -> {
                try {
                    while (true) {
                        final Socket client = this.socket.accept();
                        this.accepted.incrementAndGet();
                        this.sockets.add(client);
                        final Thread reader = new Thread(() -> {
                            try (InputStream in = client.getInputStream()) {
                                while (in.read() >= 0) { /* swallow, never reply */ }
                            } catch (final IOException closed) {
                                // test over
                            }
                        });
                        reader.setDaemon(true);
                        reader.start();
                    }
                } catch (final IOException closed) {
                    // test over
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
        }

        RedisClient client(final int timeoutMillis) {
            return new RedisClient(new RedisClient.Endpoint("127.0.0.1", this.socket.getLocalPort(), false, null, null, 0),
                2, timeoutMillis);
        }

        @Override
        public void close() throws IOException {
            for (final Socket client : this.sockets) client.close();
            this.socket.close();
        }
    }

    @Test
    void aCallAfterTheDeadlineSendsNothing() throws Exception {
        try (SilentServer server = new SilentServer(); RedisClient client = server.client(10_000)) {
            client.deadline(System.nanoTime() - 1);
            final AwfDeadlineExceeded refused = assertThrows(AwfDeadlineExceeded.class, () -> client.call("PING"));
            assertFalse(refused.requestSent());
            assertTrue(client.expired());
            assertEquals(0, server.accepted.get(), "no connection was even opened");
        }
    }

    @Test
    void aReadBlockedOnAFrozenServerIsAbortedAtTheDeadline() throws Exception {
        try (SilentServer server = new SilentServer(); RedisClient client = server.client(10_000)) {
            client.deadline(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300));
            final long started = System.nanoTime();
            final AwfDeadlineExceeded cut = Assertions.assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> assertThrows(AwfDeadlineExceeded.class, () -> client.call("SET", "k", "v")));
            final long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(cut.requestSent(), "SET was written: Redis may still run it");
            assertTrue(millis < 2_000, "timeout-ms is 10 s, the deadline 300 ms: took " + millis + " ms");
        }
    }

    @Test
    void anEarlierDeadlineWinsAndALaterOneDoesNotExtendIt() throws Exception {
        try (SilentServer server = new SilentServer(); RedisClient client = server.client(10_000)) {
            client.deadline(System.nanoTime() - 1);
            client.deadline(System.nanoTime() + TimeUnit.SECONDS.toNanos(60));
            assertThrows(AwfDeadlineExceeded.class, () -> client.call("PING"));
        }
    }

    @Test
    void expiryStopsRenewalAndAbortsCallsInFlight() throws Exception {
        try (SilentServer server = new SilentServer()) {
            final RedisClient client = server.client(10_000);
            final RedisSettings settings = new RedisSettings("redis://127.0.0.1:" + server.socket.getLocalPort() + "/0",
                "d6:", 2, 10_000, 30, false);
            final RedisBackend backend = new RedisBackend(settings, client, "owner", 30_000, 10_000);
            final AwfShutdownBudget budget = new AwfShutdownBudget(60_000, System::nanoTime);
            backend.beginShutdown(budget);
            final Thread blocked = new Thread(() -> {
                try {
                    client.call("PING");
                } catch (final IOException expected) {
                    // aborted
                }
            });
            blocked.start();
            final long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (server.accepted.get() == 0 && System.nanoTime() < until) Thread.onSpinWait();
            budget.fireExpiry();                                  // the deadline passes
            blocked.join(5_000);
            assertFalse(blocked.isAlive(), "the call blocked in a socket read was aborted");
            assertTrue(backend.renewalsStopped());
            assertTrue(client.expired());
            backend.shutdown();
        }
    }
}
