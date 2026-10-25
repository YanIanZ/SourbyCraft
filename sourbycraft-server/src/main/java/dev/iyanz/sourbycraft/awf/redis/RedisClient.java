package dev.iyanz.sourbycraft.awf.redis;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A small, dependency-free Redis client: RESP2 over a pool of blocking sockets.
 *
 * <p>Only what AWF needs: single commands, pipelines, {@code AUTH}, {@code SELECT} and TLS
 * ({@code rediss://}, with host name verification). A connection that sees an I/O error or a
 * protocol error is closed rather than returned to the pool, so a half-read reply can never be
 * read as the answer to the next command. Error replies are values, not broken connections.</p>
 *
 * <p>Every call blocks for at most the configured timeout per socket read, waits at most that
 * long for a free connection, and fails when Redis accepts no bytes of a request for that long:
 * a socket write has no timeout of its own, so a watchdog closes a connection whose write makes no
 * progress (a frozen Redis stops reading once the TCP buffers are full, and a large commit would
 * otherwise block forever, holding the world's commit lock).</p>
 */
public class RedisClient implements AutoCloseable {

    /** An error reply, such as {@code ERR unknown command}. */
    public static final class RedisError extends IOException {
        private static final long serialVersionUID = 1L;

        public RedisError(final String message) {
            super(message);
        }
    }

    /**
     * Where to connect: {@code redis://[user[:password]@]host[:port][/database]}, or
     * {@code rediss://} for TLS. User name and password may be percent-encoded.
     */
    public record Endpoint(String host, int port, boolean tls, String username, String password, int database) {

        public Endpoint {
            Objects.requireNonNull(host, "host");
            if (port < 1 || port > 65535) throw new IllegalArgumentException("port " + port);
            if (database < 0) throw new IllegalArgumentException("database " + database);
        }

        /**
         * Parses a Redis URI. The exception message says what is wrong but never repeats any part
         * of the text: a malformed URI cannot be redacted reliably (an unencoded {@code @} or
         * {@code /} in a password moves the boundaries), so callers may log the message as is.
         */
        public static Endpoint parse(final String text) {
            if (text == null) throw new IllegalArgumentException("no Redis URI");
            final URI uri;
            try {
                uri = new URI(text.trim());
            } catch (final java.net.URISyntaxException malformed) {
                throw new IllegalArgumentException("not a valid URI (percent-encode special characters in the"
                    + " user name and password)");
            }
            final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!scheme.equals("redis") && !scheme.equals("rediss")) {
                throw new IllegalArgumentException("a Redis URI starts with redis:// or rediss://");
            }
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("no host (or a port that is not a number, or an unencoded"
                    + " special character in the user name or password)");
            }
            String username = null;
            String password = null;
            final String info = uri.getRawUserInfo();
            if (info != null && !info.isEmpty()) {
                final int colon = info.indexOf(':');
                if (colon < 0) {
                    password = decode(info);     // redis://secret@host: a password alone, as redis-cli reads it
                } else {
                    username = colon == 0 ? null : decode(info.substring(0, colon));
                    password = decode(info.substring(colon + 1));
                }
            }
            int database = 0;
            final String path = uri.getPath();
            if (path != null && path.length() > 1) {
                try {
                    database = Integer.parseInt(path.substring(1));
                } catch (final NumberFormatException notNumber) {
                    throw new IllegalArgumentException("the database (the path after the host) is not a number");
                }
            }
            return new Endpoint(uri.getHost(), uri.getPort() < 0 ? 6379 : uri.getPort(), scheme.equals("rediss"),
                username, password, database);
        }

        private static String decode(final String part) {
            try {
                return URLDecoder.decode(part, StandardCharsets.UTF_8);
            } catch (final IllegalArgumentException badEscape) {
                // URLDecoder's own message quotes the text around the bad escape: part of the secret.
                throw new IllegalArgumentException("the user name or password has an invalid %-escape");
            }
        }

        /**
         * The URI with any credentials removed, for logs: everything between {@code ://} and the
         * last {@code @} is masked. The last {@code @} anywhere, not the first one after the host,
         * because a password may contain an unencoded {@code @}, {@code /}, {@code ?} or {@code #};
         * a Redis URI has no other place for an {@code @}, so at worst a host is masked too.
         */
        public static String redact(final String text) {
            if (text == null) return "null";
            final int at = text.lastIndexOf('@');
            if (at < 0) return text;
            final int scheme = text.indexOf("://");
            final int start = scheme >= 0 && scheme < at ? scheme + 3 : 0;
            return text.substring(0, start) + "***" + text.substring(at);
        }

        @Override
        public String toString() {
            return (this.tls ? "rediss://" : "redis://") + (this.password != null ? "***@" : "") + this.host + ":"
                + this.port + "/" + this.database;
        }
    }

    /** Closes connections whose writes stall; see the class comment. One daemon thread for all clients. */
    private static final java.util.concurrent.ScheduledExecutorService WATCHDOG =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> {
            final Thread thread = new Thread(task, "SourbyCraft-Storage-redis-watchdog");
            thread.setDaemon(true);
            return thread;
        });

    private final Endpoint endpoint;
    private final int timeoutMillis;
    private final Semaphore permits;
    /** Idle connections; never more than the pool size, since only that many are ever out. */
    private final ArrayBlockingQueue<Connection> idle;
    private volatile Observer observer;
    private volatile boolean closed;
    /** Connections handed to a caller right now, so a deadline or {@link #close()} can abort them. */
    private final java.util.Set<Connection> checkedOut = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** {@link System#nanoTime} deadline for every call once {@link #deadline} was set. */
    private volatile long deadlineNanos;
    private volatile boolean hasDeadline;
    /** Set when the deadline passed: every call fails at once and calls in flight were aborted. */
    private volatile boolean expired;

    public RedisClient(final Endpoint endpoint, final int poolSize, final int timeoutMillis) {
        if (poolSize < 1) throw new IllegalArgumentException("pool size " + poolSize);
        if (timeoutMillis < 1) throw new IllegalArgumentException("timeout " + timeoutMillis);
        this.endpoint = endpoint;
        this.timeoutMillis = timeoutMillis;
        this.permits = new Semaphore(poolSize, true);
        this.idle = new ArrayBlockingQueue<>(poolSize);
    }

    public Endpoint endpoint() {
        return this.endpoint;
    }

    /** The configured socket timeout, before any shutdown deadline cuts it. */
    public int timeoutMillis() {
        return this.timeoutMillis;
    }

    /** Optional per-call instrumentation; the fabric attaches one to its pools. */
    public void observer(final Observer observer) {
        this.observer = observer;
    }

    /** Receives one event per finished call and, separately, per timeout or saturated pool. */
    public interface Observer {
        void call(long nanos, boolean ok);

        default void timeout() {}

        default void poolSaturation() {}
    }

    /** Connections this pool may open at once. */
    public int poolSize() {
        return this.idle.size() + this.idle.remainingCapacity();
    }

    /** Connections currently checked out by callers. */
    public int inUse() {
        return this.checkedOut.size();
    }

    /**
     * Bounds every call from now on by one deadline (the AWF shutdown deadline): each call's
     * connection wait, connect, write-progress and read timeouts are cut to the time left, a call
     * that starts after it fails at once without touching the network, and when it passes every
     * connection in use is closed, so a call blocked in a socket read or write fails immediately.
     * A call stopped by the deadline throws {@link dev.iyanz.sourbycraft.awf.AwfDeadlineExceeded},
     * saying whether its request may have reached Redis.
     *
     * @param deadlineNanos on the {@link System#nanoTime} scale; an earlier deadline set before wins
     */
    public void deadline(final long deadlineNanos) {
        synchronized (this.checkedOut) {
            if (this.hasDeadline && this.deadlineNanos - deadlineNanos <= 0) return;
            this.deadlineNanos = deadlineNanos;
            this.hasDeadline = true;
        }
        WATCHDOG.schedule(this::expireIfDue, Math.max(0L, deadlineNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
    }

    private void expireIfDue() {
        if (this.hasDeadline && System.nanoTime() - this.deadlineNanos >= 0) expire();
    }

    /** The deadline has passed: refuse new calls and abort every connection in use or idle. */
    public void expire() {
        this.expired = true;
        abortAll();
    }

    /** Whether {@link #deadline} passed (or {@link #expire()} was called). */
    public boolean expired() {
        return this.expired;
    }

    private void abortAll() {
        for (final Connection connection : List.copyOf(this.checkedOut)) connection.close();
        Connection connection;
        while ((connection = this.idle.poll()) != null) connection.close();
    }

    /**
     * The timeout for the next step of a call: {@code timeout-ms}, cut to the time left before the
     * deadline. Throws once the deadline has passed.
     */
    private int stepTimeoutMillis(final boolean requestSent) throws dev.iyanz.sourbycraft.awf.AwfDeadlineExceeded {
        if (this.expired) throw deadlinePassed(requestSent, null);
        if (!this.hasDeadline) return this.timeoutMillis;
        final long remaining = this.deadlineNanos - System.nanoTime();
        if (remaining <= 0L) {
            expire();
            throw deadlinePassed(requestSent, null);
        }
        return (int) Math.max(1L, Math.min(this.timeoutMillis, TimeUnit.NANOSECONDS.toMillis(remaining + 999_999L)));
    }

    private static dev.iyanz.sourbycraft.awf.AwfDeadlineExceeded deadlinePassed(final boolean requestSent,
                                                                              final Throwable cause) {
        return new dev.iyanz.sourbycraft.awf.AwfDeadlineExceeded(requestSent
            ? "the AWF shutdown deadline passed while waiting for Redis; the request was sent and may or may not have"
                + " been applied"
            : "refused: the AWF shutdown deadline has passed; nothing was sent to Redis", requestSent, cause);
    }

    /**
     * The timeout a server-side wait (such as {@code WAITAOF}'s) may use now: {@code timeout-ms}, cut
     * to the time left before the deadline.
     *
     * @throws dev.iyanz.sourbycraft.awf.AwfDeadlineExceeded once the deadline has passed
     */
    public int remainingTimeoutMillis() throws dev.iyanz.sourbycraft.awf.AwfDeadlineExceeded {
        return stepTimeoutMillis(false);
    }

    /** Whether a failure happened because the deadline passed (the call was aborted or its timeout was cut). */
    private boolean pastDeadline() {
        return this.expired || (this.hasDeadline && System.nanoTime() - this.deadlineNanos >= 0);
    }

    /** Runs one command. An error reply is thrown as {@link RedisError}. */
    public Object call(final Object... args) throws IOException {
        final Object reply = pipeline(List.<Object[]>of(args)).getFirst();
        if (reply instanceof RedisError error) throw new RedisError(error.getMessage());
        return reply;
    }

    /**
     * Sends every command before reading any reply. Replies come back in order; an error reply is
     * a {@link RedisError} in the list, so one failed command does not hide the others' results.
     */
    public List<Object> pipeline(final List<Object[]> commands) throws IOException {
        final Observer observer = this.observer;
        final long began = System.nanoTime();
        boolean ok = false;
        try {
            final List<Object> replies = pipelineOnce(commands);
            ok = true;
            return replies;
        } catch (final IOException failed) {
            if (observer != null && (failed instanceof dev.iyanz.sourbycraft.awf.AwfDeadlineExceeded
                || failed instanceof java.net.SocketTimeoutException)) {
                observer.timeout();
            }
            throw failed;
        } finally {
            if (observer != null) observer.call(System.nanoTime() - began, ok);
        }
    }

    private List<Object> pipelineOnce(final List<Object[]> commands) throws IOException {
        if (this.closed) throw new IOException("Redis client is closed");
        final int waitMillis = stepTimeoutMillis(false);
        try {
            if (!this.permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS)) {
                if (pastDeadline()) throw deadlinePassed(false, null);
                if (this.observer != null) this.observer.poolSaturation();
                throw new IOException("no free Redis connection within " + waitMillis + " ms");
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for a Redis connection", interrupted);
        }
        Connection connection = null;
        boolean sent = false;
        try {
            // close()/deadline() may have run while this thread waited for a permit; never open a
            // socket after that, or it would be offered to a pool nobody owns any more.
            if (this.closed || this.expired) throw new IOException("Redis client is closed");
            connection = this.idle.poll();
            if (connection == null) connection = connect(stepTimeoutMillis(false));
            this.checkedOut.add(connection);
            if (this.expired) throw deadlinePassed(false, null);     // aborted before it was registered
            final int stepMillis = stepTimeoutMillis(false);
            connection.timeout(stepMillis);
            final WriteWatch watch = new WriteWatch(connection, TimeUnit.MILLISECONDS.toNanos(stepMillis));
            try {
                sent = true;          // from the first byte on, Redis may receive and run the request
                for (final Object[] command : commands) connection.write(command);
                connection.flush();
            } catch (final IOException failed) {
                if (pastDeadline()) throw deadlinePassed(true, failed);
                if (watch.stalled) {
                    throw new java.net.SocketTimeoutException("Redis accepted no request bytes for " + stepMillis
                        + " ms; connection closed");
                }
                throw failed;
            } finally {
                watch.done();
            }
            final List<Object> replies = new ArrayList<>(commands.size());
            try {
                for (int i = 0; i < commands.size(); i++) replies.add(connection.read());
            } catch (final IOException failed) {
                if (pastDeadline()) throw deadlinePassed(true, failed);
                throw failed;
            }
            this.checkedOut.remove(connection);
            if (this.closed || this.expired || !this.idle.offer(connection)) {
                connection.close();
            }
            // Pooled or closed either way: the finally block must not close it a second time, and a
            // pooled connection must not be closed there at all.
            connection = null;
            if (this.expired) abortAll();                              // offered after the abort swept the pool
            return replies;
        } catch (final IOException failed) {
            if (failed instanceof dev.iyanz.sourbycraft.awf.AwfDeadlineExceeded || !pastDeadline()) throw failed;
            throw deadlinePassed(sent, failed);
        } finally {
            if (connection != null) {
                this.checkedOut.remove(connection);
                connection.close();    // broken mid-exchange: never reused
            }
            this.permits.release();
        }
    }

    /** Script SHA-1s, computed once per script text. */
    private static final java.util.concurrent.ConcurrentMap<String, String> SHAS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The SHA-1 Redis names a script by ({@code SCRIPT LOAD}, {@code EVALSHA}): lowercase hex of its UTF-8 bytes. */
    public static String sha1(final String script) {
        return SHAS.computeIfAbsent(script, text -> {
            try {
                return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
            } catch (final java.security.NoSuchAlgorithmException missing) {
                throw new IllegalStateException("the JDK has no SHA-1", missing);
            }
        });
    }

    /** Whether a reply is Redis saying it does not have the script ({@code NOSCRIPT}): the script did not run. */
    static boolean noScript(final Object reply) {
        return reply instanceof RedisError error && error.getMessage() != null && error.getMessage().startsWith("NOSCRIPT");
    }

    /**
     * Runs a script by its SHA-1 ({@code EVALSHA}), so the script text is not sent with every call.
     * When Redis does not have it cached (first use, after a restart or {@code SCRIPT FLUSH}), it is
     * sent once with {@code EVAL}, which also caches it. An error reply is thrown as {@link RedisError}.
     *
     * @param keysAndArgs {@code numkeys}, the keys, then the arguments, as for {@code EVAL}
     */
    public Object eval(final String script, final Object... keysAndArgs) throws IOException {
        final Object reply = evalPipeline(script, List.<Object[]>of(keysAndArgs)).getFirst();
        if (reply instanceof RedisError error) throw new RedisError(error.getMessage());
        return reply;
    }

    /**
     * {@link #eval} for many calls of one script in one pipeline: one round trip while Redis has
     * the script cached; when it does not, the calls Redis refused with {@code NOSCRIPT} (they did
     * not run) are sent again with {@code EVAL} in a second pipeline. Replies are in order; error
     * replies are values, as in {@link #pipeline}.
     */
    public List<Object> evalPipeline(final String script, final List<Object[]> calls) throws IOException {
        final String sha = sha1(script);
        final List<Object[]> bySha = new ArrayList<>(calls.size());
        for (final Object[] call : calls) bySha.add(command("EVALSHA", sha, call));
        final List<Object> replies = new ArrayList<>(pipeline(bySha));
        final List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < replies.size(); i++) {
            if (noScript(replies.get(i))) missing.add(i);
        }
        if (!missing.isEmpty()) {
            final List<Object[]> byText = new ArrayList<>(missing.size());
            for (final int i : missing) byText.add(command("EVAL", script, calls.get(i)));
            final List<Object> retried = pipeline(byText);
            for (int j = 0; j < missing.size(); j++) replies.set(missing.get(j), retried.get(j));
        }
        return replies;
    }

    private static Object[] command(final String name, final String script, final Object[] keysAndArgs) {
        final Object[] command = new Object[keysAndArgs.length + 2];
        command[0] = name;
        command[1] = script;
        System.arraycopy(keysAndArgs, 0, command, 2, keysAndArgs.length);
        return command;
    }

    private Connection connect(final int timeoutMillis) throws IOException {
        final Socket socket;
        if (this.endpoint.tls()) {
            final SSLSocket tls = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            final SSLParameters parameters = tls.getSSLParameters();
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
            tls.setSSLParameters(parameters);
            socket = tls;
        } else {
            socket = new Socket();
        }
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.connect(new InetSocketAddress(this.endpoint.host(), this.endpoint.port()), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            if (socket instanceof SSLSocket tls) tls.startHandshake();
            final Connection connection = new Connection(socket);
            final List<Object[]> hello = new ArrayList<>();
            if (this.endpoint.password() != null) {
                hello.add(this.endpoint.username() == null ? new Object[] {"AUTH", this.endpoint.password()}
                    : new Object[] {"AUTH", this.endpoint.username(), this.endpoint.password()});
            }
            if (this.endpoint.database() != 0) hello.add(new Object[] {"SELECT", Integer.toString(this.endpoint.database())});
            for (final Object[] command : hello) connection.write(command);
            connection.flush();
            for (int i = 0; i < hello.size(); i++) {
                if (connection.read() instanceof RedisError error) {
                    connection.close();
                    // The command, not the reply, would carry the password; the reply never does.
                    throw new RedisError("Redis refused the connection setup: " + error.getMessage());
                }
            }
            return connection;
        } catch (final IOException | RuntimeException failed) {
            try {
                socket.close();
            } catch (final IOException ignored) {
                // Reporting the connect failure matters more.
            }
            throw failed;
        }
    }

    /**
     * Closes the client: idle connections and also the ones in use, whose calls then fail (a client
     * is closed last at shutdown, when no call may keep the process waiting).
     */
    @Override
    public void close() {
        this.closed = true;
        abortAll();
    }

    static byte[] bytes(final Object arg) {
        if (arg instanceof byte[] raw) return raw;
        if (arg instanceof String text) return text.getBytes(StandardCharsets.UTF_8);
        if (arg instanceof Number || arg instanceof Boolean) return arg.toString().getBytes(StandardCharsets.US_ASCII);
        throw new IllegalArgumentException("unsupported Redis argument " + (arg == null ? "null" : arg.getClass()));
    }

    /** Text of a bulk or simple-string reply, or {@code null}. */
    public static String text(final Object reply) {
        if (reply == null) return null;
        if (reply instanceof byte[] raw) return new String(raw, StandardCharsets.UTF_8);
        return reply.toString();
    }

    /**
     * Watches one request's writes: closes the connection when no bytes went out for the timeout.
     * Re-arms itself while writes progress, so a large request over a slow link is not cut off.
     */
    private static final class WriteWatch implements Runnable {
        private final Connection connection;
        private final long timeoutNanos;
        private volatile boolean finished;
        volatile boolean stalled;
        private volatile java.util.concurrent.ScheduledFuture<?> pending;

        WriteWatch(final Connection connection, final long timeoutNanos) {
            this.connection = connection;
            this.timeoutNanos = timeoutNanos;
            connection.progressNanos = System.nanoTime();
            this.pending = WATCHDOG.schedule(this, timeoutNanos, TimeUnit.NANOSECONDS);
        }

        @Override
        public void run() {
            if (this.finished) return;
            final long idle = System.nanoTime() - this.connection.progressNanos;
            if (idle >= this.timeoutNanos) {
                this.stalled = true;
                this.connection.close();
            } else {
                this.pending = WATCHDOG.schedule(this, this.timeoutNanos - idle, TimeUnit.NANOSECONDS);
            }
        }

        void done() {
            this.finished = true;
            this.pending.cancel(false);
        }
    }

    /**
     * One socket speaking RESP2. Not thread safe; the pool hands it to one caller at a time.
     *
     * <p>Replies are bounded, so a broken or hostile server cannot exhaust the heap: one bulk string
     * holds at most the largest chunk AWF stores plus a margin, and one reply (with everything nested
     * in it) at most {@link #MAX_REPLY_ELEMENTS} values and {@link #MAX_REPLY_BYTES} bytes, which
     * still fits {@code HKEYS} over a store of {@code AwfFile.MAX_CHUNKS} chunks. Any framing error,
     * a length or integer that is not a number included, is an {@link IOException}, and the caller
     * closes the connection.</p>
     */
    static final class Connection {
        private static final byte[] CRLF = {'\r', '\n'};
        /** {@code AwfFile.MAX_CHUNK_BYTES} (64 MiB, the largest chunk AWF writes) plus 1 MiB. */
        static final int MAX_BULK = 65 * 1024 * 1024;
        static final long MAX_REPLY_ELEMENTS = 8L * 1024 * 1024;
        static final long MAX_REPLY_BYTES = 256L * 1024 * 1024;
        private static final int MAX_DEPTH = 8;
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        /** When the last slice of a write reached the socket; read by {@link WriteWatch}. */
        volatile long progressNanos;
        /** Values and bytes of the reply being read; reset for every top-level reply. */
        private long replyElements;
        private long replyBytes;

        Connection(final Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
            final OutputStream raw = socket.getOutputStream();
            // Slices, so the watchdog sees progress within one large argument.
            final OutputStream sliced = new OutputStream() {
                @Override
                public void write(final int b) throws IOException {
                    raw.write(b);
                    Connection.this.progressNanos = System.nanoTime();
                }

                @Override
                public void write(final byte[] data, final int offset, final int length) throws IOException {
                    for (int at = offset, end = offset + length; at < end; ) {
                        final int slice = Math.min(64 * 1024, end - at);
                        raw.write(data, at, slice);
                        at += slice;
                        Connection.this.progressNanos = System.nanoTime();
                    }
                }

                @Override
                public void flush() throws IOException {
                    raw.flush();
                }
            };
            this.out = new BufferedOutputStream(sliced, 64 * 1024);
        }

        void write(final Object[] command) throws IOException {
            this.out.write('*');
            this.out.write(Integer.toString(command.length).getBytes(StandardCharsets.US_ASCII));
            this.out.write(CRLF);
            for (final Object arg : command) {
                final byte[] raw = bytes(arg);
                this.out.write('$');
                this.out.write(Integer.toString(raw.length).getBytes(StandardCharsets.US_ASCII));
                this.out.write(CRLF);
                this.out.write(raw);
                this.out.write(CRLF);
            }
        }

        void flush() throws IOException {
            this.out.flush();
        }

        /** Sets the read timeout for the next call. */
        void timeout(final int millis) throws IOException {
            this.socket.setSoTimeout(millis);
        }

        Object read() throws IOException {
            this.replyElements = 0L;
            this.replyBytes = 0L;
            return read(0);
        }

        private Object read(final int depth) throws IOException {
            if (depth > MAX_DEPTH) throw new IOException("Redis reply nested too deeply");
            if (++this.replyElements > MAX_REPLY_ELEMENTS) {
                throw new IOException("Redis reply has more than " + MAX_REPLY_ELEMENTS + " values");
            }
            final int type = this.in.read();
            if (type < 0) throw new EOFException("Redis closed the connection");
            final String line = line();
            return switch (type) {
                case '+' -> line;
                case '-' -> new RedisError(line);
                case ':' -> number(line);
                case '$' -> {
                    final long length = number(line);
                    if (length == -1L) yield null;
                    if (length < 0L) throw new IOException("Redis reply framing: bulk length " + length);
                    if (length > MAX_BULK) throw new IOException("Redis bulk reply of " + length + " bytes");
                    charge(length + 2L);
                    final byte[] data = this.in.readNBytes((int) length);
                    if (data.length != length) throw new EOFException("Redis reply truncated");
                    if (this.in.read() != '\r' || this.in.read() != '\n') throw new IOException("Redis reply framing");
                    yield data;
                }
                case '*' -> {
                    final long count = number(line);
                    if (count == -1L) yield null;
                    if (count < 0L) throw new IOException("Redis reply framing: array length " + count);
                    if (count > MAX_REPLY_ELEMENTS - this.replyElements) {
                        throw new IOException("Redis array reply of " + count + " values");
                    }
                    final List<Object> items = new ArrayList<>((int) Math.min(count, 1 << 16));
                    for (long i = 0; i < count; i++) items.add(read(depth + 1));
                    yield items;
                }
                default -> throw new IOException("unexpected Redis reply type '" + (char) type + "'");
            };
        }

        /** An integer reply or a length; anything else is a framing error, never a runtime exception. */
        private static long number(final String line) throws IOException {
            try {
                return Long.parseLong(line);
            } catch (final NumberFormatException malformed) {
                throw new IOException("Redis reply framing: expected a number");
            }
        }

        /** Counts bytes against {@link #MAX_REPLY_BYTES} before they are read. */
        private void charge(final long bytes) throws IOException {
            this.replyBytes += bytes;
            if (this.replyBytes > MAX_REPLY_BYTES) {
                throw new IOException("Redis reply is larger than " + MAX_REPLY_BYTES + " bytes");
            }
        }

        private String line() throws IOException {
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream(32);
            while (true) {
                final int b = this.in.read();
                if (b < 0) throw new EOFException("Redis closed the connection");
                if (b == '\r') {
                    if (this.in.read() != '\n') throw new IOException("Redis reply framing");
                    charge(buffer.size() + 3L);         // the type byte and CRLF included
                    return buffer.toString(StandardCharsets.UTF_8);
                }
                if (buffer.size() > 64 * 1024) throw new IOException("Redis reply line too long");
                buffer.write(b);
            }
        }

        void close() {
            try {
                this.socket.close();
            } catch (final IOException ignored) {
                // Nothing to recover; the connection is being dropped.
            }
        }
    }
}
