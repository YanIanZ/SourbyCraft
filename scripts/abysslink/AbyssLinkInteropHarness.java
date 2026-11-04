import dev.iyanz.sourbycraft.abysslink.AbyssLinkClient;
import dev.iyanz.sourbycraft.abysslink.AbyssLinkSettings;
import world.leviathan.abysslink.AbyssLinkBackendRegistry;
import world.leviathan.abysslink.AbyssLinkConfig;
import world.leviathan.abysslink.AbyssLinkServer;
import world.leviathan.abysslink.ProxyPingProvider;
import java.io.InputStream;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
/**
 * Real-peer AbyssLink harness: {@code AbyssLinkInteropHarness <cert-dir>} holding ca.pem,
 * server.pem, server-key.pem, client.pem and client-key.pem. Prints
 * {@code CHECK <name> <PASS|FAIL> <detail>} and exits non-zero unless all expected checks
 * passed. The real Leviathan server and real SourbyCraft client exchange real mTLS. The
 * deterministic ping provider and always-true entitlement supplier are explicit harness
 * injections, not production certification. A thrown assertion aborts the remaining
 * checks; the Python runner reports absent checks as failed, so an aborted phase can
 * never look like a pass.
 */
public final class AbyssLinkInteropHarness {
    private static final String NODE_MAIN = "backend-01";
    private static final String NODE_WRONG_HOST = "backend-02";
    private static final String NODE_WRONG_PIN = "backend-03";
    private static final String SERVER_IDENTITY = "localhost";
    private static final UUID FOUND_PLAYER = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ABSENT_PLAYER = UUID.fromString("22222222-3333-4444-5555-666666666666");
    private static final UUID STALE_PLAYER = UUID.fromString("33333333-4444-5555-6666-777777777777");
    private static final int FOUND_LATENCY_MS = 42;
    private static final int STALE_LATENCY_MS = 37;
    private static final long STALE_SAMPLE_AGE_MS = 10_000L;
    private static final List<String> EXPECTED = List.of(
        "certificate-material", "auth-hello", "heartbeat-observed", "ping-found",
        "ping-absent", "ping-stale", "reconnect", "main-cleanup",
        "negative-wrong-hostname", "negative-wrong-pin");
    private static final Map<String, Boolean> RESULTS = new LinkedHashMap<>();
    /** Harness injections, not production certification. */
    private static final BooleanSupplier ENTITLEMENT = () -> true;
    private static final BooleanSupplier UNDER_ATTACK = () -> false;

    private record Certs(Path ca, Path serverCert, Path serverKey, Path clientCert, Path clientKey,
                         String clientPin, String serverPin) { }

    public static void main(String[] args) {
        int exit = 2;
        try {
            if (args.length != 1) throw new IllegalArgumentException("usage: cert-dir");
            Certs certs = loadCerts(Path.of(args[0]));
            check("certificate-material", true, "leaf certificates parsed; client pin is SHA-256");
            runMainPhase(certs);
            runNegative(certs, false);
            runNegative(certs, true);
            exit = EXPECTED.stream().allMatch(name -> Boolean.TRUE.equals(RESULTS.get(name))) ? 0 : 1;
        } catch (Throwable failure) {
            System.err.println("harness failure: " + describe(failure));
        }
        System.exit(exit);
    }
    private static Certs loadCerts(Path dir) throws Exception {
        Path ca = dir.resolve("ca.pem"), serverCert = dir.resolve("server.pem");
        Path serverKey = dir.resolve("server-key.pem");
        Path clientCert = dir.resolve("client.pem"), clientKey = dir.resolve("client-key.pem");
        for (Path path : List.of(ca, serverCert, serverKey, clientCert, clientKey)) {
            if (!Files.isRegularFile(path)) throw new IllegalStateException("missing: " + path);
        }
        return new Certs(ca, serverCert, serverKey, clientCert, clientKey,
            leafSha256(clientCert), leafSha256(serverCert));
    }
    private static String leafSha256(Path pem) throws Exception {
        try (InputStream input = Files.newInputStream(pem)) {
            X509Certificate certificate = (X509Certificate) CertificateFactory
                .getInstance("X.509").generateCertificate(input);
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        }
    }

    // Positive phase: actual peer, actual client, real mTLS, reconnect.
    private static void runMainPhase(Certs certs) throws Exception {
        AbyssLinkServer server = null;
        AbyssLinkClient client = null;
        AbyssLinkServer reopened = null;
        try {
            int port = freePort();
            AbyssLinkConfig config = peerConfig(port, certs, NODE_MAIN, certs.clientPin());
            ProxyPingProvider ping = deterministicPing();
            server = startPeer(config, ping, 1);
            client = new AbyssLinkClient(clientSettings(port, NODE_MAIN, SERVER_IDENTITY, certs));
            client.start();
            final AbyssLinkServer authServer = server;
            final AbyssLinkClient authClient = client;
            awaitTrue("authenticated HELLO", 6_000L, () -> {
                AbyssLinkBackendRegistry.Backend peer = backend(authServer, NODE_MAIN);
                return authClient.connected() && peer != null && peer.active()
                    && peer.capabilities().contains("ping")
                    && authClient.capabilities().contains("ping")
                    && authServer.metrics.accepted.sum() >= 1;
            }, () -> status(authClient));
            AbyssLinkBackendRegistry.Backend backend = backend(server, NODE_MAIN);
            check("auth-hello", true, "node=" + backend.node() + " capabilities="
                + String.join(",", backend.capabilities())
                + " peerAccepted=" + server.metrics.accepted.sum());
            final AbyssLinkServer heartbeatServer = server;
            final AbyssLinkClient heartbeatClient = client;
            awaitTrue("peer heartbeat", 8_000L, () -> {
                AbyssLinkBackendRegistry.Backend peer = backend(heartbeatServer, NODE_MAIN);
                return peer != null && peer.lastHeartbeatMs() - peer.connectedAtMs() > 1_000L;
            }, () -> status(heartbeatClient));
            backend = backend(heartbeatServer, NODE_MAIN);
            check("heartbeat-observed", true, "heartbeatAgeMs="
                + (backend.lastHeartbeatMs() - backend.connectedAtMs())
                + " controlRttNanos=" + backend.controlRttNanos());
            pingCheck("ping-found", client, server, FOUND_PLAYER,
                sample -> sample.player().equals(FOUND_PLAYER)
                    && sample.latencyMillis() == FOUND_LATENCY_MS, "expectedLatency=42");
            pingCheck("ping-absent", client, server, ABSENT_PLAYER, null, "expected=empty");
            pingCheck("ping-stale", client, server, STALE_PLAYER, null,
                "staleAgeMs=" + STALE_SAMPLE_AGE_MS + " maxSampleAgeMs="
                    + client.settingsForDiagnostics().maxSampleAgeMillis());
            final AbyssLinkClient reconnectClient = client;
            final AbyssLinkServer oldServer = server;
            AbyssLinkBackendRegistry.Backend before = backend(oldServer, NODE_MAIN);
            if (before == null) throw new IllegalStateException("no backend before reconnect");
            long oldConnectedAtMs = before.connectedAtMs();
            oldServer.close();
            awaitTrue("client observed peer close", 4_000L, () -> !reconnectClient.connected(),
                () -> status(reconnectClient));
            reopened = startPeer(config, ping, 5);
            final AbyssLinkServer reopenedServer = reopened;
            awaitTrue("client re-authenticated", 8_000L, () -> {
                AbyssLinkBackendRegistry.Backend peer = backend(reopenedServer, NODE_MAIN);
                return reconnectClient.connected() && peer != null && peer.active();
            }, () -> status(reconnectClient));
            AbyssLinkBackendRegistry.Backend after = backend(reopened, NODE_MAIN);
            Optional<AbyssLinkClient.Sample> result = resolve(reconnectClient, FOUND_PLAYER);
            boolean ok = result.isPresent()
                && result.get().latencyMillis() == FOUND_LATENCY_MS
                && reconnectClient.metrics.connections.sum() >= 2
                && after.connectedAtMs() >= oldConnectedAtMs;
            check("reconnect", ok, "oldConnectedAtMs=" + oldConnectedAtMs
                + " newConnectedAtMs=" + after.connectedAtMs() + " connections="
                + reconnectClient.metrics.connections.sum());
        } finally {
            closeQuietly(client);
            closeQuietly(server);
            closeQuietly(reopened);
            final AbyssLinkClient cleanup = client;
            if (cleanup == null) throw new IllegalStateException("client was never created");
            awaitTrue("client pending drained", 2_000L, () -> cleanup.pendingCount() == 0,
                () -> "pending=" + cleanup.pendingCount());
            check("main-cleanup", true,
                "pending=" + cleanup.pendingCount() + " connected=" + cleanup.connected());
        }
    }

    // Negative phases: independent peer and client configuration each.
    private static void runNegative(Certs certs, boolean wrongPin) throws Exception {
        String name = wrongPin ? "negative-wrong-pin" : "negative-wrong-hostname";
        String node = wrongPin ? NODE_WRONG_PIN : NODE_WRONG_HOST;
        String identity = wrongPin ? SERVER_IDENTITY : "wrong-host.invalid";
        String pin = !wrongPin ? certs.clientPin()
            : certs.serverPin().equals(certs.clientPin()) ? "0".repeat(64) : certs.serverPin();
        AbyssLinkServer server = null;
        AbyssLinkClient client = null;
        try {
            int port = freePort();
            server = startPeer(peerConfig(port, certs, node, pin), absentPing(), 1);
            client = new AbyssLinkClient(clientSettings(port, node, identity, certs));
            client.start();
            final AbyssLinkServer peer = server;
            final AbyssLinkClient peerClient = client;
            awaitTrue("rejection observed", 2_000L, () -> wrongPin
                ? peer.metrics.rejected.sum() >= 1
                : peerClient.metrics.rejected.sum() >= 1 || peerClient.metrics.reconnects.sum() >= 1,
                () -> status(peerClient));
            boolean ok = !peerClient.connected() && peerClient.pendingCount() == 0
                && peer.backends.size() == 0 && peer.metrics.accepted.sum() == 0;
            check(name, ok, "clientRejected=" + peerClient.metrics.rejected.sum()
                + " clientConnections=" + peerClient.metrics.connections.sum()
                + " peerRejected=" + peer.metrics.rejected.sum()
                + " peerBackends=" + peer.backends.size()
                + " peerAccepted=" + peer.metrics.accepted.sum());
        } finally {
            closeQuietly(client);
            closeQuietly(server);
        }
    }
    private static AbyssLinkConfig peerConfig(int port, Certs certs, String node, String pin)
        throws Exception {
        return new AbyssLinkConfig(true,
            new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), false,
            certs.serverCert(), certs.serverKey(), certs.ca(), Map.of(node, pin),
            400, 5_000, 8, 16, 65_536, 1_500, 200, 131_072, "disconnect", true);
    }
    private static AbyssLinkSettings clientSettings(int port, String node, String identity,
                                                    Certs certs) {
        return new AbyssLinkSettings(true, "127.0.0.1", port, node, identity,
            certs.ca().toString(), certs.clientCert().toString(), certs.clientKey().toString(),
            100, 500, 1_000, 1_500, 0, 4, 1_000, true, 2_000);
    }
    private static ProxyPingProvider deterministicPing() {
        return player -> {
            long now = System.currentTimeMillis();
            boolean found = FOUND_PLAYER.equals(player);
            if (found || STALE_PLAYER.equals(player)) {
                long sampledAt = found ? now : now - STALE_SAMPLE_AGE_MS;
                int latency = found ? FOUND_LATENCY_MS : STALE_LATENCY_MS;
                return CompletableFuture.completedFuture(
                    new ProxyPingProvider.Sample(player, latency, sampledAt, true));
            }
            return CompletableFuture.completedFuture(ProxyPingProvider.Sample.absent(player));
        };
    }
    private static ProxyPingProvider absentPing() {
        return player -> CompletableFuture.completedFuture(ProxyPingProvider.Sample.absent(player));
    }
    private static void pingCheck(String name, AbyssLinkClient client, AbyssLinkServer server,
                                  UUID player, Predicate<AbyssLinkClient.Sample> expected,
                                  String extra) throws Exception {
        long before = client.metrics.timeouts.sum();
        Optional<AbyssLinkClient.Sample> result = resolve(client, player);
        long after = client.metrics.timeouts.sum();
        boolean matched = expected == null ? result.isEmpty() : result.filter(expected).isPresent();
        check(name, client.connected() && after == before && matched,
            "result=" + result.map(s -> "latency=" + s.latencyMillis()).orElse("empty")
                + "; timeoutsDelta=" + (after - before) + "; " + extra + "; " + status(client));
    }
    private static AbyssLinkServer startPeer(AbyssLinkConfig config, ProxyPingProvider ping,
                                             int attempts) throws Exception {
        Throwable last = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            AbyssLinkServer server = new AbyssLinkServer(config, ping, ENTITLEMENT, UNDER_ATTACK);
            try {
                server.start().toCompletableFuture().get(5L, TimeUnit.SECONDS);
                return server;
            } catch (Throwable failure) {
                last = failure;
                server.close();
                Thread.sleep(200L);
            }
        }
        throw new IllegalStateException("peer start failed: " + describe(last));
    }
    private static Optional<AbyssLinkClient.Sample> resolve(AbyssLinkClient client, UUID player)
        throws Exception {
        return client.resolve(player).get(3L, TimeUnit.SECONDS);
    }
    private static void awaitTrue(String what, long timeoutMs, BooleanSupplier condition,
                                  Supplier<String> status) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline)
                throw new IllegalStateException(what + " not observed within " + timeoutMs
                    + "ms; " + status.get());
            Thread.sleep(20L);
        }
    }
    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }
    private static AbyssLinkBackendRegistry.Backend backend(AbyssLinkServer server, String node) {
        return server == null ? null : server.backends.snapshot().stream()
            .filter(backend -> node.equals(backend.node())).findFirst().orElse(null);
    }
    private static String status(AbyssLinkClient client) {
        return "connected=" + client.connected() + ", pending=" + client.pendingCount()
            + ", connections=" + client.metrics.connections.sum()
            + ", rejected=" + client.metrics.rejected.sum();
    }    private static void closeQuietly(AutoCloseable closeable) {
        try {
            if (closeable != null) closeable.close();
        } catch (Exception ignored) {
            // Cleanup must not mask the check result.
        }
    }
    private static synchronized void check(String name, boolean pass, String detail) {
        RESULTS.put(name, pass);
        System.out.println("CHECK " + name + " " + (pass ? "PASS" : "FAIL") + " "
            + oneLine(detail == null ? "" : detail));
    }
    private static String describe(Throwable failure) {
        Throwable root = failure;
        while (root != null && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root == null ? null : root.getMessage();
        return oneLine(root == null ? "unknown" : root.getClass().getSimpleName()
            + (message == null || message.isBlank() ? "" : ": " + message));
    }
    private static String oneLine(String text) {
        String value = text.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ');
        return value.length() > 2_000 ? value.substring(0, 2_000) + "..." : value;
    }
}
