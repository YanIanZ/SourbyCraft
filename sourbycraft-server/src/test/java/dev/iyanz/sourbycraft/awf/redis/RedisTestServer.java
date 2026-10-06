package dev.iyanz.sourbycraft.awf.redis;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;

/**
 * A real {@code redis-server} on a free port in a temporary directory, for tests.
 *
 * <p>When the binary is not installed the calling test is skipped, unless
 * {@code SOURBY_REQUIRE_REDIS=1} is set (CI sets it), in which case it fails: a skipped storage
 * test must never pass silently where it is supposed to run.</p>
 */
public final class RedisTestServer implements AutoCloseable {

    private final Process process;
    private final Path directory;
    public final int port;

    private RedisTestServer(final Process process, final Path directory, final int port) {
        this.process = process;
        this.directory = directory;
        this.port = port;
    }

    static Optional<Path> binary() {
        final List<String> candidates = new ArrayList<>();
        final String path = System.getenv("PATH");
        if (path != null) candidates.addAll(List.of(path.split(java.io.File.pathSeparator)));
        candidates.addAll(List.of("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/usr/sbin"));
        for (final String dir : candidates) {
            final Path candidate = Path.of(dir, "redis-server");
            if (Files.isExecutable(candidate)) return Optional.of(candidate);
        }
        return Optional.empty();
    }

    /** Starts a server with the given extra options, such as {@code --appendonly yes}. */
    public static RedisTestServer start(final String... options) throws Exception {
        final Optional<Path> binary = binary();
        if (binary.isEmpty()) {
            if ("1".equals(System.getenv("SOURBY_REQUIRE_REDIS"))) {
                throw new IllegalStateException("redis-server is required (SOURBY_REQUIRE_REDIS=1) but not installed");
            }
            Assumptions.abort("redis-server is not installed");
        }
        final int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        final Path directory = Files.createTempDirectory("awf-redis");
        final List<String> command = new ArrayList<>(List.of(binary.get().toString(), "--port", Integer.toString(port),
            "--bind", "127.0.0.1", "--dir", directory.toString(), "--save", "", "--appendonly", "no"));
        command.addAll(List.of(options));
        final Process process = new ProcessBuilder(command).redirectErrorStream(true)
            .redirectOutput(directory.resolve("redis.log").toFile()).start();
        final RedisTestServer server = new RedisTestServer(process, directory, port);
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            try (RedisClient client = new RedisClient(new RedisClient.Endpoint("127.0.0.1", port, false, null, null, 0), 1, 500)) {
                client.call("PING");
                return server;
            } catch (final RedisClient.RedisError authRequired) {
                return server;           // NOAUTH: up, and protected as asked
            } catch (final IOException notYet) {
                Thread.sleep(50);
            }
        }
        server.close();
        throw new IllegalStateException("redis-server did not start: " + Files.readString(directory.resolve("redis.log")));
    }

    public String uri() {
        return "redis://127.0.0.1:" + this.port + "/0";
    }

    @Override
    public void close() throws IOException {
        this.process.destroy();
        try {
            if (!this.process.waitFor(5, TimeUnit.SECONDS)) this.process.destroyForcibly();
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        try (var walk = Files.walk(this.directory)) {
            for (final Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
