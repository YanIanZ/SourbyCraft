package dev.iyanz.sourbycraft.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entry point for the slim SourbyCraft paperclip jar. Reads its bundled manifest,
 * downloads any missing libraries into the paperclip libraries/ dir with SHA-256
 * verification, then delegates to io.papermc.paperclip.Main.
 *
 * <p>Uses only JDK classes until the delegate call — every externalized library
 * is potentially missing at that point.
 *
 * <p>Hard-fails on any error: prints actionable diagnostics + exits non-zero.
 */
public final class SourbyBootstrap {

    private static final String ORCHESTRATOR_BYPASS = "sourbycraft.orchestrator.bypass";
    private static final String CDS_ARCHIVE_PATH = "cache/sourbycraft.jsa";

    public static void main(String[] args) throws Throwable {
        // Auto-CDS orchestrator. First-time + missing archive flow:
        //   1. operator runs `java -jar SourbyCraft-...jar`
        //   2. orchestrator detects no CDS flag + missing archive
        //   3. fork child JVM with -XX:ArchiveClassesAtExit=cache/sourbycraft.jsa
        //   4. child runs server normally; on /stop, JVM writes archive
        //   5. orchestrator parent waits + propagates exit code
        // Subsequent boots:
        //   1. orchestrator sees cache/sourbycraft.jsa exists
        //   2. forks child with -XX:SharedArchiveFile=cache/sourbycraft.jsa
        //   3. child boots ~30-50% faster from mmap'd class data
        if (System.getProperty(ORCHESTRATOR_BYPASS) == null
                && System.getenv("SOURBYCRAFT_ORCHESTRATOR_BYPASS") == null) {
            try {
                Integer orchExit = runOrchestrator(args);
                if (orchExit != null) {
                    System.exit(orchExit);
                    return;
                }
                // null means orchestrator detected operator-provided CDS flag
                // and chose to skip — fall through to inline boot.
            } catch (Throwable t) {
                System.err.println("[SourbyBootstrap] orchestrator failed (running inline without CDS): " + t.getMessage());
                // Fall through to inline boot — server still works, just no CDS.
            }
        }
        Path librariesDir = Paths.get("libraries");
        Files.createDirectories(librariesDir);

        BootstrapManifest manifest;
        try {
            manifest = loadManifest();
        } catch (IOException e) {
            System.err.println("[SourbyBootstrap] FATAL: cannot read bundled manifest: " + e.getMessage());
            System.exit(2);
            return;
        }

        long startNs = System.nanoTime();
        int downloaded = 0;
        long totalBytes = 0;
        for (BootstrapManifest.Entry entry : manifest.entries()) {
            try {
                if (LibDownloader.ensure(entry, librariesDir)) {
                    downloaded++;
                    totalBytes += entry.sizeBytes();
                    System.out.println("[SourbyBootstrap] downloaded "
                        + entry.paperclipPath()
                        + " (" + (entry.sizeBytes() / 1024 / 1024) + "M)");
                }
            } catch (IOException e) {
                System.err.println("[SourbyBootstrap] FATAL: cannot fetch "
                    + entry.paperclipPath() + " from " + entry.downloadUrl()
                    + ": " + e.getMessage());
                System.err.println("[SourbyBootstrap] If your server has no internet access on first boot,");
                System.err.println("[SourbyBootstrap] download the libraries manually and place them at:");
                for (BootstrapManifest.Entry e2 : manifest.entries()) {
                    System.err.println("[SourbyBootstrap]   libraries/" + e2.paperclipPath()
                        + "  <-  " + e2.downloadUrl());
                }
                System.exit(3);
                return;
            }
        }
        if (downloaded > 0) {
            long secs = (System.nanoTime() - startNs) / 1_000_000_000L;
            System.out.println("[SourbyBootstrap] downloaded " + downloaded + " libraries ("
                + (totalBytes / 1024 / 1024) + "M) in " + secs + "s");
        }

        Class<?> paperclipMain = Class.forName("io.papermc.paperclip.Main");
        paperclipMain.getMethod("main", String[].class).invoke(null, (Object) args);
    }

    /**
     * Forks a child JVM with CDS flags + waits for completion. Returns child
     * exit code. Parent JVM stays alive as orchestrator (~30MB footprint;
     * Pterodactyl sees parent PID; signals propagate via shutdown hook).
     */
    private static Integer runOrchestrator(String[] args) throws Throwable {
        Path archivePath = Paths.get(CDS_ARCHIVE_PATH);
        Path archiveDir = archivePath.getParent();
        if (archiveDir != null && !Files.exists(archiveDir)) Files.createDirectories(archiveDir);

        java.lang.management.RuntimeMXBean rt = java.lang.management.ManagementFactory.getRuntimeMXBean();
        java.util.List<String> existingJvmArgs = rt.getInputArguments();
        // If operator already passed a CDS flag, respect it — skip orchestrator.
        for (String a : existingJvmArgs) {
            if (a.startsWith("-XX:SharedArchiveFile") || a.startsWith("-XX:ArchiveClassesAtExit")) {
                System.out.println("[SourbyBootstrap] operator-provided CDS flag detected, skipping orchestrator");
                return null; // null = caller falls through to inline boot
            }
        }

        // Discover java binary + own jar path.
        String javaCmd = ProcessHandle.current().info().command()
                .orElse(System.getProperty("java.home") + "/bin/java");
        String ownJar = SourbyBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();

        boolean haveArchive = Files.isRegularFile(archivePath) && Files.size(archivePath) > 0;
        String cdsFlag = haveArchive
                ? "-XX:SharedArchiveFile=" + archivePath
                : "-XX:ArchiveClassesAtExit=" + archivePath;

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(javaCmd);
        for (String a : existingJvmArgs) {
            // Drop flags incompatible with re-exec (jdwp port re-use, agent
            // re-attach) but keep all heap / GC / system property flags.
            if (a.startsWith("-agentlib:jdwp")) continue;
            cmd.add(a);
        }
        cmd.add(cdsFlag);
        cmd.add("-D" + ORCHESTRATOR_BYPASS + "=1");
        cmd.add("-jar");
        cmd.add(ownJar);
        cmd.addAll(java.util.Arrays.asList(args));

        System.out.println("[SourbyBootstrap] orchestrator " + (haveArchive ? "USING" : "GENERATING")
                + " CDS archive " + archivePath);
        if (!haveArchive) {
            System.out.println("[SourbyBootstrap] first boot — archive will be written on /stop. "
                + "Subsequent boots will be 30-50% faster.");
        }

        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        Process child = pb.start();
        // Forward TERM/INT to child so /stop propagates from Pterodactyl.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (child.isAlive()) {
                    child.destroy();
                    if (!child.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                        child.destroyForcibly();
                    }
                }
            } catch (Throwable ignored) {}
        }, "SourbyBootstrap-Shutdown-Forwarder"));
        return child.waitFor();
    }

    static BootstrapManifest loadManifest() throws IOException {
        try (InputStream in = SourbyBootstrap.class
                .getResourceAsStream("/META-INF/sourby-bootstrap-manifest.json")) {
            if (in == null) throw new IOException("META-INF/sourby-bootstrap-manifest.json not found in jar");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parse(json);
        }
    }

    /** Minimal regex-based parser. Manifest shape is deterministic (gradle-generated). */
    static BootstrapManifest parse(String json) throws IOException {
        Pattern entryPat = Pattern.compile(
            "\\{\\s*\"paperclipPath\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*"
            + "\"downloadUrl\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*"
            + "\"sha256\"\\s*:\\s*\"([0-9a-fA-F]{64})\"\\s*,\\s*"
            + "\"sizeBytes\"\\s*:\\s*(\\d+)\\s*\\}");
        List<BootstrapManifest.Entry> entries = new ArrayList<>();
        Matcher m = entryPat.matcher(json);
        while (m.find()) {
            entries.add(new BootstrapManifest.Entry(
                m.group(1), m.group(2), m.group(3).toLowerCase(java.util.Locale.ROOT),
                Long.parseLong(m.group(4))));
        }
        if (entries.isEmpty()) throw new IOException("manifest has no entries (parse failed?)");
        return new BootstrapManifest(List.copyOf(entries));
    }
}
