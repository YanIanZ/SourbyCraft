package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.util.BarUtil;
import dev.iyanz.sourbycraft.util.VirtualExecutor;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.zip.GZIPInputStream;

import static net.kyori.adventure.text.Component.text;

/**
 * {@code /speedtest} — runs the pinned, SHA-256-verified Ookla Speedtest CLI 1.2.0 binary and
 * reports download/upload/ping as a hex-coloured panel matching the rest of the SourbyCraft suite.
 *
 * <p>The platform-specific binary is lazy-downloaded on first use into
 * {@code libraries/speedtest/<os>/<arch>/} and verified against a hard-coded SHA-256 pin (see
 * {@link #OOKLA_SHA256}) before it is ever extracted or executed — the same download-integrity
 * discipline as {@code LibDownloader}/{@code SourbyUpdater}. The extracted binary's SHA-256 is
 * recorded beside it and re-checked before every run (see {@link #installIntact()}). Runs on a
 * {@link dev.iyanz.sourbycraft.util.VirtualExecutor virtual thread}; only one speedtest may be
 * in flight at a time so repeated invocations cannot stack OS processes that would themselves
 * saturate the bandwidth being measured. The process's output is drained concurrently and the
 * process is killed when it outlives {@link #DEFAULT_TIMEOUT}, so a hung CLI can neither leak
 * nor wedge the command. Every reply is delivered back through {@link SourbyReply#run} since
 * Folia has no global main thread to bounce it through.
 */
public class SpeedtestCommand extends Command {
    private static final Logger LOG = LoggerFactory.getLogger("SourbyCraft/Speedtest");
    private static final String OOKLA_BASE_URL = "https://install.speedtest.net/app/cli/";

    private static final String OS_NAME = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
    private static final String OS_ARCH = System.getProperty("os.arch").toLowerCase(java.util.Locale.ROOT);

    /**
     * SHA-256 pins for every supported Ookla CLI 1.2.0 archive (the exact bytes served by
     * {@link #OOKLA_BASE_URL} on 2026-07-11). The downloaded archive is verified against its pin
     * BEFORE the binary is ever extracted/executed — matching the download-integrity discipline of
     * {@code SourbyUpdater} / {@code LibDownloader}. A platform with no published binary (e.g. Windows
     * ARM64: Ookla ships no {@code winarm64} build for 1.2.0) is deliberately absent, so
     * {@link #resolveBinaryName()} reports it unsupported rather than executing an unpinned download.
     */
    private static final Map<String, String> OOKLA_SHA256 = Map.of(
        "ookla-speedtest-1.2.0-linux-x86_64.tgz",     "5690596c54ff9bed63fa3732f818a05dbc2db19ad36ed68f21ca5f64d5cfeeb7",
        "ookla-speedtest-1.2.0-linux-aarch64.tgz",    "3953d231da3783e2bf8904b6dd72767c5c6e533e163d3742fd0437affa431bd3",
        "ookla-speedtest-1.2.0-linux-armhf.tgz",      "e45fcdebbd8a185553535533dd032d6b10bc8c64eee4139b1147b9c09835d08d",
        "ookla-speedtest-1.2.0-macosx-universal.tgz", "c9f8192149ebc88f8699998cecab1ce144144045907ece6f53cf50877f4de66f",
        "ookla-speedtest-1.2.0-win64.zip",            "13e3d888b845d301a556419e31f14ab9bff57e3f06089ef2fd3bdc9ba6841efa"
    );

    private static String resolveBinaryName() {
        if (OS_NAME.contains("linux")) {
            if (OS_ARCH.equals("x86_64") || OS_ARCH.equals("amd64")) {
                return "ookla-speedtest-1.2.0-linux-x86_64.tgz";
            } else if (OS_ARCH.equals("aarch64") || OS_ARCH.equals("arm64")) {
                return "ookla-speedtest-1.2.0-linux-aarch64.tgz";
            } else if (OS_ARCH.equals("arm") || OS_ARCH.startsWith("armv")) {
                return "ookla-speedtest-1.2.0-linux-armhf.tgz";
            }
        } else if (OS_NAME.contains("mac") || OS_NAME.contains("darwin")) {
            // macOS uses universal binary (x86_64 + arm64)
            return "ookla-speedtest-1.2.0-macosx-universal.tgz";
        } else if (OS_NAME.contains("windows")) {
            if (OS_ARCH.equals("x86_64") || OS_ARCH.equals("amd64")) {
                return "ookla-speedtest-1.2.0-win64.zip";
            }
            // No Windows-ARM64 build is published for Ookla CLI 1.2.0 (unpinnable) -> unsupported.
        }
        return null;
    }

    private static final String BINARY_NAME = resolveBinaryName();
    private static final String OOKLA_URL = BINARY_NAME != null ? OOKLA_BASE_URL + BINARY_NAME : null;

    private static Path getBinaryPath() {
        String binName = OS_NAME.contains("windows") ? "speedtest.exe" : "speedtest";
        String archDir = OS_NAME.contains("linux") ? "linux" :
                         OS_NAME.contains("mac") || OS_NAME.contains("darwin") ? "macos" : "windows";
        String archSubDir;
        if (OS_NAME.contains("linux")) {
            archSubDir = OS_ARCH.equals("x86_64") || OS_ARCH.equals("amd64") ? "x86_64" :
                         OS_ARCH.equals("aarch64") || OS_ARCH.equals("arm64") ? "aarch64" :
                         OS_ARCH.startsWith("arm") ? "armhf" : "unknown";
        } else if (OS_NAME.contains("mac") || OS_NAME.contains("darwin")) {
            archSubDir = "universal";
        } else {
            archSubDir = OS_ARCH.equals("x86_64") || OS_ARCH.equals("amd64") ? "x86_64" :
                         OS_ARCH.equals("aarch64") || OS_ARCH.equals("arm64") ? "aarch64" : "unknown";
        }
        return Paths.get("libraries", "speedtest", archDir, archSubDir, binName);
    }

    private static final Path BIN = getBinaryPath();
    /**
     * SHA-256 of {@link #BIN} as extracted from the verified archive, written after the binary is in
     * place: a binary without this record is an interrupted install and is downloaded again.
     */
    private static final Path BIN_SHA256 = BIN.resolveSibling(BIN.getFileName() + ".sha256");

    /**
     * Starts the Ookla CLI. The server's launcher ({@link PinnedBinaryLauncher}) downloads and
     * verifies the pinned binary first; a test's launcher can be any process, including one that
     * never exits. A launcher must merge stderr into stdout ({@code redirectErrorStream(true)})
     * or the command drains both; either way no pipe is left unread.
     */
    @FunctionalInterface
    interface Launcher {
        Process start() throws IOException;

        /** Why this platform cannot run a speedtest, or {@code null} when it can. */
        default String unsupportedReason() {
            return null;
        }

        /** Whether {@link #start()} will first download the binary (worth telling the sender). */
        default boolean needsDownload() {
            return false;
        }
    }

    /** The pinned, SHA-256-verified Ookla CLI from {@code libraries/speedtest/<os>/<arch>/}. */
    static final class PinnedBinaryLauncher implements Launcher {
        @Override
        public String unsupportedReason() {
            return BINARY_NAME == null || OOKLA_URL == null
                ? "Speedtest unsupported on this platform (OS=" + OS_NAME + ", arch=" + OS_ARCH + ")" : null;
        }

        @Override
        public boolean needsDownload() {
            return !Files.exists(BIN) || !Files.exists(BIN_SHA256);
        }

        /** Re-verifies the installed binary before every run; a missing or failed check downloads it again. */
        @Override
        public Process start() throws IOException {
            if (!installIntact()) {
                downloadSpeedtestBinary();
            }
            if (!installIntact()) {
                throw new IOException("speedtest binary unavailable (download failed verification)");
            }
            return new ProcessBuilder(BIN.toString(), "--format=json", "--accept-license", "--accept-gdpr")
                .redirectErrorStream(true).start();
        }
    }

    /**
     * Whether {@link #BIN} still has the SHA-256 recorded when it was extracted. A binary without a
     * record (an interrupted install, or one extracted by a build before the record existed) or with
     * a different hash is deleted with its record, so the caller downloads and verifies it again.
     * This catches a truncated or corrupted binary; whoever can write both files can replace both.
     */
    private static boolean installIntact() throws IOException {
        // A record that is too long or not text fails the comparison, so it is replaced rather than thrown on.
        if (Files.isRegularFile(BIN) && Files.isRegularFile(BIN_SHA256) && Files.size(BIN_SHA256) <= 128) {
            final String recorded = new String(Files.readAllBytes(BIN_SHA256), StandardCharsets.US_ASCII).trim();
            if (recorded.equalsIgnoreCase(sha256(BIN))) return true;
            LOG.warn("Speedtest binary {} does not match its recorded SHA-256; downloading it again", BIN);
        }
        Files.deleteIfExists(BIN_SHA256);
        Files.deleteIfExists(BIN);
        return false;
    }

    /** How long the CLI may run before it is killed; a complete Ookla run takes about 30 s. */
    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    /** After the process has exited, how long its output pipe may take to close. */
    private static final Duration DRAIN_GRACE = Duration.ofSeconds(10);

    private final Launcher launcher;
    private final Duration timeout;
    private final Executor executor;
    private final BiConsumer<CommandSender, Runnable> reply;
    // One speedtest at a time — repeated invocations would stack OS processes, each
    // saturating the very bandwidth the test is measuring. Reset on every exit path of run().
    private final AtomicBoolean inFlight = new AtomicBoolean();

    public SpeedtestCommand(String name) {
        this(name, new PinnedBinaryLauncher(), DEFAULT_TIMEOUT, VirtualExecutor::run, SourbyReply::run);
    }

    /**
     * Every collaborator injectable: the process, the clock the timeout runs on, the thread the
     * work runs on and the hop a reply takes back to the sender. A test can then hang the process
     * with a shell script and prove the command recovers, without a network or a binary.
     */
    SpeedtestCommand(String name, Launcher launcher, Duration timeout, Executor executor,
                     BiConsumer<CommandSender, Runnable> reply) {
        super(name);
        this.description = "Ookla speedtest";
        this.usageMessage = "/speedtest";
        this.setPermission("sourbycraft.command.speedtest");
        this.launcher = launcher;
        this.timeout = timeout;
        this.executor = executor;
        this.reply = reply;
    }

    /**
     * Runs the Ookla CLI on a virtual thread (downloading it first if absent), then replies with
     * a coloured download/upload/ping panel. Rejects a second invocation while one is in flight
     * and reports the platform as unsupported when no pinned binary exists for it.
     */
    @Override
    public boolean execute(CommandSender sender, String alias, String[] args) {
        // Nothing happens before the permission check -- not even a log line (OBS-12): the log
        // used to name the binary path and platform for every sender, permitted or not.
        if (!testPermission(sender)) return true;

        if (dev.iyanz.sourbycraft.core.AuroraRuntime.stopping()) {
            sender.sendMessage(text("Speedtest unavailable: the server is stopping or failed to start",
                SourbyCraftColors.DANGER));
            return true;
        }

        final String unsupported = this.launcher.unsupportedReason();
        if (unsupported != null) {
            sender.sendMessage(text(unsupported, SourbyCraftColors.DANGER));
            return true;
        }

        if (!this.inFlight.compareAndSet(false, true)) {
            sender.sendMessage(text("A speedtest is already running — wait for it to finish.", SourbyCraftColors.DANGER));
            return true;
        }
        sender.sendMessage(text("Running...", SourbyCraftColors.LABEL));
        try {
            this.executor.execute(() -> {
                try {
                    run(sender);
                } finally {
                    this.inFlight.set(false);
                }
            });
        } catch (RuntimeException rejected) {                     // the I/O executor is stopped
            this.inFlight.set(false);
            sender.sendMessage(text("Speedtest unavailable: " + rejected.getMessage(), SourbyCraftColors.DANGER));
        }
        return true;
    }

    /** Off the command thread. Every message from here goes back through {@link #reply}. */
    private void run(final CommandSender sender) {
        LOG.info("Speedtest invoked by {}. OS={} arch={} binPath={} binExists={} binaryName={}", sender.getName(),
            System.getProperty("os.name"), System.getProperty("os.arch"), BIN.toAbsolutePath(), Files.exists(BIN),
            BINARY_NAME);
        Process process = null;
        try {
            if (this.launcher.needsDownload()) {
                say(sender, text("Downloading speedtest CLI (first run, ~1MB)...", SourbyCraftColors.LABEL));
            }
            process = this.launcher.start();
            final Process started = process;
            // Drained on their own threads. This thread used to call readAllBytes() before
            // waitFor(): a CLI that hung with its pipe open blocked here for good, the timeout
            // never ran, the process stayed, and the in-flight flag kept every later /speedtest
            // out until restart. Draining concurrently also keeps a chatty process from
            // blocking on a full pipe while this thread waits for it to exit.
            final CompletableFuture<String> output = CompletableFuture.supplyAsync(
                () -> drain(started.getInputStream()), this.executor);
            final CompletableFuture<String> errors = CompletableFuture.supplyAsync(
                () -> drain(started.getErrorStream()), this.executor);

            if (!process.waitFor(this.timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                LOG.error("Speedtest did not finish within {}s; process killed", this.timeout.toSeconds());
                say(sender, text("Speedtest timed out (" + this.timeout.toSeconds() + "s); process killed",
                    SourbyCraftColors.DANGER));
                return;
            }
            final String stdout;
            try {
                stdout = output.get(DRAIN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
                final String stderr = errors.get(DRAIN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
                if (!stderr.isBlank()) {
                    LOG.warn("Speedtest stderr: {}", stderr.strip());
                }
            } catch (final TimeoutException pipeStillOpen) {
                // The process is gone but something it started still holds the pipe; the reader
                // thread ends when that closes. Do not wait on it any longer.
                LOG.error("Speedtest exited but its output did not close within {}s", DRAIN_GRACE.toSeconds());
                say(sender, text("Speedtest failed: output not available", SourbyCraftColors.DANGER));
                return;
            } catch (final ExecutionException unreadable) {
                LOG.error("Speedtest output could not be read: {}", unreadable.getCause().toString());
                say(sender, text("Speedtest failed: " + unreadable.getCause().getMessage(), SourbyCraftColors.DANGER));
                return;
            }
            report(sender, stdout, process.exitValue());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            say(sender, text("Speedtest interrupted", SourbyCraftColors.DANGER));
        } catch (final Exception e) {
            LOG.error("Speedtest process failed: {}", e.toString(), e);
            say(sender, text("Speedtest failed: " + e.getMessage(), SourbyCraftColors.DANGER));
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly(); // no orphans on any exit path
        }
    }

    private static String drain(final InputStream stream) {
        try (stream) {
            return new String(dev.iyanz.sourbycraft.util.BoundedIo.readAll(stream, MAX_OUTPUT_BYTES), StandardCharsets.UTF_8);
        } catch (final IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    /** Parses the CLI's JSON and replies with the panel, or with why it could not. */
    private void report(final CommandSender sender, final String output, final int exitCode) {
        if (output == null || output.trim().isEmpty()) {
            LOG.error("Speedtest produced empty output (exit {})", exitCode);
            say(sender, text("Speedtest failed: empty output (exit " + exitCode + ")", SourbyCraftColors.DANGER));
            return;
        }

        String trimmed = output.trim();
        int jsonStart = trimmed.indexOf('{');
        if (jsonStart < 0) {
            LOG.error("Speedtest output is not JSON (exit {}): {}", exitCode,
                trimmed.substring(0, Math.min(200, trimmed.length())));
            say(sender, text("Speedtest failed: unexpected output format (exit " + exitCode + ")", SourbyCraftColors.DANGER));
            return;
        }
        if (jsonStart > 0) {
            LOG.warn("Speedtest output has {} bytes of preamble before JSON, stripping", jsonStart);
            trimmed = trimmed.substring(jsonStart);
        }

        final Map<?, ?> dl;
        final Map<?, ?> ul;
        final Map<?, ?> ping;
        final Map<?, ?> sv;
        try {
            final Map<?, ?> r = new com.google.gson.Gson().fromJson(trimmed, Map.class);
            dl = (Map<?, ?>) r.get("download");
            ul = (Map<?, ?>) r.get("upload");
            ping = (Map<?, ?>) r.get("ping");
            sv = (Map<?, ?>) r.get("server");
        } catch (final RuntimeException malformed) {              // JsonSyntaxException, ClassCastException
            LOG.error("Speedtest JSON is malformed: {}", malformed.toString());
            say(sender, text("Speedtest failed: malformed JSON response", SourbyCraftColors.DANGER));
            return;
        }
        final java.util.List<String> missing = new java.util.ArrayList<>();
        if (dl == null || !(dl.get("bandwidth") instanceof Number)) missing.add("download.bandwidth");
        if (ul == null || !(ul.get("bandwidth") instanceof Number)) missing.add("upload.bandwidth");
        if (ping == null || !(ping.get("latency") instanceof Number)) missing.add("ping.latency");
        if (sv == null) missing.add("server");
        if (!missing.isEmpty()) {
            // Names and length only: the report itself carries the host's external IP and MAC address.
            LOG.error("Speedtest JSON missing expected fields {} ({} characters, not logged)", missing, trimmed.length());
            say(sender, text("Speedtest failed: incomplete JSON response", SourbyCraftColors.DANGER));
            return;
        }

        LOG.info("Speedtest parsed: dl={} ul={} ping={} server={}", dl.get("bandwidth"), ul.get("bandwidth"),
            ping.get("latency"), sv.get("name"));

        final double dm = ((Number) dl.get("bandwidth")).doubleValue() * 8 / 1_000_000;
        final double um = ((Number) ul.get("bandwidth")).doubleValue() * 8 / 1_000_000;
        final double pm = ((Number) ping.get("latency")).doubleValue();

        // Folia-safe reply hop. MinecraftServer.execute() throws UnsupportedOperationException
        // on Folia (no global main thread). Bounce the reply onto the sender's own scheduler
        // instead (player region / console direct / command block's region).
        say(sender, text()
            .append(text("DL: ", SourbyCraftColors.LABEL))
            .append(BarUtil.coloredBar(Math.min(dm / 10, 100), 20))
            .append(text(String.format(java.util.Locale.ROOT, " %.1f Mbps", dm), SourbyCraftColors.SUCCESS))
            .append(text("\nUL: ", SourbyCraftColors.LABEL))
            .append(BarUtil.coloredBar(Math.min(um / 10, 100), 20))
            .append(text(String.format(java.util.Locale.ROOT, " %.1f Mbps", um), SourbyCraftColors.SUCCESS))
            .append(text("\nPing: ", SourbyCraftColors.LABEL))
            .append(text(String.format(java.util.Locale.ROOT, "%.0fms", pm),
                pm < 30 ? SourbyCraftColors.SUCCESS : SourbyCraftColors.PRIMARY))
            .append(text(" | " + sv.get("name") + ", " + sv.get("location"), SourbyCraftColors.DIM))
            .build());
    }

    /**
     * Delivers one message from the worker to the sender on the thread that owns the sender.
     * Every message sent after {@link #execute} returned goes through here (OBS-8): a command
     * block's {@code sendMessage} writes block-entity state and throws off its region thread.
     */
    private void say(final CommandSender sender, final Component message) {
        this.reply.accept(sender, () -> {
            try {
                sender.sendMessage(message);
            } catch (final Exception ex) {
                LOG.error("Failed to send speedtest message: {}", ex.toString(), ex);
            }
        });
    }

    /** The CLI archive is about 1 MiB and SHA-256 pinned; anything this large is not it. */
    static final long MAX_ARCHIVE_BYTES = 32L * 1024 * 1024;
    /** The CLI's JSON report is a few KiB; output past this fails the run instead of filling memory. */
    static final int MAX_OUTPUT_BYTES = 1024 * 1024;

    private static void downloadSpeedtestBinary() throws IOException {
        Files.createDirectories(BIN.getParent());
        HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(OOKLA_URL))
            .timeout(Duration.ofMinutes(2))
            .GET().build();
        Path tarball = BIN.getParent().resolve("speedtest.tgz.tmp");
        Files.deleteIfExists(tarball);
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during speedtest download", e);
        }
        try (InputStream body = resp.body()) {
            if (resp.statusCode() != 200) {
                throw new IOException("HTTP " + resp.statusCode() + " from " + OOKLA_URL);
            }
            dev.iyanz.sourbycraft.util.BoundedIo.copy(body, tarball, MAX_ARCHIVE_BYTES);
        } catch (IOException e) {
            Files.deleteIfExists(tarball);
            throw e;
        }
        // SHA-256 integrity gate: verify the downloaded archive against its pin BEFORE extracting or
        // executing anything. A MITM'd/tampered mirror would otherwise yield an unpinned executable.
        String expected = OOKLA_SHA256.get(BINARY_NAME);
        if (expected == null) {
            Files.deleteIfExists(tarball);
            throw new IOException("no SHA-256 pin for " + BINARY_NAME + "; refusing to execute unverified binary");
        }
        String actual = sha256(tarball);
        if (!expected.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(tarball);
            throw new IOException("SHA-256 mismatch for " + BINARY_NAME
                + ": got " + actual + ", expected " + expected + " (refusing to execute)");
        }
        LOG.info("Speedtest archive SHA-256 verified for {}", BINARY_NAME);
        // Extracted beside BIN and renamed into place only when complete: an extraction that failed
        // midway (disk full, a kill) used to leave a truncated BIN that every later run executed.
        final Path partial = BIN.resolveSibling(BIN.getFileName() + ".part");
        try {
            Files.deleteIfExists(partial);
            try (InputStream raw = Files.newInputStream(tarball)) {
                if (OOKLA_URL.endsWith(".zip")) {
                    extractSpeedtestFromZip(raw, partial);
                } else {
                    try (GZIPInputStream gz = new GZIPInputStream(raw)) {
                        extractSpeedtestFromTar(gz, partial);
                    }
                }
            }
            if (!Files.exists(partial)) {
                throw new IOException("speedtest binary missing from extracted archive");
            }
            try {
                partial.toFile().setExecutable(true);
            } catch (SecurityException ignored) { }
            final String extracted = sha256(partial);
            // The old record goes first, so no crash can pair it with the new binary.
            Files.deleteIfExists(BIN_SHA256);
            try {
                Files.move(partial, BIN, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException notAtomic) {
                Files.move(partial, BIN, StandardCopyOption.REPLACE_EXISTING);
            }
            // Written last: its presence marks a complete install (a torn write fails the next check).
            Files.writeString(BIN_SHA256, extracted, StandardCharsets.US_ASCII);
        } finally {
            Files.deleteIfExists(partial);
            Files.deleteIfExists(tarball);
        }
        LOG.info("Speedtest binary downloaded to {}", BIN);
    }

    private static void extractSpeedtestFromTar(InputStream tar, Path dest) throws IOException {
        byte[] header = new byte[512];
        while (true) {
            int r = tar.readNBytes(header, 0, 512);
            if (r < 512) return;
            if (isZeroBlock(header)) return;
            String name = readNulString(header, 0, 100);
            long size = parseTarOctal(header, 124, 12);
            if (name.equals("speedtest") || name.endsWith("/speedtest")) {
                try (OutputStream out = Files.newOutputStream(dest)) {
                    long remaining = size;
                    byte[] buf = new byte[64 * 1024];
                    while (remaining > 0) {
                        int n = tar.read(buf, 0, (int) Math.min(buf.length, remaining));
                        if (n < 0) throw new IOException("EOF inside tar entry");
                        out.write(buf, 0, n);
                        remaining -= n;
                    }
                }
                long pad = (512 - (size % 512)) % 512;
                if (pad > 0) tar.readNBytes((int) pad);
                return;
            } else {
                long toSkip = size + ((512 - (size % 512)) % 512);
                while (toSkip > 0) {
                    long skipped = tar.skip(toSkip);
                    if (skipped <= 0) throw new IOException("cannot skip past tar entry");
                    toSkip -= skipped;
                }
            }
        }
    }

    private static void extractSpeedtestFromZip(InputStream zipIn, Path dest) throws IOException {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(zipIn)) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals("speedtest") || name.equals("speedtest.exe") || name.endsWith("/speedtest") || name.endsWith("/speedtest.exe")) {
                    try (OutputStream out = Files.newOutputStream(dest)) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            out.write(buf, 0, n);
                        }
                    }
                    return;
                }
            }
        }
    }

    /** Hex-encoded SHA-256 of a file, for the download-integrity gate. */
    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable in this JDK", e);
        }
    }

    private static boolean isZeroBlock(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String readNulString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off);
    }

    private static long parseTarOctal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ') break;
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0');
        }
        return v;
    }
}