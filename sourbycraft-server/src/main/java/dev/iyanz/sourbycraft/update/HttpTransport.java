package dev.iyanz.sourbycraft.update;

import org.jetbrains.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The one network seam of the updater: a GET that yields status, headers and a body stream.
 *
 * <p>{@link SourbyUpdater} talks to GitHub only through this interface so the conditional-request
 * (ETag), rate-limit (Retry-After) and bounded-download paths can be exercised in a unit test with
 * scripted responses instead of a live API. {@link #jdk()} is the production implementation on
 * {@link HttpURLConnection}, unchanged in behaviour from the pre-seam code (10 s connect, 15 s read,
 * redirects followed).
 */
interface HttpTransport {

    /** Perform one GET. Never returns {@code null}; a transport failure is an {@link IOException}. */
    Response get(String url, Map<String, String> headers) throws IOException;

    /** One HTTP response. Closing it releases the body stream. */
    record Response(int status, Map<String, List<String>> headers, @Nullable InputStream body) implements Closeable {

        /** First value of {@code name}, matched case-insensitively; {@code null} when absent. */
        public @Nullable String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)
                        && e.getValue() != null && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }

        /** The advertised {@code Content-Length}, or {@code -1} when absent or unparseable. */
        public long contentLength() {
            String raw = header("Content-Length");
            if (raw == null) return -1L;
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException e) {
                return -1L;
            }
        }

        /** The body, or an empty stream when the server sent none. */
        public InputStream bodyOrEmpty() {
            return body == null ? InputStream.nullInputStream() : body;
        }

        @Override
        public void close() throws IOException {
            if (body != null) body.close();
        }
    }

    /** Production transport on {@link HttpURLConnection}. */
    static HttpTransport jdk() {
        return (url, headers) -> {
            HttpURLConnection conn;
            try {
                conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            } catch (IllegalArgumentException e) {
                throw new IOException("bad url: " + url, e);
            }
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("User-Agent", "SourbyCraft-AutoUpdate");
            for (Map.Entry<String, String> h : headers.entrySet()) {
                conn.setRequestProperty(h.getKey(), h.getValue());
            }
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(15_000);
            conn.setInstanceFollowRedirects(true);
            int code = conn.getResponseCode();
            InputStream body;
            try {
                body = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            } catch (IOException noBody) {
                body = null;
            }
            // getHeaderFields() keys the status line under null; drop it, keep the rest.
            Map<String, List<String>> fields = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
                if (e.getKey() != null) fields.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
            return new Response(code, fields, body);
        };
    }
}
