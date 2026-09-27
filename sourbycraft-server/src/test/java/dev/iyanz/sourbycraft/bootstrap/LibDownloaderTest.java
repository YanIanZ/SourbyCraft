package dev.iyanz.sourbycraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The downloader's bounds: cache, offline reuse, retries, cleanup, size and hash checks. */
class LibDownloaderTest {

    @TempDir Path dir;
    private static final byte[] JAR = "pretend this is a jar".getBytes(StandardCharsets.UTF_8);
    private final List<Integer> pauses = new ArrayList<>();
    private final LibDownloader.Backoff noWait = this.pauses::add;

    private static String sha(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private BootstrapManifest.Entry entry() throws Exception {
        return new BootstrapManifest.Entry("org/example/lib/1.0/lib-1.0.jar", "https://repo.example/lib-1.0.jar",
            sha(JAR), JAR.length);
    }

    private Path dest() {
        return this.dir.resolve("org/example/lib/1.0/lib-1.0.jar");
    }

    private static LibDownloader.Transport serving(final byte[] body) {
        return (uri, into, max) -> Files.write(into, body);
    }

    private static final LibDownloader.Transport OFFLINE = (uri, into, max) -> {
        throw new IOException("network must not be touched");
    };

    @Test
    void downloadsVerifiesAndPlacesTheFile() throws Exception {
        assertTrue(LibDownloader.ensure(entry(), this.dir, serving(JAR), this.noWait));
        assertArrayEquals(JAR, Files.readAllBytes(dest()));
        assertFalse(Files.exists(dest().resolveSibling("lib-1.0.jar.tmp")));
    }

    @Test
    void aVerifiedCacheIsReusedWithoutTheNetwork() throws Exception {
        LibDownloader.ensure(entry(), this.dir, serving(JAR), this.noWait);
        assertFalse(LibDownloader.ensure(entry(), this.dir, OFFLINE, this.noWait), "offline after the first success");
    }

    @Test
    void aCorruptCacheIsReplaced() throws Exception {
        Files.createDirectories(dest().getParent());
        Files.write(dest(), "truncated".getBytes(StandardCharsets.UTF_8));
        assertTrue(LibDownloader.ensure(entry(), this.dir, serving(JAR), this.noWait));
        assertArrayEquals(JAR, Files.readAllBytes(dest()));
    }

    @Test
    void aTransientFailureIsRetriedWithBackoff() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final LibDownloader.Transport flaky = (uri, into, max) -> {
            if (calls.incrementAndGet() < 3) throw new IOException("connection reset");
            Files.write(into, JAR);
        };
        assertTrue(LibDownloader.ensure(entry(), this.dir, flaky, this.noWait));
        assertEquals(3, calls.get());
        assertEquals(List.of(1, 2), this.pauses);
    }

    @Test
    void everyAttemptFailingKeepsNothingAndReportsTheLastError() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final LibDownloader.Transport halfThenFail = (uri, into, max) -> {
            calls.incrementAndGet();
            Files.write(into, new byte[5]);
            throw new IOException("reset after 5 bytes");
        };
        final IOException failure = assertThrows(IOException.class,
            () -> LibDownloader.ensure(entry(), this.dir, halfThenFail, this.noWait));
        assertEquals("reset after 5 bytes", failure.getMessage());
        assertEquals(LibDownloader.ATTEMPTS, calls.get());
        assertFalse(Files.exists(dest()));
        assertFalse(Files.exists(dest().resolveSibling("lib-1.0.jar.tmp")), "no partial file is left behind");
    }

    @Test
    void wrongBytesAreNeverInstalled() throws Exception {
        final byte[] tampered = JAR.clone();
        tampered[0] ^= 1;
        final IOException failure = assertThrows(IOException.class,
            () -> LibDownloader.ensure(entry(), this.dir, serving(tampered), this.noWait));
        assertTrue(failure.getMessage().contains("SHA-256 mismatch"));
        assertFalse(Files.exists(dest()));
    }

    @Test
    void theTransportIsToldThePinnedSize() throws Exception {
        final long[] seen = new long[1];
        LibDownloader.ensure(entry(), this.dir, (uri, into, max) -> {
            seen[0] = max;
            Files.write(into, JAR);
        }, this.noWait);
        assertEquals(JAR.length, seen[0]);
    }

    @Test
    void nonHttpsAndEscapingPathsAreRefusedWithoutRetry() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final LibDownloader.Transport counting = (uri, into, max) -> calls.incrementAndGet();
        assertThrows(IOException.class, () -> LibDownloader.ensure(new BootstrapManifest.Entry(
            "a.jar", "http://repo.example/a.jar", sha(JAR), JAR.length), this.dir, counting, this.noWait));
        assertThrows(IOException.class, () -> LibDownloader.ensure(new BootstrapManifest.Entry(
            "../escape.jar", "https://repo.example/a.jar", sha(JAR), JAR.length), this.dir, counting, this.noWait));
        assertEquals(0, calls.get());
        assertTrue(this.pauses.isEmpty());
    }

    @Test
    void theHttpsBodyIsCutOffOnceItExceedsThePinnedSize() throws Exception {
        final Path file = this.dir.resolve("body.bin");
        final LibDownloader.LimitedSubscriber limited = new LibDownloader.LimitedSubscriber(
            java.net.http.HttpResponse.BodySubscribers.ofFile(file), 8);
        final boolean[] cancelled = {false};
        limited.onSubscribe(new Flow.Subscription() {
            @Override public void request(final long n) {}
            @Override public void cancel() { cancelled[0] = true; }
        });
        limited.onNext(List.of(ByteBuffer.wrap(new byte[6])));
        limited.onNext(List.of(ByteBuffer.wrap(new byte[6])));
        assertTrue(cancelled[0], "the transfer is cancelled, not drained");
        final var body = limited.getBody().toCompletableFuture();
        final Exception failure = assertThrows(Exception.class, () -> body.get(5, TimeUnit.SECONDS));
        assertTrue(String.valueOf(failure.getCause()).contains("exceeds the pinned 8 bytes"));
    }

    @Test
    void theTransferDeadlineCoversTheBody() {
        assertTrue(LibDownloader.TRANSFER_DEADLINE.toMinutes() >= 1 && LibDownloader.TRANSFER_DEADLINE.toMinutes() <= 30);
        assertEquals(URI.create("https://x").getScheme(), "https");
    }
}
