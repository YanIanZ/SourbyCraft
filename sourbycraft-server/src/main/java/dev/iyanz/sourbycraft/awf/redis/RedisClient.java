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
 * <p>Every call blocks for at most the configured timeout per socket read, and waits at most that
 * long for a free connection.</p>
 */
public final class RedisClient implements AutoCloseable {

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
            for (final Object[] command : commands) connection.write(command);
            connection.flush();
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

    /** One socket speaking RESP2. Not thread safe; the pool hands it to one caller at a time. */
    static final class Connection {
        private static final byte[] CRLF = {'\r', '\n'};
        private static final int MAX_BULK = 512 * 1024 * 1024;
        private static final int MAX_DEPTH = 8;
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;

        Connection(final Socket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream(), 64 * 1024);
            this.out = new BufferedOutputStream(socket.getOutputStream(), 64 * 1024);
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
