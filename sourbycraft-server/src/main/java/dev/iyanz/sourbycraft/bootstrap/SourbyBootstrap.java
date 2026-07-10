package dev.iyanz.sourbycraft.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entry point for the slim SourbyCraft paperclip jar. Reads its bundled manifest,
 * downloads any missing libraries into the paperclip libraries/ dir with SHA-256
 * verification, then delegates to moe.luminolmc.hyacinthusclip.Main.
 *
 * <p>Also runs the Auto-CDS layer (Class Data Sharing) to cut JVM startup time.
 * See {@link #runCds} for the strategy matrix — it adapts to bare metal, Docker,
 * and process-managed panels (Pterodactyl / Pelican) instead of blindly forking.
 *
 * <p>Uses only JDK classes until the delegate call — every externalized library
 * is potentially missing at that point.
 *
 * <p>Hard-fails on any error: prints actionable diagnostics + exits non-zero.
 */
public final class SourbyBootstrap {

    private static final String ORCHESTRATOR_BYPASS = "sourbycraft.orchestrator.bypass";
    private static final String CDS_MODE_PROP = "sourbycraft.cds.mode";
    private static final String CDS_MODE_ENV = "SOURBYCRAFT_CDS_MODE";
    private static final String CDS_PATH_PROP = "sourbycraft.cds.path";
    private static final String CDS_PATH_ENV = "SOURBYCRAFT_CDS_PATH";
    private static final String DEFAULT_CDS_ARCHIVE_PATH = "cache/sourbycraft.jsa";
    private static final String HINT_MARKER = "cache/.sourby-cds-hint";
    private static final String SIMD_HINT_MARKER = "cache/.sourby-simd-hint";

    /** The JDK incubator module Luminol's SIMDConfig auto-uses for vectorized ops (map colors, mob AI, etc.). */
    private static final String SIMD_MODULE = "jdk.incubator.vector";
    private static final String SIMD_ADD_MODULES_FLAG = "--add-modules=" + SIMD_MODULE;

    /** Below this committed initial heap (-Xms) a fork double-commit is negligible. */
    private static final long FORK_SAFE_XMS_BYTES = 256L * 1024 * 1024;

    public static void main(String[] args) throws Throwable {
        // The child (post re-exec) carries the bypass flag and drops straight to boot.
        if (System.getProperty(ORCHESTRATOR_BYPASS) == null
                && System.getenv("SOURBYCRAFT_ORCHESTRATOR_BYPASS") == null) {
            try {
                Integer exit = runCds(args);
                if (exit != null) {
                    System.exit(exit);
                    return;
                }
                // null = boot inline in this JVM (CDS handled by flags, disabled,
                // or intentionally skipped for container/panel safety).
            } catch (Throwable t) {
                System.err.println("[SourbyBootstrap] CDS layer failed (booting inline without CDS): " + t.getMessage());
            }
            // Reaching here means we boot inline in this JVM (container/panel/flag mode). We
            // cannot add --add-modules to a running JVM, so if the SIMD incubator module was
            // not resolved at launch, print a one-time copy-paste hint (mirrors the CDS hint).
            try {
                maybeHintSimd();
            } catch (Throwable ignored) {}
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

        // Folia base uses hyacinthusclip (paperclip fork), not Paper's paperclip.
        Class<?> clipMain = Class.forName("moe.luminolmc.hyacinthusclip.Main");
        clipMain.getMethod("main", String[].class).invoke(null, (Object) args);
    }

    // ------------------------------------------------------------------
    // Auto-CDS
    // ------------------------------------------------------------------

    /**
     * Decides how to apply Class Data Sharing and returns the child exit code
     * when it re-execs, or {@code null} when the caller should boot inline.
     *
     * <p>Strategy matrix (mode {@code auto}, the default). Environment classification is
     * delegated to {@link CdsEnvironment}, an ordered detector chain covering panels
     * (Pterodactyl / Pelican / generic), containers (Docker / Podman / LXC / OpenVZ /
     * containerd), orchestrators (Kubernetes / Nomad), service managers (systemd), CI
     * runners, and cgroup v1/v2 memory caps — with WSL and uncapped cloud VMs classified
     * as fork-permitted:
     * <ul>
     *   <li>Operator already passed a CDS/AOT flag → inline, respect it.</li>
     *   <li>An environment whose policy is {@code INLINE} (any capped/managed/ephemeral
     *       case above), <em>or</em> a large committed heap ({@code -Xms} &ge; 256M) →
     *       <b>do not fork</b>. Forking a second JVM there double-commits the heap
     *       (OOM-kill risk under a cgroup limit) and/or hides the real server behind a
     *       wrapper PID (panel/systemd/orchestrator memory graphs + stop signals target
     *       the wrong process). Boot inline and print a one-time, copy-paste flag hint
     *       for zero-overhead single-JVM CDS instead.</li>
     *   <li>Otherwise (bare metal, WSL, uncapped cloud VM — all with small/absent
     *       {@code -Xms}) → fork one child with {@code -XX:+AutoCreateSharedArchive}
     *       (JDK 19+, single pass: creates the archive on first clean shutdown, uses +
     *       self-heals it afterwards).</li>
     * </ul>
     *
     * <p>Modes via {@code -Dsourbycraft.cds.mode} / {@code $SOURBYCRAFT_CDS_MODE}:
     * {@code auto} (default), {@code flag} (never fork, always print the hint),
     * {@code fork} (always fork, legacy/bare-metal), {@code off} (no CDS).
     */
    private static Integer runCds(String[] args) throws Throwable {
        String mode = firstNonNull(System.getProperty(CDS_MODE_PROP), System.getenv(CDS_MODE_ENV), "auto")
                .trim().toLowerCase(Locale.ROOT);
        if (mode.equals("off")) return null;

        java.lang.management.RuntimeMXBean rt = java.lang.management.ManagementFactory.getRuntimeMXBean();
        List<String> jvmArgs = rt.getInputArguments();

        // Respect any operator-provided CDS / AOT cache flag.
        for (String a : jvmArgs) {
            if (a.startsWith("-XX:SharedArchiveFile")
                    || a.startsWith("-XX:ArchiveClassesAtExit")
                    || a.startsWith("-XX:+AutoCreateSharedArchive")
                    || a.startsWith("-XX:AOTCache")
                    || a.startsWith("-XX:AOTMode")) {
                System.out.println("[SourbyBootstrap] operator CDS/AOT flag present — leaving startup archive to the JVM");
                return null;
            }
        }

        Path archivePath = resolveArchivePath();
        Path archiveDir = archivePath.getParent();
        if (archiveDir != null && !Files.exists(archiveDir)) Files.createDirectories(archiveDir);

        if (mode.equals("flag")) {
            printFlagHint(archivePath, CdsEnvironment.label());
            return null;
        }

        boolean forceFork = mode.equals("fork");
        java.util.Optional<CdsEnvironment.Detection> detected = CdsEnvironment.classify();
        long xms = committedInitialHeapBytes(jvmArgs);
        boolean bigCommittedHeap = xms >= FORK_SAFE_XMS_BYTES;

        // A detected environment forbids forking when its policy is INLINE (capped container,
        // panel, orchestrator, service manager, CI). Environments that permit forking
        // (bare metal, WSL, uncapped cloud VM) still fall through to the -Xms headroom check.
        boolean envForbidsFork = detected.isPresent() && !detected.get().canFork();

        if (!forceFork && (envForbidsFork || bigCommittedHeap)) {
            // Unsafe/wasteful to fork here — hint the single-JVM flag instead.
            String why;
            if (envForbidsFork) {
                why = detected.get().label() + " detected";
            } else {
                // Fork-permitted env (or bare metal) but a large committed heap would be doubled.
                String envNote = detected.map(d -> d.label() + ", ").orElse("");
                why = envNote + "committed heap -Xms=" + human(xms) + " (fork would double it)";
            }
            printFlagHintOnce(archivePath, why);
            return null;
        }

        return fork(args, archivePath, jvmArgs, detected.map(CdsEnvironment.Detection::label).orElse(null));
    }

    /** Forks one child JVM with single-pass AutoCreateSharedArchive; returns its exit code. */
    private static Integer fork(String[] args, Path archivePath, List<String> jvmArgs, String envLabel) throws Throwable {
        String javaCmd = ProcessHandle.current().info().command()
                .orElse(System.getProperty("java.home") + "/bin/java");
        String ownJar = SourbyBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath();

        List<String> cmd = new ArrayList<>();
        cmd.add(javaCmd);
        boolean haveStdoutEnc = false, haveStderrEnc = false, haveFileEnc = false, haveSimdModule = false;
        for (String a : jvmArgs) {
            if (a.startsWith("-agentlib:jdwp")) continue; // port re-use on re-exec
            if (a.startsWith("-Dstdout.encoding=")) haveStdoutEnc = true;
            else if (a.startsWith("-Dstderr.encoding=")) haveStderrEnc = true;
            else if (a.startsWith("-Dfile.encoding=")) haveFileEnc = true;
            else if (mentionsSimdModule(a)) haveSimdModule = true;
            cmd.add(a);
        }
        // Force UTF-8 console + file encoding in the forked child. The child is launched from
        // this rebuilt arg list (RuntimeMXBean input args), which omits JVM defaults — so when
        // the parent runs under a C / POSIX locale, stdout.encoding would otherwise fall back to
        // US-ASCII and the branded box-drawing chars (U+2550 ═, …) print as ? / �. Only add each
        // flag if the operator did not already set it, so an explicit choice always wins.
        if (!haveStdoutEnc) cmd.add("-Dstdout.encoding=UTF-8");
        if (!haveStderrEnc) cmd.add("-Dstderr.encoding=UTF-8");
        if (!haveFileEnc) cmd.add("-Dfile.encoding=UTF-8");
        // Resolve the SIMD incubator module in the forked child so Luminol's SIMDConfig
        // auto-uses vectorized ops (map colors, mob AI). The module ships with the JDK; it
        // is only unavailable to code until --add-modules makes it resolvable. Fork boots
        // (bare metal) thus get SIMD for free, silencing the "not configured" warning. Only
        // add it when the operator did not already pass an --add-modules for it.
        if (!haveSimdModule) cmd.add(SIMD_ADD_MODULES_FLAG);
        // JDK 19+: create-on-miss, use-on-hit, and recreate automatically when the
        // archive is stale (jar/JDK changed). No manual fingerprint bookkeeping.
        cmd.add("-XX:+AutoCreateSharedArchive");
        cmd.add("-XX:SharedArchiveFile=" + archivePath);
        cmd.add("-D" + ORCHESTRATOR_BYPASS + "=1");
        cmd.add("-jar");
        cmd.add(ownJar);
        cmd.addAll(java.util.Arrays.asList(args));

        boolean have = Files.isRegularFile(archivePath) && Files.size(archivePath) > 0;
        String forkCtx = envLabel != null ? envLabel + " fork, headroom" : "bare-metal fork";
        System.out.println("[SourbyBootstrap] Auto-CDS " + (have ? "using" : "creating")
                + " archive " + archivePath + " (" + forkCtx + ")");
        if (!have) {
            System.out.println("[SourbyBootstrap] first boot writes the archive on clean /stop; "
                + "later boots start ~30-50% faster.");
        }

        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        Process child = pb.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (child.isAlive()) {
                    child.destroy(); // SIGTERM → child /stop
                    if (!child.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) child.destroyForcibly();
                }
            } catch (Throwable ignored) {}
        }, "SourbyBootstrap-Shutdown-Forwarder"));
        return child.waitFor();
    }

    /** Prints the copy-paste single-JVM CDS flag exactly once (marker-suppressed). */
    private static void printFlagHintOnce(Path archivePath, String why) {
        Path marker = Paths.get(HINT_MARKER);
        boolean shown = Files.isRegularFile(marker);
        if (!shown) {
            printFlagHint(archivePath, why);
            try {
                Path p = marker.getParent();
                if (p != null && !Files.exists(p)) Files.createDirectories(p);
                Files.writeString(marker, why + System.lineSeparator());
            } catch (Throwable ignored) {}
        }
    }

    private static void printFlagHint(Path archivePath, String why) {
        String flags = "-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=" + archivePath;
        System.out.println("[SourbyBootstrap] Auto-CDS: not forking"
                + (why != null ? " (" + why + ")" : "") + ".");
        System.out.println("[SourbyBootstrap] For faster startup with zero extra process, add to your JVM flags:");
        System.out.println("[SourbyBootstrap]     " + flags);
        System.out.println("[SourbyBootstrap] (Pterodactyl/Pelican: prepend to the Startup command's JAVA flags; "
                + "Docker: already baked into the reference Dockerfile. See docs/CDS.md.)");
    }

    // ------------------------------------------------------------------
    // SIMD (jdk.incubator.vector) hint — inline boot only
    // ------------------------------------------------------------------

    /** True when the arg is an {@code --add-modules} that already lists the SIMD incubator module. */
    private static boolean mentionsSimdModule(String arg) {
        if (arg == null) return false;
        // Forms: "--add-modules=a,jdk.incubator.vector,b" or "--add-modules a,..." (space form
        // is two tokens; the value token is then a bare "jdk.incubator.vector"-containing string).
        if (arg.startsWith("--add-modules")) return arg.contains(SIMD_MODULE) || arg.equals("--add-modules");
        return arg.equals(SIMD_MODULE) || arg.startsWith(SIMD_MODULE + ",") || arg.contains("," + SIMD_MODULE);
    }

    /**
     * On the inline boot path we cannot self-add {@code --add-modules} to a running JVM. If the
     * SIMD incubator module is not resolved, print a one-time copy-paste hint so operators on
     * containers/panels can enable Luminol's vectorized optimizations. No-op (silent) when the
     * module is already present — the boot then resolves SIMD and Luminol logs it as functional.
     */
    private static void maybeHintSimd() {
        boolean present = ModuleLayer.boot().findModule(SIMD_MODULE).isPresent();
        if (present) return; // SIMDConfig will detect + enable it; nothing to hint.

        Path marker = Paths.get(SIMD_HINT_MARKER);
        if (Files.isRegularFile(marker)) return; // already hinted once
        String env = CdsEnvironment.label();
        System.out.println("[SourbyBootstrap] SIMD: the jdk.incubator.vector module is not loaded"
                + (env != null ? " (" + env + ")" : "") + " — Luminol's vectorized optimizations are off.");
        System.out.println("[SourbyBootstrap] To enable them, add this to your JVM flags, BEFORE \"-jar\":");
        System.out.println("[SourbyBootstrap]     " + SIMD_ADD_MODULES_FLAG);
        System.out.println("[SourbyBootstrap] (Pterodactyl/Pelican: prepend to the Startup command's JAVA flags; "
                + "Docker: already baked into the reference Dockerfile.)");
        try {
            Path p = marker.getParent();
            if (p != null && !Files.exists(p)) Files.createDirectories(p);
            Files.writeString(marker, (env != null ? env : "inline") + System.lineSeparator());
        } catch (Throwable ignored) {}
    }

    private static Path resolveArchivePath() {
        String override = firstNonNull(System.getProperty(CDS_PATH_PROP), System.getenv(CDS_PATH_ENV), null);
        return Paths.get(override != null && !override.isBlank() ? override : DEFAULT_CDS_ARCHIVE_PATH);
    }

    /** Parses committed initial heap from -Xms / -XX:InitialHeapSize; -1 if unset. */
    private static long committedInitialHeapBytes(List<String> args) {
        long xms = -1;
        for (String a : args) {
            if (a.startsWith("-Xms")) xms = parseSize(a.substring(4));
            else if (a.startsWith("-XX:InitialHeapSize=")) xms = parseSize(a.substring("-XX:InitialHeapSize=".length()));
        }
        return xms;
    }

    private static long parseSize(String s) {
        if (s == null) return -1;
        s = s.trim();
        if (s.isEmpty()) return -1;
        long mult = 1;
        char c = Character.toLowerCase(s.charAt(s.length() - 1));
        if (c == 'k') mult = 1024L;
        else if (c == 'm') mult = 1024L * 1024;
        else if (c == 'g') mult = 1024L * 1024 * 1024;
        else if (c == 't') mult = 1024L * 1024 * 1024 * 1024;
        String num = (mult == 1) ? s : s.substring(0, s.length() - 1);
        try {
            return Long.parseLong(num.trim()) * mult;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String human(long bytes) {
        if (bytes < 0) return "?";
        if (bytes >= 1024L * 1024 * 1024) return (bytes / (1024L * 1024 * 1024)) + "G";
        if (bytes >= 1024L * 1024) return (bytes / (1024L * 1024)) + "M";
        return bytes + "B";
    }

    private static String firstNonNull(String a, String b, String c) {
        if (a != null && !a.isBlank()) return a;
        if (b != null && !b.isBlank()) return b;
        return c;
    }

    // ------------------------------------------------------------------
    // Manifest
    // ------------------------------------------------------------------

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
