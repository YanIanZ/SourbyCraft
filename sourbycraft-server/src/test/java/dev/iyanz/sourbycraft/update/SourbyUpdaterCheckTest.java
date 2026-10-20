package dev.iyanz.sourbycraft.update;

import dev.iyanz.sourbycraft.brand.BuildInfo;
import dev.iyanz.sourbycraft.update.SourbyUpdater.ApplyMode;
import dev.iyanz.sourbycraft.update.SourbyUpdater.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The check path without GitHub: a scripted {@link HttpTransport}, a fixed build info
 * ({@code 26.2-REL}, build 47, Minecraft 26.2), a settable clock and a temp
 * {@code auto_update} dir. Covers audit SEC-2 (signature gate), SEC-3 (Minecraft-version gate,
 * rollback hold) and SEC-10 (bounded download, ETag, Retry-After).
 */
class SourbyUpdaterCheckTest {

    static final String LISTING = "https://api.github.com/repos/YanIanZ/SourbyCraft/releases?per_page=100";
    static final String JAR_URL = "https://example.invalid/SourbyCraft-slim.jar";
    static final String SIG_URL = JAR_URL + ".sig";

    @TempDir Path root;
    ScriptedTransport transport;
    TestClock clock;
    UpdateRollbackGuard guard;
    BuildInfo build;
    byte[] jarBytes;
    String jarSha;

    @BeforeEach
    void setUp() throws Exception {
        transport = new ScriptedTransport();
        clock = new TestClock(Instant.parse("2026-10-20T12:00:00Z"));
        guard = new UpdateRollbackGuard(root.resolve("auto_update"));
        build = new BuildInfo("26.2-REL", "47", "47", "26.2", "aurora-nexus", "", "");
        jarBytes = tinyJar();
        jarSha = sha256(jarBytes);
    }

    private SourbyUpdater updater(ReleaseSignatureVerifier verifier) {
        return new SourbyUpdater(root.resolve("auto_update"), transport, () -> build, clock, verifier, guard);
    }

    private SourbyUpdater updater() {
        return updater(ReleaseSignatureVerifier.inactive());
    }

    // ------------------------------------------------------------------ notify / stage basics

    @Test
    void newerReleaseOnTheChannelIsNotifiedWithoutDownloading() {
        transport.enqueue(LISTING, ok(listing(release("v26.2-r48", jarBytes.length, jarSha, false)), Map.of()));
        assertEquals(Outcome.NOTIFIED, updater().check(ApplyMode.NOTIFY));
        assertEquals(List.of(LISTING), transport.urls());
    }

    @Test
    void stageModeDownloadsVerifiesAndStages() throws IOException {
        transport.enqueue(LISTING, ok(listing(release("v26.2-r48", jarBytes.length, jarSha, false)), Map.of()));
        transport.enqueue(JAR_URL, ok(jarBytes, Map.of("Content-Length", List.of(String.valueOf(jarBytes.length)))));

        assertEquals(Outcome.STAGED, updater().check(ApplyMode.STAGE));
        Path staged = root.resolve("auto_update/sourbycraft/SourbyCraft-slim.jar");
        assertTrue(Files.isRegularFile(staged));
        assertEquals(staged.toAbsolutePath().normalize().toString(),
            Files.readString(root.resolve("auto_update/core.path")).trim());
    }

    // ------------------------------------------------------------------ SEC-10: ETag / Retry-After

    @Test
    void etagIsSentBackAndA304AnswersFromTheCachedListing() {
        transport.enqueue(LISTING, ok(listing(release("v26.2-r48", jarBytes.length, jarSha, false)),
            Map.of("ETag", List.of("W/\"abc123\""))));
        transport.enqueue(LISTING, new HttpTransport.Response(304, Map.of(), null));

        SourbyUpdater u = updater();
        assertEquals(Outcome.NOTIFIED, u.check(ApplyMode.NOTIFY));
        assertEquals(Outcome.NOTIFIED, u.check(ApplyMode.NOTIFY), "304 must reuse the cached listing");
        assertEquals(2, transport.requests.size());
        assertEquals("W/\"abc123\"", transport.requests.get(1).headers().get("If-None-Match"));
        assertFalse(transport.requests.get(0).headers().containsKey("If-None-Match"));
    }

    @Test
    void rateLimitArmsABackoffWindowThatSkipsChecksWithoutRequests() {
        Instant reset = clock.instant().plusSeconds(120);
        transport.enqueue(LISTING, new HttpTransport.Response(403, Map.of(
            "X-RateLimit-Remaining", List.of("0"),
            "X-RateLimit-Reset", List.of(String.valueOf(reset.getEpochSecond()))), empty()));
        transport.enqueue(LISTING, ok(listing(release("v26.2-r48", jarBytes.length, jarSha, false)), Map.of()));

        SourbyUpdater u = updater();
        assertEquals(Outcome.RATE_LIMITED, u.check(ApplyMode.NOTIFY));
        assertEquals(reset, u.backoffUntil());

        clock.advance(Duration.ofSeconds(60));
        assertEquals(Outcome.RATE_LIMITED, u.check(ApplyMode.NOTIFY));
        assertEquals(1, transport.requests.size(), "inside the window no request is made");

        clock.advance(Duration.ofSeconds(61));
        assertEquals(Outcome.NOTIFIED, u.check(ApplyMode.NOTIFY));
        assertEquals(2, transport.requests.size());
    }

    @Test
    void retryAfterAcceptsSecondsAndHttpDatesAndIsClamped() {
        Instant now = Instant.parse("2026-10-20T12:00:00Z");
        assertEquals(Duration.ofSeconds(90), SourbyUpdater.retryAfter(resp(Map.of("Retry-After", List.of("90"))), now).orElseThrow());
        assertEquals(SourbyUpdater.MIN_BACKOFF, SourbyUpdater.retryAfter(resp(Map.of("Retry-After", List.of("1"))), now).orElseThrow());
        assertEquals(SourbyUpdater.MAX_BACKOFF, SourbyUpdater.retryAfter(resp(Map.of("Retry-After", List.of("999999"))), now).orElseThrow());
        assertEquals(Duration.ofMinutes(30), SourbyUpdater.retryAfter(
            resp(Map.of("retry-after", List.of("Tue, 20 Oct 2026 12:30:00 GMT"))), now).orElseThrow());
        assertEquals(Optional.empty(), SourbyUpdater.retryAfter(resp(Map.of("X-RateLimit-Remaining", List.of("5"))), now));
        assertEquals(Optional.empty(), SourbyUpdater.retryAfter(resp(Map.of()), now));
    }

    // ------------------------------------------------------------------ SEC-10: bounded download

    @Test
    void oversizeContentLengthIsRefusedBeforeAnyByteIsRead() {
        transport.enqueue(LISTING, ok(listing(release("v26.2-r48", jarBytes.length, jarSha, false)), Map.of()));
        ThrowingStream body = new ThrowingStream();
        transport.enqueue(JAR_URL, new HttpTransport.Response(200,
            Map.of("Content-Length", List.of(String.valueOf(jarBytes.length + 1L))), body));

        assertEquals(Outcome.STAGE_FAILED, updater().check(ApplyMode.STAGE));
        assertFalse(body.read, "the body must not be touched once Content-Length exceeds the bound");
        assertFalse(Files.exists(root.resolve("auto_update/sourbycraft/SourbyCraft-slim.jar.part")));
    }

    @Test
    void aStreamRunningPastTheDeclaredSizeIsCutOffAndDeleted() {
        byte[] tooLong = new byte[jarBytes.length + 1];
        System.arraycopy(jarBytes, 0, tooLong, 0, jarBytes.length);
        transport.enqueue(LISTING, ok(listing(release("v26.2-r48", jarBytes.length, jarSha, false)), Map.of()));
        transport.enqueue(JAR_URL, ok(tooLong, Map.of())); // no Content-Length: only the stream bound can catch it

        assertEquals(Outcome.STAGE_FAILED, updater().check(ApplyMode.STAGE));
        assertFalse(Files.exists(root.resolve("auto_update/sourbycraft/SourbyCraft-slim.jar.part")));
        assertFalse(Files.exists(root.resolve("auto_update/sourbycraft/SourbyCraft-slim.jar")));
    }

    @Test
    void copyBoundedStopsExactlyPastTheLimit() throws IOException {
        Path out = root.resolve("out.bin");
        assertEquals(10, SourbyUpdater.copyBounded(new ByteArrayInputStream(new byte[10]), out, 10));
        IOException e = assertThrows(IOException.class,
            () -> SourbyUpdater.copyBounded(new ByteArrayInputStream(new byte[11]), out, 10));
        assertTrue(e.getMessage().contains("10-byte bound"));
    }

    // ------------------------------------------------------------------ SEC-3: Minecraft-version gate + hold

    @Test
    void aReleaseForAnotherMinecraftVersionIsAnnouncedButNeverStaged() {
        transport.enqueue(LISTING, ok(listing(release("v26.3-r50", jarBytes.length, jarSha, false)), Map.of()));
        assertEquals(Outcome.MC_VERSION_MISMATCH, updater().check(ApplyMode.STAGE));
        assertEquals(List.of(LISTING), transport.urls(), "no download attempted");
        assertFalse(Files.exists(root.resolve("auto_update/core.path")));
    }

    @Test
    void minecraftVersionComesFromTheBuildStampOrTheVersionCore() {
        assertEquals("26.2", SourbyUpdater.runningMinecraftVersion(build));
        assertEquals("26.2", SourbyUpdater.runningMinecraftVersion(
            new BuildInfo("26.2-DEV", "47", "47", "unknown", "", "", "")));
        assertEquals("unknown", SourbyUpdater.runningMinecraftVersion(
            new BuildInfo("dev", "", "", "unknown", "", "", "")));
        assertTrue(SourbyUpdater.sameMinecraftVersion("v26.2-r48", "26.2"));
        assertFalse(SourbyUpdater.sameMinecraftVersion("v26.3-r50", "26.2"));
        assertFalse(SourbyUpdater.sameMinecraftVersion("v26.2-r48", "unknown"), "a build that cannot say its MC version stages nothing");
    }

    @Test
    void aRollbackHoldShortCircuitsTheCheckWithoutTouchingTheNetwork() throws IOException {
        Path jar = Files.write(root.resolve("SourbyCraft.jar"), new byte[]{1});
        Path prev = guard.keepPrevious(jar);
        guard.recordApplied("v26.2-r48", jar, prev, clock.instant());
        guard.onBoot();                      // first boot on the new jar
        assertEquals(UpdateRollbackGuard.BootDecision.RESTORED, guard.onBoot()); // ... never reached ready

        assertEquals(Outcome.HELD, updater().check(ApplyMode.AUTO));
        assertTrue(transport.requests.isEmpty());
    }

    // ------------------------------------------------------------------ SEC-2: signature gate

    @Test
    void withAKeyInTheBuildAReleaseWithoutASignatureIsRefused() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        transport.enqueue(LISTING, ok(listing(release("v26.2-r48", jarBytes.length, jarSha, false)), Map.of()));
        transport.enqueue(JAR_URL, ok(jarBytes, Map.of()));

        assertEquals(Outcome.STAGE_FAILED,
            updater(ReleaseSignatureVerifier.fromEncodedKey(kp.getPublic().getEncoded())).check(ApplyMode.STAGE));
        assertFalse(Files.exists(root.resolve("auto_update/sourbycraft/SourbyCraft-slim.jar")));
    }

    @Test
    void withAKeyInTheBuildAValidSignatureStagesAndATamperedOneDoesNot() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(jarBytes);
        byte[] sig = s.sign();
        ReleaseSignatureVerifier verifier = ReleaseSignatureVerifier.fromEncodedKey(kp.getPublic().getEncoded());

        transport.enqueue(LISTING, ok(listing(release("v26.2-r48", jarBytes.length, jarSha, true)), Map.of()));
        transport.enqueue(JAR_URL, ok(jarBytes, Map.of()));
        transport.enqueue(SIG_URL, ok(sig, Map.of()));
        assertEquals(Outcome.STAGED, updater(verifier).check(ApplyMode.STAGE));
        assertEquals(List.of(LISTING, JAR_URL, SIG_URL), transport.urls());

        // Same release, flipped signature byte: refused and nothing staged over the good copy.
        Files.delete(root.resolve("auto_update/sourbycraft/SourbyCraft-slim.jar"));
        byte[] bad = sig.clone();
        bad[0] ^= 0x01;
        transport.enqueue(LISTING, ok(listing(release("v26.2-r48", jarBytes.length, jarSha, true)), Map.of()));
        transport.enqueue(JAR_URL, ok(jarBytes, Map.of()));
        transport.enqueue(SIG_URL, ok(bad, Map.of()));
        assertEquals(Outcome.STAGE_FAILED, updater(verifier).check(ApplyMode.STAGE));
        assertFalse(Files.exists(root.resolve("auto_update/sourbycraft/SourbyCraft-slim.jar")));
    }

    @Test
    void withoutAKeyTheSignatureAssetIsNeverEvenFetched() throws Exception {
        transport.enqueue(LISTING, ok(listing(release("v26.2-r48", jarBytes.length, jarSha, true)), Map.of()));
        transport.enqueue(JAR_URL, ok(jarBytes, Map.of()));
        assertEquals(Outcome.STAGED, updater().check(ApplyMode.STAGE));
        assertEquals(List.of(LISTING, JAR_URL), transport.urls());
    }

    // ------------------------------------------------------------------ SEC-4: launcher gate

    private Outcome stageJar(byte[] jar) throws Exception {
        transport.enqueue(LISTING, ok(listing(release("v26.2-r48", jar.length, sha256(jar), false)), Map.of()));
        transport.enqueue(JAR_URL, ok(jar, Map.of()));
        return updater().check(ApplyMode.STAGE);
    }

    private void assertNothingStaged() {
        assertFalse(Files.exists(root.resolve("auto_update/sourbycraft/SourbyCraft-slim.jar")));
        assertFalse(Files.exists(root.resolve("auto_update/sourbycraft/SourbyCraft-slim.jar.part")));
        assertFalse(Files.exists(root.resolve("auto_update/core.path")), "core.path must not point at a refused jar");
        assertFalse(Files.exists(root.resolve("auto_update/sourbycraft/latest.path")));
    }

    @Test
    void aReleaseWithACompatibleLauncherIsStaged() throws Exception {
        assertEquals(Outcome.STAGED, stageJar(launcherJar("3.1.2", "1")));
        assertTrue(Files.isRegularFile(root.resolve("auto_update/sourbycraft/SourbyCraft-slim.jar")));
    }

    @Test
    void aNewerPatchLauncherOnTheSameProtocolIsStaged() throws Exception {
        assertEquals(Outcome.STAGED, stageJar(launcherJar("3.2.0", "1")));
    }

    @Test
    void aReleaseWhoseLauncherSpeaksAnotherProtocolIsRefusedAndDeleted() throws Exception {
        assertEquals(Outcome.CLIP_INCOMPATIBLE, stageJar(launcherJar("3.1.2", "2")));
        assertNothingStaged();
    }

    @Test
    void aReleaseWhoseLauncherPredatesTheProtocolIsRefused() throws Exception {
        assertEquals(Outcome.CLIP_INCOMPATIBLE, stageJar(launcherJar("3.0.22", "1")));
        assertNothingStaged();
    }

    @Test
    void aReleaseWithNoLauncherMetadataFailsClosed() throws Exception {
        // The shape of release/SourbyCraft-26.2-REL.jar (Clip-Version 3.0.21, no protocol, no properties).
        byte[] oldShape = jar("Manifest-Version: 1.0\nClip-Version: 3.0.21\n", null);
        assertEquals(Outcome.CLIP_INCOMPATIBLE, stageJar(oldShape));
        assertNothingStaged();
    }

    @Test
    void aLauncherRefusalIsDistinctFromADownloadFailure() throws Exception {
        assertNotEquals(Outcome.STAGE_FAILED, stageJar(launcherJar("3.1.2", "2")));
    }

    @Test
    void theLauncherGateRunsAfterTheDigestSoATamperedJarIsStillAStageFailure() throws Exception {
        byte[] jar = launcherJar("3.1.2", "2");
        transport.enqueue(LISTING, ok(listing(release("v26.2-r48", jar.length, "00".repeat(32), false)), Map.of()));
        transport.enqueue(JAR_URL, ok(jar, Map.of()));
        assertEquals(Outcome.STAGE_FAILED, updater().check(ApplyMode.STAGE));
        assertNothingStaged();
    }

    // ------------------------------------------------------------------ fixtures

    static String release(String tag, long size, String sha, boolean withSig) {
        String sig = withSig ? ",{\"name\":\"SourbyCraft-slim.jar.sig\",\"browser_download_url\":\"" + SIG_URL + "\",\"size\":64}" : "";
        return "{\"tag_name\":\"" + tag + "\",\"draft\":false,\"prerelease\":false,"
            + "\"html_url\":\"https://example.invalid/rel/" + tag + "\",\"body\":\"# Notes\\nfirst line\","
            + "\"assets\":[{\"name\":\"SourbyCraft-slim.jar\",\"browser_download_url\":\"" + JAR_URL + "\","
            + "\"size\":" + size + ",\"digest\":\"sha256:" + sha + "\"}" + sig + "]}";
    }

    static String listing(String... releases) {
        return "[" + String.join(",", releases) + "]";
    }

    static HttpTransport.Response ok(String json, Map<String, List<String>> headers) {
        return ok(json.getBytes(StandardCharsets.UTF_8), headers);
    }

    static HttpTransport.Response ok(byte[] body, Map<String, List<String>> headers) {
        return new HttpTransport.Response(200, headers, new ByteArrayInputStream(body));
    }

    static HttpTransport.Response resp(Map<String, List<String>> headers) {
        return new HttpTransport.Response(429, headers, empty());
    }

    static InputStream empty() {
        return new ByteArrayInputStream(new byte[0]);
    }

    /** A minimal release jar that declares the launcher the real build embeds (SourbyClip 3.1.2, protocol 1). */
    static byte[] tinyJar() throws IOException {
        return launcherJar("3.1.2", "1");
    }

    /**
     * A jar whose manifest and {@code META-INF/sourbyclip.properties} declare the given launcher;
     * a {@code null} version/protocol leaves that key out of BOTH entries.
     */
    static byte[] launcherJar(String version, String protocol) throws IOException {
        StringBuilder manifest = new StringBuilder("Manifest-Version: 1.0\n");
        if (version != null) manifest.append("Clip-Version: ").append(version).append('\n');
        if (protocol != null) manifest.append("SourbyClip-Protocol: ").append(protocol).append('\n');
        StringBuilder props = new StringBuilder();
        if (version != null) props.append("version=").append(version).append('\n');
        if (protocol != null) props.append("protocol=").append(protocol).append('\n');
        return jar(manifest.toString(), props.toString());
    }

    /** A jar with exactly these two metadata entries; a {@code null} argument omits that entry. */
    static byte[] jar(String manifest, String properties) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            if (manifest != null) {
                zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                zip.write(manifest.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            if (properties != null) {
                zip.putNextEntry(new ZipEntry("META-INF/sourbyclip.properties"));
                zip.write(properties.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("version.json"));
            zip.write("{}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    /** Records every request; answers from per-URL queues; fails loudly on an unexpected URL. */
    static final class ScriptedTransport implements HttpTransport {
        record Request(String url, Map<String, String> headers) {}
        final List<Request> requests = new ArrayList<>();
        private final Map<String, Deque<Response>> script = new java.util.HashMap<>();

        void enqueue(String url, Response r) {
            script.computeIfAbsent(url, k -> new ArrayDeque<>()).add(r);
        }

        List<String> urls() {
            return requests.stream().map(Request::url).toList();
        }

        @Override
        public Response get(String url, Map<String, String> headers) throws IOException {
            requests.add(new Request(url, Map.copyOf(headers)));
            Deque<Response> q = script.get(url);
            if (q == null || q.isEmpty()) throw new IOException("unscripted request: " + url);
            return q.poll();
        }
    }

    /** A body that must never be read. */
    static final class ThrowingStream extends InputStream {
        boolean read;
        @Override public int read() throws IOException { read = true; throw new IOException("body read"); }
    }

    static final class TestClock extends Clock {
        private Instant now;
        TestClock(Instant start) { this.now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
