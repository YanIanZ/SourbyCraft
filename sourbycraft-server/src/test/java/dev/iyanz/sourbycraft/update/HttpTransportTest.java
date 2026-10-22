package dev.iyanz.sourbycraft.update;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpTransport} against a local {@link HttpServer} on 127.0.0.1.
 *
 * <p>Design facts these tests pin: the production transport does NOT enforce https (the updater's
 * URLs are https constants or come from the GitHub listing, and the jar is SHA-256 verified);
 * it does not bound sizes itself either: it reports the advertised {@code Content-Length} and
 * hands back a stream, and {@link SourbyUpdater#copyBounded} / {@code readBounded} apply the limit.
 * Redirects are followed by {@link java.net.HttpURLConnection}, which never switches protocol.
 */
class HttpTransportTest {

    HttpServer server;
    String base;
    final Map<String, String> seen = new ConcurrentHashMap<>();
    final AtomicInteger loopHits = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "http-transport-test");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/ok", ex -> {
            seen.put("accept", String.valueOf(ex.getRequestHeaders().getFirst("Accept")));
            seen.put("user-agent", String.valueOf(ex.getRequestHeaders().getFirst("User-Agent")));
            seen.put("if-none-match", String.valueOf(ex.getRequestHeaders().getFirst("If-None-Match")));
            ex.getResponseHeaders().add("ETag", "W/\"abc\"");
            ex.getResponseHeaders().add("X-Multi", "one");
            ex.getResponseHeaders().add("X-Multi", "two");
            send(ex, 200, "hello");
        });
        server.createContext("/missing", ex -> send(ex, 404, "nope"));
        server.createContext("/limited", ex -> {
            ex.getResponseHeaders().add("Retry-After", "42");
            send(ex, 429, "slow down");
        });
        server.createContext("/not-modified", ex -> {
            ex.sendResponseHeaders(304, -1);
            ex.close();
        });
        server.createContext("/redirect", ex -> redirect(ex, "/ok"));
        server.createContext("/redirect-twice", ex -> redirect(ex, "/redirect"));
        server.createContext("/loop", ex -> {
            loopHits.incrementAndGet();
            redirect(ex, "/loop");
        });
        server.createContext("/to-https", ex -> redirect(ex, "https://127.0.0.1:1/never"));
        server.createContext("/big", ex -> {
            // Content-Length is honest: 100 000 zero bytes.
            ex.sendResponseHeaders(200, 100_000);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(new byte[100_000]);
            }
        });
        server.createContext("/endless", ex -> {
            // Chunked, no Content-Length; keeps writing until the client goes away.
            ex.sendResponseHeaders(200, 0);
            try (OutputStream out = ex.getResponseBody()) {
                byte[] chunk = new byte[64 * 1024];
                for (int i = 0; i < 1024; i++) out.write(chunk); // up to 64 MiB
            } catch (IOException clientGone) {
                // expected once the bounded reader gives up
            }
        });
        server.createContext("/slow", ex -> {
            try {
                Thread.sleep(3_000);
                send(ex, 200, "late");
            } catch (InterruptedException | IOException ignored) {
                ex.close();
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void send(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private static String text(HttpTransport.Response r) throws IOException {
        return new String(r.bodyOrEmpty().readAllBytes(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ plain responses

    @Test
    void successReturnsStatusBodyAndLowerCasedHeadersWithoutTheStatusLine() throws IOException {
        try (HttpTransport.Response r = HttpTransport.jdk().get(base + "/ok", Map.of())) {
            assertEquals(200, r.status());
            assertEquals("hello", text(r));
            assertEquals("W/\"abc\"", r.header("etag"));
            assertEquals("W/\"abc\"", r.header("ETag"), "lookup is case-insensitive");
            assertEquals(5, r.contentLength());
            assertEquals(List.of("one", "two"), r.headers().get("x-multi"));
            assertTrue(r.headers().keySet().stream().allMatch(k -> k.equals(k.toLowerCase(java.util.Locale.ROOT))),
                "keys are lower-cased and the null status-line key is dropped: " + r.headers().keySet());
        }
    }

    @Test
    void defaultRequestHeadersAreTheGitHubAcceptAndTheSourbyUserAgent() throws IOException {
        try (HttpTransport.Response r = HttpTransport.jdk().get(base + "/ok", Map.of())) {
            assertEquals(200, r.status());
        }
        assertEquals("application/vnd.github+json", seen.get("accept"));
        assertEquals("SourbyCraft-AutoUpdate", seen.get("user-agent"));
        assertEquals("null", seen.get("if-none-match"));
    }

    @Test
    void callerHeadersReplaceTheDefaultsAndPassThrough() throws IOException {
        try (HttpTransport.Response r = HttpTransport.jdk().get(base + "/ok",
                Map.of("Accept", "application/octet-stream", "If-None-Match", "W/\"abc\""))) {
            assertEquals(200, r.status());
        }
        assertEquals("application/octet-stream", seen.get("accept"));
        assertEquals("W/\"abc\"", seen.get("if-none-match"));
    }

    @Test
    void errorStatusesStillYieldAResponseWithTheErrorBodyInsteadOfThrowing() throws IOException {
        try (HttpTransport.Response r = HttpTransport.jdk().get(base + "/missing", Map.of())) {
            assertEquals(404, r.status());
            assertEquals("nope", text(r));
        }
        try (HttpTransport.Response r = HttpTransport.jdk().get(base + "/limited", Map.of())) {
            assertEquals(429, r.status());
            assertEquals("42", r.header("Retry-After"));
        }
    }

    @Test
    void a304HasNoBodyAndBodyOrEmptyIsStillSafe() throws IOException {
        try (HttpTransport.Response r = HttpTransport.jdk().get(base + "/not-modified", Map.of())) {
            assertEquals(304, r.status());
            assertEquals("", text(r));
        }
    }

    // ------------------------------------------------------------------ redirects

    @Test
    void sameProtocolRedirectsAreFollowed() throws IOException {
        try (HttpTransport.Response r = HttpTransport.jdk().get(base + "/redirect-twice", Map.of())) {
            assertEquals(200, r.status());
            assertEquals("hello", text(r));
        }
    }

    @Test
    void aRedirectLoopEndsInAnIOExceptionNotAHang() {
        IOException e = assertThrows(IOException.class, () -> HttpTransport.jdk().get(base + "/loop", Map.of()));
        assertTrue(e.getMessage().toLowerCase(java.util.Locale.ROOT).contains("redirect"), e.getMessage());
        assertTrue(loopHits.get() > 1 && loopHits.get() <= 25,
            "the JDK bounds the chain (20 hops); saw " + loopHits.get());
    }

    @Test
    void aRedirectToAnotherProtocolIsNotFollowedSoNoSchemeDowngradeOrSwitchHappens() throws IOException {
        // http -> https is refused by HttpURLConnection (so is https -> http, the downgrade that matters).
        try (HttpTransport.Response r = HttpTransport.jdk().get(base + "/to-https", Map.of())) {
            assertEquals(302, r.status());
            assertEquals("https://127.0.0.1:1/never", r.header("Location"));
        }
    }

    // ------------------------------------------------------------------ size: reported, bounded by the caller

    @Test
    void contentLengthIsReportedSoTheCallerCanRefuseBeforeReading() throws IOException {
        try (HttpTransport.Response r = HttpTransport.jdk().get(base + "/big", Map.of())) {
            assertEquals(100_000, r.contentLength());
        }
    }

    @Test
    void anOversizeBodyThroughTheRealTransportAbortsAtTheBound(@TempDir Path dir) throws IOException {
        try (HttpTransport.Response r = HttpTransport.jdk().get(base + "/big", Map.of())) {
            IOException e = assertThrows(IOException.class,
                () -> SourbyUpdater.copyBounded(r.bodyOrEmpty(), dir.resolve("big.part"), 1_000));
            assertTrue(e.getMessage().contains("1000-byte bound"), e.getMessage());
        }
    }

    @Test
    void anEndlessChunkedBodyWithNoContentLengthIsStillCutOffAtTheBound(@TempDir Path dir) throws IOException {
        try (HttpTransport.Response r = HttpTransport.jdk().get(base + "/endless", Map.of())) {
            assertEquals(-1, r.contentLength(), "chunked: no advertised length");
            IOException e = assertThrows(IOException.class,
                () -> SourbyUpdater.copyBounded(r.bodyOrEmpty(), dir.resolve("endless.part"), 256 * 1024));
            assertTrue(e.getMessage().contains("bound"), e.getMessage());
            assertTrue(java.nio.file.Files.size(dir.resolve("endless.part")) <= 256 * 1024,
                "never writes more than the bound to disk");
        }
        assertThrows(IOException.class, () -> {
            try (HttpTransport.Response r = HttpTransport.jdk().get(base + "/endless", Map.of())) {
                SourbyUpdater.readBounded(r.bodyOrEmpty(), 128 * 1024);
            }
        });
    }

    // ------------------------------------------------------------------ timeouts and connect failures

    @Test
    void aServerThatNeverAnswersTripsTheReadTimeoutAsAnIOException() {
        HttpTransport shortFuse = HttpTransport.jdk(1_000, 300);
        long start = System.nanoTime();
        assertThrows(SocketTimeoutException.class, () -> shortFuse.get(base + "/slow", Map.of()));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 2_500, "timed out in " + elapsedMs + " ms, well before the 3 s the server sleeps");
    }

    @Test
    void connectionRefusedIsAnIOException() throws IOException {
        int closedPort;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            closedPort = s.getLocalPort();
        }
        assertThrows(IOException.class,
            () -> HttpTransport.jdk(1_000, 1_000).get("http://127.0.0.1:" + closedPort + "/x", Map.of()));
    }

    // ------------------------------------------------------------------ URL validation

    @Test
    void aMalformedUrlIsAnIOExceptionNamingTheUrl() {
        IOException e = assertThrows(IOException.class, () -> HttpTransport.jdk().get("not a url", Map.of()));
        assertTrue(e.getMessage().contains("bad url"), e.getMessage());
        assertThrows(IOException.class, () -> HttpTransport.jdk().get("http://[::1", Map.of()));
    }

    @Test
    void nonHttpSchemesAreRefusedAsIOExceptionNotAClassCastException() {
        // The transport contract is "a transport failure is an IOException". file:/jar:/ftp: URLs
        // do not open an HttpURLConnection; they must be a clean refusal, not a ClassCastException.
        for (String url : List.of("file:///etc/hosts", "ftp://127.0.0.1/x", "jar:file:/nonexistent.jar!/x")) {
            IOException e = assertThrows(IOException.class, () -> HttpTransport.jdk().get(url, Map.of()), url);
            assertTrue(e.getMessage().contains("unsupported"), url + " -> " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ Response record

    @Test
    void responseHeaderLookupToleratesNullKeysAndEmptyValueLists() {
        Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
        headers.put(null, List.of("HTTP/1.1 200 OK"));
        headers.put("Empty", List.of());
        headers.put("Content-Length", List.of(" 12 "));
        headers.put("X-First", List.of("a", "b"));
        HttpTransport.Response r = new HttpTransport.Response(200, headers, null);
        assertNull(r.header("Empty"));
        assertNull(r.header("absent"));
        assertEquals("a", r.header("x-first"));
        assertEquals(12, r.contentLength(), "whitespace around the number is tolerated");
    }

    @Test
    void contentLengthIsMinusOneWhenAbsentOrUnparseable() {
        assertEquals(-1, new HttpTransport.Response(200, Map.of(), null).contentLength());
        assertEquals(-1, new HttpTransport.Response(200, Map.of("Content-Length", List.of("many")), null).contentLength());
        assertEquals(-1, new HttpTransport.Response(200, Map.of("Content-Length", List.of("")), null).contentLength());
    }

    @Test
    void bodyOrEmptyAndCloseHandleANullBody() throws IOException {
        HttpTransport.Response r = new HttpTransport.Response(204, Map.of(), null);
        assertEquals(0, r.bodyOrEmpty().readAllBytes().length);
        assertDoesNotThrow(r::close);
    }

    @Test
    void closeReleasesTheBodyStream() throws IOException {
        boolean[] closed = {false};
        ByteArrayInputStream body = new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)) {
            @Override public void close() throws IOException {
                closed[0] = true;
                super.close();
            }
        };
        try (HttpTransport.Response r = new HttpTransport.Response(200, Map.of(), body)) {
            assertArrayEquals(new byte[] {'x'}, r.bodyOrEmpty().readAllBytes());
        }
        assertTrue(closed[0]);
    }
}
