package dev.iyanz.sourbycraft.bootstrap;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fetches a single {@link BootstrapManifest.Entry} into the paperclip {@code libraries/} directory,
 * verifying it against its pinned SHA-256 before (and, on a cache hit, without re-downloading)
 * trusting it. Used by {@link SourbyBootstrap} to materialize every library the slim jar omits.
 *
 * <p>Bounds, each covered by {@code LibDownloaderTest}:</p>
 * <ul>
 *   <li>Connect: 15 s. Whole transfer, headers and body: {@link #TRANSFER_DEADLINE}; a body that
 *       stalls after the headers is cancelled at the deadline rather than hanging boot.</li>
 *   <li>Size: the body is cut off as soon as it exceeds the pinned size, so a wrong or hostile
 *       server cannot fill the disk.</li>
 *   <li>Attempts: {@link #ATTEMPTS} per library with 2 s then 4 s between them. A refused URL
 *       (not https, outside {@code libraries/}) is not retried.</li>
 *   <li>Concurrency: one download at a time; {@link SourbyBootstrap} walks the manifest in order.</li>
 *   <li>Cache: an existing file is used only if its SHA-256 matches, and then no network is
 *       touched, which is what makes every boot after the first run offline.</li>
 *   <li>Partial files: a failed attempt deletes its {@code .tmp}; the destination is replaced only
 *       by a verified file.</li>
 * </ul>
 */
final class LibDownloader {

    static final Duration TRANSFER_DEADLINE = Duration.ofMinutes(10);
    static final int ATTEMPTS = 3;

    /** Moves bytes from a URI into a file, refusing more than {@code maxBytes}. */
    interface Transport {
        void fetch(URI uri, Path into, long maxBytes) throws IOException;
    }

    /** Waits between attempts; replaceable so tests do not sleep. */
    interface Backoff {
        void pause(int attempt) throws IOException;
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    static final Transport HTTPS = LibDownloader::httpsFetch;

    static final Backoff SLEEP = attempt -> {
        try {
            Thread.sleep(2_000L << (attempt - 1));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting to retry", e);
        }
    };

    private LibDownloader() {}

    /**
     * Downloads an entry into {@code librariesDir} at its declared paperclipPath.
     * Returns false on cache-hit (existing file matches SHA-256), true after a fresh download.
     * Throws IOException when every attempt failed.
     */
    static boolean ensure(BootstrapManifest.Entry entry, Path librariesDir) throws IOException {
        return ensure(entry, librariesDir, HTTPS, SLEEP);
    }

    static boolean ensure(final BootstrapManifest.Entry entry, final Path librariesDir, final Transport transport,
                          final Backoff backoff) throws IOException {
        Path librariesRoot = librariesDir.toAbsolutePath().normalize();
        Path dest = librariesRoot.resolve(entry.paperclipPath()).normalize();
        if (!dest.startsWith(librariesRoot) || dest.equals(librariesRoot)) {
            throw new IOException("Refusing library write outside libraries dir: " + dest);
        }
        if (Files.exists(dest) && Sha256Verifier.matches(dest, entry.sha256())) {
            return false;
        }
        URI uri = URI.create(entry.downloadUrl());
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new IOException("Refusing non-https library download: " + entry.downloadUrl());
        }
        Files.createDirectories(dest.getParent());
        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                attempt(entry, uri, dest, transport);
                return true;
            } catch (final IOException failed) {
                last = failed;
                if (attempt < ATTEMPTS) {
                    System.err.println("[SourbyBootstrap] attempt " + attempt + "/" + ATTEMPTS + " for "
                        + entry.paperclipPath() + " failed (" + failed.getMessage() + "); retrying");
                    backoff.pause(attempt);
                }
            }
        }
        throw last;
    }

    private static void attempt(final BootstrapManifest.Entry entry, final URI uri, final Path dest,
                                final Transport transport) throws IOException {
        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        try {
            transport.fetch(uri, tmp, entry.sizeBytes());
            if (Files.size(tmp) != entry.sizeBytes()) {
                throw new IOException("size mismatch for " + entry.paperclipPath()
                    + ": got " + Files.size(tmp) + ", expected " + entry.sizeBytes());
            }
            String actual = Sha256Verifier.ofFile(tmp);
            if (!entry.sha256().equalsIgnoreCase(actual)) {
                throw new IOException("SHA-256 mismatch for " + entry.paperclipPath()
                    + ": got " + actual + ", expected " + entry.sha256());
            }
            // Prefer an atomic move, but some filesystems (overlay/container/network mounts) reject
            // ATOMIC_MOVE even within the same directory. Fall back to a plain replace so boot
            // proceeds with the verified file.
            try {
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException atomicUnsupported) {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // Nothing half-written survives an attempt, whatever failed.
            Files.deleteIfExists(tmp);
        }
    }

    private static void httpsFetch(final URI uri, final Path into, final long maxBytes) throws IOException {
        final HttpRequest req = HttpRequest.newBuilder(uri).timeout(TRANSFER_DEADLINE).GET().build();
        final CompletableFuture<HttpResponse<Path>> pending = HTTP.sendAsync(req, info -> info.statusCode() == 200
            ? new LimitedSubscriber(HttpResponse.BodySubscribers.ofFile(into), maxBytes)
            : HttpResponse.BodySubscribers.replacing(into));
        final HttpResponse<Path> resp;
        try {
            resp = pending.get(TRANSFER_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        } catch (final TimeoutException stalled) {
            pending.cancel(true);
            throw new IOException("no complete response from " + uri + " within " + TRANSFER_DEADLINE.toMinutes() + " min");
        } catch (final InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during download of " + uri, e);
        } catch (final ExecutionException failed) {
            final Throwable cause = failed.getCause();
            throw cause instanceof IOException io ? io : new IOException(String.valueOf(cause), cause);
        }
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " for " + uri);
        }
    }

    /** Passes the body to a file subscriber until it exceeds {@code maxBytes}, then aborts. */
    static final class LimitedSubscriber implements HttpResponse.BodySubscriber<Path> {
        private final HttpResponse.BodySubscriber<Path> delegate;
        private final long maxBytes;
        private final CompletableFuture<Path> result = new CompletableFuture<>();
        private Flow.Subscription subscription;
        private long received;

        LimitedSubscriber(final HttpResponse.BodySubscriber<Path> delegate, final long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
            delegate.getBody().whenComplete((path, failure) -> {
                if (failure != null) this.result.completeExceptionally(failure);
                else this.result.complete(path);
            });
        }

        @Override
        public CompletionStage<Path> getBody() {
            return this.result;
        }

        @Override
        public void onSubscribe(final Flow.Subscription subscription) {
            this.subscription = subscription;
            this.delegate.onSubscribe(subscription);
        }

        @Override
        public void onNext(final List<ByteBuffer> item) {
            for (final ByteBuffer buffer : item) this.received += buffer.remaining();
            if (this.received > this.maxBytes) {
                this.subscription.cancel();
                final IOException tooLarge = new IOException("body exceeds the pinned " + this.maxBytes + " bytes");
                this.delegate.onError(tooLarge);
                this.result.completeExceptionally(tooLarge);
                return;
            }
            this.delegate.onNext(item);
        }

        @Override
        public void onError(final Throwable throwable) {
            this.delegate.onError(throwable);
        }

        @Override
        public void onComplete() {
            if (!this.result.isDone()) this.delegate.onComplete();
        }
    }
}
