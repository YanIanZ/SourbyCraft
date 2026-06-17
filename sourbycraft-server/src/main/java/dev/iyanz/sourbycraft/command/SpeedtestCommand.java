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
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static net.kyori.adventure.text.Component.text;

public class SpeedtestCommand extends Command {
    private static final Logger LOG = LoggerFactory.getLogger("SourbyCraft:Speedtest");
    private static final String OOKLA_BASE_URL = "https://install.speedtest.net/app/cli/";

    private static final String OS_NAME = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT);
    private static final String OS_ARCH = System.getProperty("os.arch").toLowerCase(java.util.Locale.ROOT);

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
            } else if (OS_ARCH.equals("aarch64") || OS_ARCH.equals("arm64")) {
                return "ookla-speedtest-1.2.0-winarm64.zip";
            }
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

    public SpeedtestCommand(String name) {
        super(name);
        this.description = "Ookla speedtest";
        this.usageMessage = "/speedtest";
        this.setPermission("sourbycraft.command.speedtest");
    }

    @Override
    public boolean execute(CommandSender sender, String alias, String[] args) {
        LOG.info("Speedtest invoked. OS={} arch={} binPath={} binExists={} binaryName={}",
            System.getProperty("os.name"),
            System.getProperty("os.arch"),
            BIN.toAbsolutePath(),
            Files.exists(BIN),
            BINARY_NAME
        );

        if (!testPermission(sender)) return true;

        if (BINARY_NAME == null || OOKLA_URL == null) {
            sender.sendMessage(text("Speedtest unsupported on this platform (OS=" + OS_NAME + ", arch=" + OS_ARCH + ")", net.kyori.adventure.text.format.NamedTextColor.RED));
            return true;
        }

        sender.sendMessage(text("Running...", SourbyCraftColors.LABEL));
        VirtualExecutor.run(() -> {
            try {
                if (!Files.exists(BIN)) {
                    sender.sendMessage(text("Downloading speedtest CLI (first run, ~1MB)...", SourbyCraftColors.LABEL));
                    downloadSpeedtestBinary();
                }
                if (!Files.exists(BIN)) {
                    sender.sendMessage(text("Speedtest unavailable (download failed)", SourbyCraftColors.DANGER));
                    return;
                }
                Process proc = new ProcessBuilder(BIN.toString(),
                        "--format=json", "--accept-license", "--accept-gdpr")
                    .redirectErrorStream(true).start();
                String output = new String(proc.getInputStream().readAllBytes());
                proc.waitFor();

                if (output == null || output.trim().isEmpty()) {
                    LOG.error("Speedtest produced empty output");
                    sender.sendMessage(text("Speedtest failed: empty output", SourbyCraftColors.DANGER));
                    return;
                }

                String trimmed = output.trim();
                int jsonStart = trimmed.indexOf('{');
                if (jsonStart < 0) {
                    LOG.error("Speedtest output is not JSON: {}", trimmed.substring(0, Math.min(200, trimmed.length())));
                    sender.sendMessage(text("Speedtest failed: unexpected output format", SourbyCraftColors.DANGER));
                    return;
                }
                if (jsonStart > 0) {
                    LOG.warn("Speedtest output has {} bytes of preamble before JSON, stripping", jsonStart);
                    trimmed = trimmed.substring(jsonStart);
                }

                var g = new com.google.gson.Gson();
                var r = g.fromJson(trimmed, Map.class);
                var dl = (Map) r.get("download");
                var ul = (Map) r.get("upload");
                var ping = (Map) r.get("ping");
                var sv = (Map) r.get("server");

                if (dl == null || ul == null || ping == null || sv == null) {
                    LOG.error("Speedtest JSON missing expected fields: {}", trimmed);
                    sender.sendMessage(text("Speedtest failed: incomplete JSON response", SourbyCraftColors.DANGER));
                    return;
                }

                LOG.info("Speedtest parsed: dl={} ul={} ping={} server={}", dl.get("bandwidth"), ul.get("bandwidth"), ping.get("latency"), sv.get("name"));

                double dm = ((Number) dl.get("bandwidth")).doubleValue() * 8 / 1_000_000;
                double um = ((Number) ul.get("bandwidth")).doubleValue() * 8 / 1_000_000;
                double pm = ((Number) ping.get("latency")).doubleValue();

                net.minecraft.server.MinecraftServer.getServer().execute(() -> {
                    try {
                        sender.sendMessage(text()
                            .append(text("DL: ", SourbyCraftColors.LABEL))
                            .append(BarUtil.coloredBar(Math.min(dm / 100, 100), 20))
                            .append(text(String.format(java.util.Locale.ROOT, " %.1f Mbps", dm), SourbyCraftColors.SUCCESS))
                            .append(text("\nUL: ", SourbyCraftColors.LABEL))
                            .append(BarUtil.coloredBar(Math.min(um / 50, 100), 20))
                            .append(text(String.format(java.util.Locale.ROOT, " %.1f Mbps", um), SourbyCraftColors.SUCCESS))
                            .append(text("\nPing: ", SourbyCraftColors.LABEL))
                            .append(text(String.format(java.util.Locale.ROOT, "%.0fms", pm),
                                pm < 30 ? SourbyCraftColors.SUCCESS : SourbyCraftColors.PRIMARY))
                            .append(text(" | " + sv.get("name") + ", " + sv.get("location"), SourbyCraftColors.DIM)));
                    } catch (Exception ex) {
                        LOG.error("Failed to send speedtest result: {}", ex.toString(), ex);
                    }
                });
            } catch (Exception e) {
                LOG.error("Speedtest process failed: {}", e.toString(), e);
                sender.sendMessage(text("Speedtest failed: " + e.getMessage(), SourbyCraftColors.DANGER));
            }
        });
        return true;
    }

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
        HttpResponse<Path> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofFile(tarball));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during speedtest download", e);
        }
        if (resp.statusCode() != 200) {
            Files.deleteIfExists(tarball);
            throw new IOException("HTTP " + resp.statusCode() + " from " + OOKLA_URL);
        }
        try (InputStream raw = Files.newInputStream(tarball)) {
            if (OOKLA_URL.endsWith(".zip")) {
                extractSpeedtestFromZip(raw, BIN);
            } else {
                try (GZIPInputStream gz = new GZIPInputStream(raw)) {
                    extractSpeedtestFromTar(gz, BIN);
                }
            }
        }
        Files.deleteIfExists(tarball);
        if (!Files.exists(BIN)) {
            throw new IOException("speedtest binary missing from extracted archive");
        }
        try {
            BIN.toFile().setExecutable(true);
        } catch (SecurityException ignored) { }
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