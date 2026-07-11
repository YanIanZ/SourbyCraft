package dev.iyanz.sourbycraft.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;

/**
 * Auto-provisions the built-in ViaVersion + ViaBackwards plugins so old clients (down to 1.20)
 * can join the native 26.2 (MC 1.21.9, protocol 776) server without the operator installing
 * anything manually.
 *
 * <p><b>Two phases, two call sites (ordering matters).</b> On modern Paper/Folia the plugin
 * directory is <em>scanned once, very early</em> — {@code PluginInitializerManager.load(options)}
 * in {@code net.minecraft.server.Main.main}, before {@code DedicatedServer#initServer} even runs.
 * A jar dropped in after that scan is invisible until the next restart. So:
 * <ol>
 *   <li>{@link #provisionJars()} runs from {@link SourbyBootstrap#main} — the slim-jar bootstrap
 *       entry — <em>before</em> control is handed to the clip/{@code Main.main}, i.e. before the
 *       plugin scan. JDK-only (no server classes are on the classpath yet); downloads +
 *       SHA-256-verifies the pinned jars into {@code plugins/}. This is the load-bearing part:
 *       it makes the jars visible to that first scan, so Via loads + enables this same boot.</li>
 *   <li>{@link #provisionConfigs()} runs from
 *       {@link dev.iyanz.sourbycraft.perf.PerfEngineBootstrap#start()} — the post-config hook,
 *       which precedes {@code CraftServer.enablePlugins()} (where a legacy Bukkit plugin reads its
 *       config in {@code onEnable}). It writes the shipped default {@code config.yml} (with the
 *       1.20 floor) from a server-jar resource, only if absent.</li>
 * </ol>
 *
 * <p><b>Security.</b> We auto-install and then run third-party plugin code. Every jar is pinned to
 * an exact version and verified against a hard-coded SHA-256 (and exact byte length, https-only)
 * before it is moved into place; any mismatch aborts that plugin (temp is deleted, nothing enabled)
 * — the same discipline as {@link LibDownloader}. The pins below were captured from the official
 * ViaVersion Modrinth CDN and cross-checked against the project's published SHA-512.
 *
 * <p><b>Idempotent.</b> If any {@code plugins/ViaVersion-*.jar} (resp. {@code ViaBackwards-*.jar})
 * already exists — ours from a prior boot, or the operator's own build — we do nothing for that
 * plugin. Configs are written only when absent, so operator edits are never clobbered.
 *
 * <p><b>Toggle.</b> Gated by {@code viaversion.auto-provision} in the unified SourbyCraft config
 * (default {@code false} since 2026-07-12). A native 26.2/1.21.9 (protocol 776) client needs no
 * translation, and ViaVersion injects into EVERY client's pipeline — the current 1.21.9 play-phase
 * translation has a confirmed native-join stall (ViaVersion#4666), so we do not provision Via by
 * default. Operators bridging pre-1.21.9 clients opt in with {@code viaversion.auto-provision=true}.
 * Phase 1 reads it with a tiny on-disk TOML scan (the Luminol config engine is not up yet at
 * bootstrap time); phase 2 reads the fully-parsed value.
 */
public final class PluginProvisioner {

    private PluginProvisioner() {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    private static final Path UNIFIED_CONFIG =
        Paths.get("sourbycraft_config", "sourbycraft_global_config.toml");

    /**
     * A pinned, SHA-256-verified plugin jar to provision, plus its default-config resource.
     *
     * @param plugin         display name; also the {@code plugins/<name>/} config dir
     * @param jarPrefix      filename prefix for the "already present?" glob (e.g. "ViaVersion-")
     * @param fileName       exact destination filename under {@code plugins/}
     * @param downloadUrl    direct https URL (Modrinth CDN)
     * @param sha256         expected lowercase hex SHA-256 (64 chars)
     * @param sizeBytes      expected content length
     * @param configResource classpath resource holding the default config.yml (server jar), or null
     */
    record Pin(String plugin, String jarPrefix, String fileName, String downloadUrl,
               String sha256, long sizeBytes, String configResource) {}

    /**
     * ViaVersion 5.10.0 + ViaBackwards 5.10.0 — the matched stable pair (released 2026-06-19).
     * Both declare {@code folia-supported: true}; ViaVersion supports 1.8.9..1.21.11 (incl. the
     * native protocol 776 / MC 1.21.9 server side), ViaBackwards down to 1.10 client side. The
     * 1.20 client floor is enforced by the shipped ViaVersion config ({@code block-versions}).
     */
    private static final List<Pin> PINS = List.of(
        new Pin(
            "ViaVersion", "ViaVersion-", "ViaVersion-5.10.0.jar",
            "https://cdn.modrinth.com/data/P1OZGk5p/versions/ruzmiBqe/ViaVersion-5.10.0.jar",
            "e5a63f86198d74cd1be00a6ef626001e509921d2955982fe73f3ab6d7149d972",
            6434343L, "/sourbycraft/via/viaversion-config.yml"),
        new Pin(
            "ViaBackwards", "ViaBackwards-", "ViaBackwards-5.10.0.jar",
            "https://cdn.modrinth.com/data/NpvuJQoq/versions/YjpKsm6j/ViaBackwards-5.10.0.jar",
            "4699b6dddd388048cd33627276f7250c23d7f7125fe620056cdf5acf96e9c62b",
            1422522L, "/sourbycraft/via/viabackwards-config.yml")
    );

    // ---------------------------------------------------------------------------------------------
    // Phase 1: jar download — JDK-only, called from SourbyBootstrap.main BEFORE the plugin scan.
    // ---------------------------------------------------------------------------------------------

    /**
     * Download + SHA-256-verify the pinned Via jars into {@code plugins/}. JDK-only (uses
     * {@code System.out}/{@code err}, not any server class). Best-effort: a fetch failure logs and
     * continues so the server still boots (just without that legacy-client bridge). No-op when the
     * on-disk toggle is {@code false}.
     */
    public static void provisionJars() {
        if (!autoProvisionEnabledFromDisk()) {
            System.out.println("[SourbyBootstrap] ViaVersion auto-provision disabled "
                + "(viaversion.auto-provision=false) — old clients will not be bridged.");
            return;
        }
        Path pluginsDir = Paths.get("plugins");
        try {
            Files.createDirectories(pluginsDir);
        } catch (IOException e) {
            System.err.println("[SourbyBootstrap] ViaVersion auto-provision: cannot create plugins/ dir: "
                + e.getMessage());
            return;
        }
        for (Pin pin : PINS) {
            try {
                ensureJar(pin, pluginsDir);
            } catch (IOException e) {
                System.err.println("[SourbyBootstrap] ViaVersion auto-provision: failed to provision "
                    + pin.plugin() + " from " + pin.downloadUrl() + ": " + e.getMessage()
                    + " — old clients will not be bridged. Install it manually into plugins/ if needed.");
            }
        }
    }

    /** Download + verify the pinned jar into {@code plugins/} unless it (or a same-prefix jar) is present. */
    private static void ensureJar(Pin pin, Path pluginsDir) throws IOException {
        Path dest = pluginsDir.resolve(pin.fileName());

        // Idempotency 1: exact target already verified -> nothing to do (no re-download).
        if (Files.isRegularFile(dest) && Sha256Verifier.matches(dest, pin.sha256())) {
            return;
        }
        // Idempotency 2: ANY same-prefix jar present (operator build or other pinned version) -> respect it.
        if (anyPrefixJarPresent(pluginsDir, pin.jarPrefix())) {
            System.out.println("[SourbyBootstrap] ViaVersion auto-provision: found an existing "
                + pin.jarPrefix() + "*.jar in plugins/ — leaving it untouched.");
            return;
        }

        URI uri = URI.create(pin.downloadUrl());
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new IOException("Refusing non-https plugin download: " + pin.downloadUrl());
        }

        Path tmp = dest.resolveSibling(dest.getFileName() + ".tmp");
        Files.deleteIfExists(tmp);
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<Path> resp;
        try {
            resp = HTTP.send(req, HttpResponse.BodyHandlers.ofFile(tmp));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted during download of " + pin.downloadUrl(), e);
        }
        try {
            if (resp.statusCode() != 200) {
                throw new IOException("HTTP " + resp.statusCode() + " for " + pin.downloadUrl());
            }
            long got = Files.size(tmp);
            if (got != pin.sizeBytes()) {
                throw new IOException("size mismatch for " + pin.fileName()
                    + ": got " + got + ", expected " + pin.sizeBytes());
            }
            String actual = Sha256Verifier.ofFile(tmp);
            if (!pin.sha256().equalsIgnoreCase(actual)) {
                throw new IOException("SHA-256 mismatch for " + pin.fileName()
                    + ": got " + actual + ", expected " + pin.sha256());
            }
        } catch (IOException verifyErr) {
            Files.deleteIfExists(tmp);
            throw verifyErr;
        }

        // Verified — move into place; fall back to plain replace where ATOMIC_MOVE is unsupported.
        try {
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException atomicUnsupported) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException moveErr) {
            Files.deleteIfExists(tmp);
            throw moveErr;
        }
        System.out.println("[SourbyBootstrap] ViaVersion auto-provision: downloaded + SHA-256-verified "
            + pin.fileName() + " (" + (pin.sizeBytes() / 1024 / 1024) + "M) into plugins/ "
            + "(Folia-supported; loads this boot).");
    }

    /** True when {@code plugins/} contains any regular file named {@code <prefix>...jar}. */
    private static boolean anyPrefixJarPresent(Path pluginsDir, String prefix) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(pluginsDir, prefix + "*.jar")) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) return true;
            }
        }
        return false;
    }

    /**
     * Tiny, dependency-free TOML scan for {@code viaversion.auto-provision}. The Luminol config
     * engine is not initialised at bootstrap time, so we read the on-disk unified file directly.
     * Returns the default ({@code false}) when the file or key is absent/unreadable — provisioning
     * is opt-in (a native 776 client needs no Via, and Via can stall a native 1.21.9 join —
     * ViaVersion#4666), and a first boot has no file yet. Explicit
     * {@code auto-provision = true} opts in.
     */
    private static boolean autoProvisionEnabledFromDisk() {
        if (!Files.isRegularFile(UNIFIED_CONFIG)) return false; // first boot / no file -> default OFF
        try {
            for (String raw : Files.readAllLines(UNIFIED_CONFIG)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                // Match "auto-provision = true" under [viaversion], OR a dotted "viaversion.auto-provision".
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                String val = line.substring(eq + 1).trim().toLowerCase(java.util.Locale.ROOT);
                if (key.equals("auto-provision") || key.equals("viaversion.auto-provision")
                        || key.equals("\"auto-provision\"")) {
                    return val.startsWith("true");
                }
            }
        } catch (IOException ignored) {
            // Unreadable -> default OFF.
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 2: default config seeding — server-jar resources, called from PerfEngineBootstrap.start().
    // ---------------------------------------------------------------------------------------------

    /**
     * Write the shipped default {@code config.yml} (with the 1.20 floor) for each provisioned plugin
     * into {@code plugins/<name>/}, only when absent. Runs from the post-config hook, which precedes
     * {@code enablePlugins()} where Via reads its config in {@code onEnable} — so the floor is in
     * place before it is read. No-op when the toggle is off; operator edits are never overwritten.
     *
     * @param enabled the parsed {@code viaversion.auto-provision} value
     */
    public static void provisionConfigs(boolean enabled) {
        if (!enabled) return;
        Path pluginsDir = Paths.get("plugins");
        for (Pin pin : PINS) {
            try {
                ensureConfig(pin, pluginsDir);
            } catch (IOException e) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn(
                    "ViaVersion auto-provision: could not write default config for "
                        + pin.plugin() + ": " + e.getMessage());
            }
        }
    }

    private static void ensureConfig(Pin pin, Path pluginsDir) throws IOException {
        if (pin.configResource() == null) return;
        Path cfgDir = pluginsDir.resolve(pin.plugin());
        Path cfg = cfgDir.resolve("config.yml");
        if (Files.exists(cfg)) return; // never clobber operator edits
        byte[] data;
        try (InputStream in = PluginProvisioner.class.getResourceAsStream(pin.configResource())) {
            if (in == null) {
                dev.iyanz.sourbycraft.util.SourbyLogger.warn(
                    "ViaVersion auto-provision: default config resource "
                        + pin.configResource() + " missing from jar — " + pin.plugin()
                        + " will generate its own default (no 1.20 floor pre-set).");
                return;
            }
            data = in.readAllBytes();
        }
        Files.createDirectories(cfgDir);
        Files.write(cfg, data);
        dev.iyanz.sourbycraft.util.SourbyLogger.info(
            "ViaVersion auto-provision: wrote default plugins/" + pin.plugin() + "/config.yml"
                + ("ViaVersion".equals(pin.plugin()) ? " (oldest client floor pinned to 1.20)." : "."));
    }
}
