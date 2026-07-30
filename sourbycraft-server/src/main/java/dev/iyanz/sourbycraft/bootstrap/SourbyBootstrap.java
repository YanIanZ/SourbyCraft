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
 * verification, then delegates to dev.iyanz.sourbyclip.Main.
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

    /**
     * At/above this committed initial heap (-Xms) the forked child prefers ZGC generational over
     * G1 — this is {@link dev.iyanz.sourbycraft.brand.GcAdvisor}'s advice (SourbyCraft is tuned for
     * ZGC generational). Below it, large-heap-oriented Aikar G1 is the safer default.
     */
    private static final long ZGC_PREFER_XMS_BYTES = 8L * 1024 * 1024 * 1024;

    /**
     * Aikar-style G1 flags — a verbatim mirror of {@code GC_FLAGS} in {@code docker/entrypoint.sh}.
     * Heap-agnostic, pure-efficiency GC tuning with zero gameplay impact; applied to the forked
     * bare-metal child when the operator has NOT chosen a GC. Docker already bakes these in at
     * launch, so this closes the bare-metal parity gap (previously the fork ran an untuned GC).
     */
    private static final String[] AIKAR_G1_FLAGS = {
        "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200",
        "-XX:+UnlockExperimentalVMOptions", "-XX:+DisableExplicitGC", "-XX:+AlwaysPreTouch",
        "-XX:G1NewSizePercent=30", "-XX:G1MaxNewSizePercent=40", "-XX:G1HeapRegionSize=8M",
        "-XX:G1ReservePercent=20", "-XX:G1HeapWastePercent=5", "-XX:G1MixedGCCountTarget=4",
        "-XX:InitiatingHeapOccupancyPercent=15", "-XX:G1MixedGCLiveThresholdPercent=90",
        "-XX:G1RSetUpdatingPauseTimePercent=5", "-XX:SurvivorRatio=32",
        "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1",
    };

    /**
     * ZGC generational flags (GcAdvisor's recommended GC). {@code -XX:+ZGenerational} needs
     * {@code -XX:+UnlockExperimentalVMOptions} on some JDKs; harmless when already unlocked.
     * Preferred over G1 when the committed heap is large ({@code -Xms >= 8G}).
     */
    private static final String[] ZGC_GENERATIONAL_FLAGS = {
        // ZGC is generational-by-default since JDK 23 and -XX:+ZGenerational was REMOVED in 24
        // (passing it only produces a boot warning on our Java 25 floor). No AlwaysPreTouch here:
        // pre-touching commits the whole heap as resident up-front, which is exactly what panel
        // operators read as "RAM usage" — let ZGC commit on demand and RETURN idle pages to the
        // OS (ZUncommit is on by default; the delay just makes it responsive).
        "-XX:+UnlockExperimentalVMOptions", "-XX:+UseZGC",
        "-XX:+DisableExplicitGC", "-XX:ZUncommitDelay=60",
    };

    /**
     * Prefixes that mean the operator has already chosen a garbage collector. When ANY is present
     * in the launch args, the fork inherits it untouched — we never override an explicit GC choice.
     */
    private static final String[] OPERATOR_GC_PREFIXES = {
        "-XX:+UseG1GC", "-XX:+UseZGC", "-XX:+UseZ", "-XX:+UseParallelGC",
        "-XX:+UseShenandoahGC", "-XX:+UseSerialGC", "-XX:+UseEpsilonGC",
    };

    /**
     * Entry point of the slim SourbyCraft jar. In order: auto-accept the EULA, finish any
     * fallback-staged auto-update swap, self-heal a GC-stale CDS archive, run the Auto-CDS layer
     * (which may re-exec into a forked child and never return), download+verify any manifest
     * libraries the slim jar omitted, provision the built-in ViaVersion/ViaBackwards jars, then
     * delegate to {@code dev.iyanz.sourbyclip.Main}. Hard-fails with actionable diagnostics on any
     * unrecoverable error (missing manifest, library fetch failure).
     */
    public static void main(String[] args) throws Throwable {
        // Auto-accept the Mojang EULA. SourbyCraft accepts it on the operator's behalf so first
        // boot is not blocked on hand-editing eula.txt. Idempotent: an existing eula=true is left
        // untouched; only a missing file or an explicit eula=false is rewritten to eula=true. Runs
        // before the CDS fork so the (possibly re-exec'd) child inherits an already-accepted EULA.
        try {
            autoAcceptEula();
        } catch (Throwable t) {
            System.err.println("[SourbyBootstrap] could not auto-accept EULA: " + t.getMessage());
        }

        // Staged-update fallback swap (auto-updater). The in-server apply performs an atomic
        // rename while running; when that was impossible (Windows file lock, unknown launch jar),
        // auto_update/core.path survives and THIS is the moment to finish the job: the previous
        // server process is gone, nothing holds the jar, and we can rename the staged jar over it.
        // This boot still runs the old bytes (the JVM already holds its open file); the next
        // restart runs the new build. JDK-only, never fatal.
        try {
            applyStagedUpdateIfPresent();
        } catch (Throwable t) {
            System.err.println("[SourbyBootstrap] staged-update swap failed (continuing with current jar): " + t);
        }

        // Self-heal a GC-stale CDS archive. A .jsa created under one GC (e.g. G1, compressed oops on)
        // is incompatible under another (e.g. ZGC, compressed oops off) — the JVM logs "Cannot use CDS
        // heap data. Selected GC not compatible" and drops the archive, so CDS never helps. When the
        // GC changed since the archive was written, delete it here so -XX:+AutoCreateSharedArchive
        // regenerates a clean one under the current GC on this run's exit. JDK-only, never fatal.
        try {
            healStaleCdsArchive();
        } catch (Throwable t) {
            System.err.println("[SourbyBootstrap] CDS archive heal check failed (non-fatal): " + t);
        }

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

        // SourbyCraft — built-in ViaVersion/ViaBackwards. Provision the pinned, SHA-256-verified
        // jars into plugins/ NOW, before we hand control to the clip -> net.minecraft.server.Main,
        // whose PluginInitializerManager.load(options) scans plugins/ exactly once, very early. A
        // jar dropped in after that scan would be invisible until the next restart, so this must run
        // here (bootstrap phase, JDK-only) rather than from the later post-config hook. Idempotent
        // and toggle-gated (viaversion.auto-provision); wrapped so a failure never blocks boot. The
        // matching default configs (with the 1.20 client floor) are seeded from PerfEngineBootstrap
        // at the post-config hook, before Via reads its config in onEnable.
        try {
            PluginProvisioner.provisionJars();
        } catch (Throwable t) {
            System.err.println("[SourbyBootstrap] ViaVersion auto-provision (jars) failed: " + t.getMessage());
        }

        // Folia base uses sourbyclip (our rebranded hyacinthusclip paperclip fork), not Paper's paperclip.
        Class<?> clipMain = Class.forName("dev.iyanz.sourbyclip.Main");
        clipMain.getMethod("main", String[].class).invoke(null, (Object) args);
    }

    // ------------------------------------------------------------------
    // EULA auto-accept
    // ------------------------------------------------------------------

    /**
     * Auto-accept the Mojang EULA in {@code eula.txt} on the operator's behalf.
     *
     * <p>Idempotent and conservative: if the file already contains {@code eula=true} (any
     * whitespace/case), it is left byte-for-byte untouched. Otherwise — the file is absent, or
     * present with {@code eula=false} / no {@code eula=} line — it is (re)written with
     * {@code eula=true}, a one-line comment noting SourbyCraft auto-accepted the Mojang EULA, and a
     * pointer to the EULA URL. JDK-only (runs during the bootstrap phase before any library load).
     */
    private static void autoAcceptEula() throws IOException {
        Path eula = Paths.get("eula.txt");
        if (Files.isRegularFile(eula)) {
            String body = Files.readString(eula, StandardCharsets.UTF_8);
            if (alreadyAccepted(body)) {
                return; // respect an existing eula=true — do not rewrite.
            }
        }
        String content = "# SourbyCraft auto-accepted the Mojang EULA (https://aka.ms/MinecraftEULA)."
            + System.lineSeparator()
            + "eula=true" + System.lineSeparator();
        Files.writeString(eula, content, StandardCharsets.UTF_8);
        System.out.println("[SourbyBootstrap] auto-accepted the Mojang EULA (eula=true written to eula.txt; "
            + "https://aka.ms/MinecraftEULA).");
    }

    /** True if the eula.txt body already has an active {@code eula=true} line (ignoring comments/case/ws). */
    private static boolean alreadyAccepted(String body) {
        for (String raw : body.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String key = line.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String val = line.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
            if (key.equals("eula")) {
                return val.equals("true");
            }
        }
        return false;
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

    /** Consume auto_update/core.path: rename the verified staged jar over the launch jar. */
    private static void applyStagedUpdateIfPresent() throws Exception {
        Path corePath = Paths.get("auto_update", "core.path");
        if (!Files.isRegularFile(corePath)) return;
        Path staged = Paths.get(new String(Files.readAllBytes(corePath), StandardCharsets.UTF_8).trim());
        if (!Files.isRegularFile(staged)) {
            Files.deleteIfExists(corePath);
            return;
        }
        // The updater verified size + SHA-256 at stage time; re-check basic zip validity here so a
        // corrupted stage can never brick the launch jar.
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(staged.toFile())) {
            if (!zf.entries().hasMoreElements()) {
                System.err.println("[SourbyBootstrap] staged update is not a valid jar — ignoring " + staged);
                return;
            }
        }
        Path ownJar = Paths.get(SourbyBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!Files.isRegularFile(ownJar) || Files.isSameFile(staged, ownJar)) {
            Files.deleteIfExists(corePath);
            return;
        }
        Files.move(staged, ownJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(corePath);
        Path pending = Paths.get("auto_update", "pending.tag");
        if (Files.isRegularFile(pending)) {
            Files.move(pending, Paths.get("auto_update", "applied.tag"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("[SourbyBootstrap] applied staged SourbyCraft update to " + ownJar
            + " — this boot still runs the previous build; the NEXT restart runs the new one.");
    }

    /** Forks one child JVM with single-pass AutoCreateSharedArchive; returns its exit code. */
    private static Integer fork(String[] args, Path archivePath, List<String> jvmArgs, String envLabel) throws Throwable {
        String javaCmd = ProcessHandle.current().info().command()
                .orElse(System.getProperty("java.home") + "/bin/java");
        // Path.of(URI), not URI.getPath(): the latter yields "/C:/..." on Windows, which the child
        // JVM's -jar cannot open — the fork would exit 1 and hard-fail the boot on Windows bare metal.
        String ownJar = java.nio.file.Path.of(
            SourbyBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();

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
        // Bare-metal GC parity with Docker. docker/entrypoint.sh launches java with Aikar G1 flags,
        // but this bare-metal fork path historically added only CDS + encoding + SIMD, leaving the
        // child on an untuned default GC (GcAdvisor only *recommended* a better one). Apply GC tuning
        // to the child ONLY when the operator has not chosen a GC (an explicit choice always wins).
        // Pure-efficiency, zero gameplay impact.
        applyGcFlags(cmd, jvmArgs);
        // RAM right-sizing for the child: with no explicit heap cap the JVM defaults to 25% of
        // container/host RAM (tiny heap, wasted allocation). 75% leaves OS/off-heap headroom.
        boolean haveXmx = false;
        for (String a : jvmArgs) {
            if (a.startsWith("-Xmx") || a.startsWith("-XX:MaxHeapSize") || a.startsWith("-XX:MaxRAMPercentage")) {
                haveXmx = true;
                break;
            }
        }
        if (!haveXmx) cmd.add("-XX:MaxRAMPercentage=75");
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

    /**
     * Add GC tuning to the forked child command when the operator has NOT already chosen a GC.
     *
     * <p>Bare-metal parity with {@code docker/entrypoint.sh}: Docker ships Aikar G1 at launch, but
     * the bare-metal fork previously ran an untuned default GC. This appends a real GC to the child:
     * <ul>
     *   <li>a large committed heap ({@code -Xms >= 8G}) → <b>ZGC generational</b> (GcAdvisor's
     *       recommended GC — SourbyCraft is tuned for it, low pause on big heaps);</li>
     *   <li>otherwise → <b>Aikar-style G1</b>, a verbatim mirror of the Docker {@code GC_FLAGS}
     *       (heap-agnostic, predictable pauses on typical heaps).</li>
     * </ul>
     * Either choice is a pure-efficiency GC change with zero gameplay effect. An operator-provided
     * GC (any of {@link #OPERATOR_GC_PREFIXES}) is respected untouched — those args are already in
     * {@code cmd} from the inherited {@code jvmArgs}, so we simply skip adding ours. Logs the choice.
     */
    private static void applyGcFlags(List<String> cmd, List<String> jvmArgs) {
        if (operatorHasGc(jvmArgs)) {
            System.out.println("[SourbyBootstrap] fork: operator GC flag present — leaving GC tuning to the operator");
            return;
        }
        long xms = committedInitialHeapBytes(jvmArgs);
        final String[] flags;
        final String which;
        if (xms >= ZGC_PREFER_XMS_BYTES) {
            flags = ZGC_GENERATIONAL_FLAGS;
            which = "ZGC generational (committed heap -Xms=" + human(xms) + " >= 8G; GcAdvisor's advice)";
        } else {
            flags = AIKAR_G1_FLAGS;
            which = "Aikar-style G1 (mirrors docker/entrypoint.sh GC_FLAGS)";
        }
        for (String f : flags) {
            // -XX:+UnlockExperimentalVMOptions may appear in both the ZGC set and inherited args;
            // the JVM tolerates the duplicate, but avoid re-adding one already inherited to stay tidy.
            if (f.equals("-XX:+UnlockExperimentalVMOptions") && cmd.contains(f)) continue;
            cmd.add(f);
        }
        System.out.println("[SourbyBootstrap] fork: applied GC tuning — " + which);
    }

    /** True when the launch args already select a garbage collector (any {@link #OPERATOR_GC_PREFIXES}). */
    private static boolean operatorHasGc(List<String> jvmArgs) {
        for (String a : jvmArgs) {
            for (String p : OPERATOR_GC_PREFIXES) {
                if (a.startsWith(p)) return true;
            }
        }
        return false;
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

    /**
     * Delete the CDS archive when the GC changed since it was written, so the JVM regenerates a
     * clean, GC-compatible one. CDS archived HEAP objects are GC/compressed-oops-specific: an
     * archive baked under G1 (compressed oops) can't be mapped under ZGC (no compressed oops),
     * producing "Cannot use CDS heap data. Selected GC not compatible" every boot and disabling
     * the archive entirely. A tiny marker file records the GC the archive was made under; a
     * mismatch triggers a one-time delete + marker rewrite. Only acts when AutoCreateSharedArchive
     * is in play (the JVM will recreate the archive) so we never leave the operator archive-less.
     */
    private static void healStaleCdsArchive() {
        List<String> jvmArgs = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
        boolean autoCreate = false;
        Path archive = null;
        for (String a : jvmArgs) {
            if (a.startsWith("-XX:+AutoCreateSharedArchive")) autoCreate = true;
            else if (a.startsWith("-XX:SharedArchiveFile=")) archive = Paths.get(a.substring("-XX:SharedArchiveFile=".length()).trim());
        }
        // If we are the ones adding the flags (fork path with no operator CDS flag), use the default.
        if (archive == null && autoCreate) archive = resolveArchivePath();
        if (!autoCreate || archive == null) return;

        // Current GC name (first collector bean's family: G1 / ZGC / Shenandoah / Parallel / ...).
        String gc = "unknown";
        for (var bean : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            String n = bean.getName();
            if (n.contains("ZGC") || n.contains("Z ")) { gc = "zgc"; break; }
            if (n.contains("G1")) { gc = "g1"; break; }
            if (n.contains("Shenandoah")) { gc = "shenandoah"; break; }
            if (n.contains("Parallel") || n.contains("PS ")) { gc = "parallel"; break; }
            if (n.contains("Serial") || n.contains("Copy") || n.contains("MarkSweep")) { gc = "serial"; break; }
        }
        try {
            Path marker = archive.resolveSibling(archive.getFileName() + ".gc");
            String prev = Files.isRegularFile(marker) ? Files.readString(marker, StandardCharsets.UTF_8).trim() : null;
            if (prev != null && !prev.equals(gc) && Files.isRegularFile(archive)) {
                Files.deleteIfExists(archive);
                System.out.println("[SourbyBootstrap] CDS archive was built under '" + prev + "' but the GC is now '"
                    + gc + "' — deleted " + archive.getFileName() + " so it regenerates clean (removes the "
                    + "'Cannot use CDS heap data' warning).");
            }
            Path pdir = marker.getParent();
            if (pdir != null && !Files.exists(pdir)) Files.createDirectories(pdir);
            Files.writeString(marker, gc + System.lineSeparator());
        } catch (Throwable ignored) {
            // Best-effort: a failed heal just leaves the benign warning, never blocks boot.
        }
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
