package dev.iyanz.sourbycraft.bootstrap;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bounded HTTPS fetch used by {@link PluginProvisioner} to download the pinned ViaVersion,
 * ViaBackwards and ProtocolLib jars before Paper's plugin scan. The whole transfer (headers and
 * body) is bounded by {@link #TRANSFER_DEADLINE}, and the body is cut off as soon as it exceeds the
 * caller's pinned size ({@link LimitedSubscriber}), so a stalled or hostile response cannot hang
 * boot or fill the disk. JDK-only: this runs before any external library is on the classpath.
 *
 * <p>The manifest-library downloader this class used to drive (the separate pre-SourbyClip
 * bootstrap entrypoint) is gone: the slim jar's launcher is SourbyClip, which reads
 * {@code META-INF/libraries.list} directly and downloads omitted libraries by coordinate itself.
 * Only the shared {@link Transport} seam survives, now consumed solely by plugin provisioning.
 */
final class LibDownloader {

    static final Duration TRANSFER_DEADLINE = Duration.ofMinutes(10);

    /** Moves bytes from a URI into a file, refusing more than {@code maxBytes}. */
    interface Transport {
        void fetch(URI uri, Path into, long maxBytes) throws IOException;
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    static final Transport HTTPS = LibDownloader::httpsFetch;

    private LibDownloader() {}

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
