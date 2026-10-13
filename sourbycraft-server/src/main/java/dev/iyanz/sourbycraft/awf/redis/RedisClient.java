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

        public static Endpoint parse(final String text) {
            final URI uri;
            try {
                uri = new URI(text.trim());
            } catch (final java.net.URISyntaxException malformed) {
                throw new IllegalArgumentException("not a Redis URI: " + redact(text));
            }
            final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
            if (!scheme.equals("redis") && !scheme.equals("rediss")) {
                throw new IllegalArgumentException("a Redis URI starts with redis:// or rediss://, not " + redact(text));
            }
            if (uri.getHost() == null) throw new IllegalArgumentException("no host in " + redact(text));
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
                    throw new IllegalArgumentException("database in " + redact(text) + " is not a number");
                }
            }
            return new Endpoint(uri.getHost(), uri.getPort() < 0 ? 6379 : uri.getPort(), scheme.equals("rediss"),
                username, password, database);
        }

        private static String decode(final String part) {
            return URLDecoder.decode(part, StandardCharsets.UTF_8);
        }

        /** The URI with any credentials removed, for logs. */
        public static String redact(final String text) {
            return text == null ? "null" : text.replaceAll("//[^@/]*@", "//***@");
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
    private volatile boolean closed;

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
        if (this.closed) throw new IOException("Redis client is closed");
        try {
            if (!this.permits.tryAcquire(this.timeoutMillis, TimeUnit.MILLISECONDS)) {
                throw new IOException("no free Redis connection within " + this.timeoutMillis + " ms");
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for a Redis connection", interrupted);
        }
        Connection connection = null;
        try {
            connection = this.idle.poll();
            if (connection == null) connection = connect();
            final WriteWatch watch = new WriteWatch(connection, TimeUnit.MILLISECONDS.toNanos(this.timeoutMillis));
            try {
                for (final Object[] command : commands) connection.write(command);
                connection.flush();
            } catch (final IOException failed) {
                if (watch.stalled) {
                    throw new java.net.SocketTimeoutException("Redis accepted no request bytes for " + this.timeoutMillis
                        + " ms; connection closed");
                }
                throw failed;
            } finally {
                watch.done();
            }
            final List<Object> replies = new ArrayList<>(commands.size());
            for (int i = 0; i < commands.size(); i++) replies.add(connection.read());
            if (this.idle.offer(connection)) connection = null;      // else closed below
            return replies;
        } finally {
            if (connection != null) connection.close();    // broken mid-exchange: never reused
            this.permits.release();
        }
    }

    private Connection connect() throws IOException {
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
            socket.connect(new InetSocketAddress(this.endpoint.host(), this.endpoint.port()), this.timeoutMillis);
            socket.setSoTimeout(this.timeoutMillis);
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

    @Override
    public void close() {
        this.closed = true;
        Connection connection;
        while ((connection = this.idle.poll()) != null) connection.close();
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

    /** One socket speaking RESP2. Not thread safe; the pool hands it to one caller at a time. */
    static final class Connection {
        private static final byte[] CRLF = {'\r', '\n'};
        private static final int MAX_BULK = 512 * 1024 * 1024;
        private static final int MAX_DEPTH = 8;
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        /** When the last slice of a write reached the socket; read by {@link WriteWatch}. */
        volatile long progressNanos;

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

        Object read() throws IOException {
            return read(0);
        }

        private Object read(final int depth) throws IOException {
            if (depth > MAX_DEPTH) throw new IOException("Redis reply nested too deeply");
            final int type = this.in.read();
            if (type < 0) throw new EOFException("Redis closed the connection");
            final String line = line();
            return switch (type) {
                case '+' -> line;
                case '-' -> new RedisError(line);
                case ':' -> Long.parseLong(line);
                case '$' -> {
                    final int length = Integer.parseInt(line);
                    if (length < 0) yield null;
                    if (length > MAX_BULK) throw new IOException("Redis bulk reply of " + length + " bytes");
                    final byte[] data = this.in.readNBytes(length);
                    if (data.length != length) throw new EOFException("Redis reply truncated");
                    if (this.in.read() != '\r' || this.in.read() != '\n') throw new IOException("Redis reply framing");
                    yield data;
                }
                case '*' -> {
                    final int count = Integer.parseInt(line);
                    if (count < 0) yield null;
                    final List<Object> items = new ArrayList<>(Math.min(count, 1 << 16));
                    for (int i = 0; i < count; i++) items.add(read(depth + 1));
                    yield items;
                }
                default -> throw new IOException("unexpected Redis reply type '" + (char) type + "'");
            };
        }

        private String line() throws IOException {
            final ByteArrayOutputStream buffer = new ByteArrayOutputStream(32);
            while (true) {
                final int b = this.in.read();
                if (b < 0) throw new EOFException("Redis closed the connection");
                if (b == '\r') {
                    if (this.in.read() != '\n') throw new IOException("Redis reply framing");
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
