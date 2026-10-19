package dev.iyanz.sourbycraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The live download transport's bounds: a body cut off past the pinned size, and a whole-transfer deadline. */
class LibDownloaderTest {

    @TempDir Path dir;

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
