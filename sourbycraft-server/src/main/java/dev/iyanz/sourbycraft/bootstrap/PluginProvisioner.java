package dev.iyanz.sourbycraft.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.jar.JarFile;
import java.nio.charset.StandardCharsets;

/**
 * Installs pinned ViaVersion/ViaBackwards and ProtocolLib before the plugin scan.
 * The active SourbyClip launcher enters the patched Minecraft Main, which invokes phase 1
 * before PluginInitializerManager.load. SourbyCraftBootstrap seeds absent Via configs later.
 * Independent RESTART_REQUIRED toggles live in the global TOML. Operator jars/configs are
 * preserved; only exact pinned jars are quarantined/restored. Native Intave retains its own
 * packet backend and skips the external ProtocolLib plugin. No network is used in Clip offline mode.
 */
public final class PluginProvisioner {

    private PluginProvisioner() {}

    private static final Path UNIFIED_CONFIG =
        Paths.get("sourbycraft_config", "sourbycraft_global_config.toml");
    private static volatile Path configuredPluginsDirectory = Paths.get("plugins");

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
    // Phase 1: JDK-only jar download, invoked by patched Minecraft Main before the plugin scan.
    // ---------------------------------------------------------------------------------------------

    /**
     * Download + SHA-256-verify the pinned Via jars into {@code plugins/}. JDK-only (uses
     * {@code System.out}/{@code err}, not any server class). Best-effort: a fetch failure logs and
     * continues so the server still boots (just without that legacy-client bridge).
     *
     * <p>When the toggle is {@code false} this is NOT a plain no-op: any pinned jar that WE
     * provisioned on an earlier boot (exact SHA-256 match) is quarantined to
     * {@code <name>.jar.disabled}. The plugin scan loads every {@code plugins/*.jar}, so a jar left
     * behind would keep loading against the operator's intent. Operator-installed Via jars (same
     * prefix, different hash) are never touched; we warn instead.
     */
    public static void provisionJars() {
        provisionJars(Paths.get("plugins"));
    }

    /** Active SourbyClip/Patcher server path calls this before PluginInitializerManager.load. */
    public static void provisionJars(Path pluginsDir) {
        configuredPluginsDirectory = pluginsDir;
        boolean nativePacketBackend = Boolean.getBoolean("sourbycraft.intave.enabled")
            || PluginProvisioner.class.getResource("/dev/yanianz/intave/integration/NativeEngineProvider.class") != null;
        provisionJars(pluginsDir, UNIFIED_CONFIG, nativePacketBackend, LibDownloader.HTTPS);
    }

    static void provisionJars(Path pluginsDir, Path config, boolean nativeIntave, LibDownloader.Transport transport) {
        ProtocolLibProvisioner.provision(pluginsDir, config, nativeIntave, transport);
        if (!BootstrapPluginSettings.enabled(config, "viaversion", true)) {
            System.out.println("[SourbyBootstrap] ViaVersion auto-provision disabled "
                + "(viaversion.auto-provision=false) — old clients will not be bridged.");
            quarantineProvisionedJars(pluginsDir, PINS);
            return;
        }
        try {
            Files.createDirectories(pluginsDir);
        } catch (IOException e) {
            System.err.println("[SourbyBootstrap] ViaVersion auto-provision: cannot create plugins/ dir: "
                + e.getMessage());
            return;
        }
        for (Pin pin : PINS) {
            try {
                ensureJar(pin, pluginsDir, transport);
            } catch (IOException e) {
                System.err.println("[SourbyCraft] ViaVersion auto-provision: failed to provision "
                    + pin.plugin() + " from " + pin.downloadUrl() + ": " + e.getMessage()
                    + " — old clients will not be bridged. Install it manually into plugins/ if needed.");
            }
        }
    }

    /**
     * Toggle is OFF: get OUR previously-provisioned jars out of the plugin scan. Only a jar whose
     * SHA-256 matches the pin is moved (to {@code <name>.jar.disabled} — reversible, and invisible
     * to the {@code *.jar} plugin scan). A same-prefix jar with any other hash is the operator's;
     * we leave it and warn that it can still stall native 1.21.9 joins.
     */
    static void quarantineProvisionedJars(Path pluginsDir, List<Pin> pins) {
        if (!Files.isDirectory(pluginsDir)) return;
        for (Pin pin : pins) {
            Path jar = pluginsDir.resolve(pin.fileName());
            try {
                if (Files.isRegularFile(jar) && Sha256Verifier.matches(jar, pin.sha256())) {
                    Path disabled = jar.resolveSibling(jar.getFileName() + ".disabled");
                    if (Files.exists(disabled) && !Sha256Verifier.matches(disabled, pin.sha256()))
                        throw new IOException("Existing quarantine differs; preserved " + disabled);
                    Files.move(jar, disabled, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("[SourbyCraft] " + pin.plugin() + " auto-provision: quarantined "
                        + pin.fileName() + " -> " + disabled.getFileName()
                        + " (we provisioned it on an earlier boot; auto-provision is now false). "
                        + "Enable auto-provision for " + pin.plugin() + " to restore it.");
                } else if (anyPluginJarPresent(pluginsDir, pin)) {
                    System.err.println("[SourbyCraft] " + pin.plugin()
                        + " auto-provision is off, but an operator-installed jar remains and can still load.");
                }
            } catch (IOException e) {
                System.err.println("[SourbyCraft] " + pin.plugin() + " auto-provision: could not quarantine "
                    + pin.fileName() + ": " + e.getMessage() + " — remove it from plugins/ manually.");
            }
        }
    }

    /** Download + verify the pinned jar into {@code plugins/} unless it (or a same-prefix jar) is present. */
    static void ensureJar(Pin pin, Path pluginsDir, LibDownloader.Transport transport) throws IOException {
        Files.createDirectories(pluginsDir);
        Path dest = pluginsDir.resolve(pin.fileName());

        // Idempotency 1: exact target already verified -> nothing to do (no re-download).
        if (Files.isRegularFile(dest) && Sha256Verifier.matches(dest, pin.sha256())) {
            return;
        }
        // Self-heal: a file with OUR exact pinned name but a wrong hash is a corrupt download
        // (power-loss mid-move, disk corruption) — without this, the prefix check below would
        // classify it as an operator jar and keep loading the corrupt file forever.
        if (Files.isRegularFile(dest)) {
            Path corrupt = Files.createTempFile(pluginsDir, dest.getFileName() + ".", ".corrupt");
            Files.move(dest, corrupt, StandardCopyOption.REPLACE_EXISTING);
            System.err.println("[SourbyCraft] " + pin.plugin() + " auto-provision: " + pin.fileName()
                + " failed SHA-256 verification — moved to " + corrupt.getFileName() + " and re-downloading.");
        }
        // Idempotency 2: ANY same-prefix jar present (operator build or other pinned version) -> respect it.
        if (anyPluginJarPresent(pluginsDir, pin)) {
            System.out.println("[SourbyCraft] " + pin.plugin() + " auto-provision: existing operator jar preserved.");
            return;
        }
        // Idempotency 3: we quarantined this exact jar while the toggle was off -> restore, no re-download.
        Path quarantined = dest.resolveSibling(dest.getFileName() + ".disabled");
        if (Files.isRegularFile(quarantined) && Sha256Verifier.matches(quarantined, pin.sha256())) {
            Files.move(quarantined, dest, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[SourbyCraft] " + pin.plugin() + " auto-provision: restored quarantined "
                + pin.fileName() + " (SHA-256 re-verified; loads this boot).");
            return;
        }

        if (Boolean.getBoolean("sourbyclip.offline"))
            throw new IOException("Offline mode requires a verified or operator-installed " + pin.plugin() + " jar");
        URI uri = URI.create(pin.downloadUrl());
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new IOException("Refusing non-https plugin download: " + pin.downloadUrl());
        }

        Path tmp = Files.createTempFile(pluginsDir, pin.fileName() + ".", ".tmp");
        try {
            // Same bounded transport as the libraries: a whole-transfer deadline, and the body cut
            // off once it passes the pinned size. A stall here would otherwise hang boot.
            transport.fetch(uri, tmp, pin.sizeBytes());
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

        // Do not replace an operator jar that appeared while the download was in progress.
        try {
            if (anyPluginJarPresent(pluginsDir, pin)) {
                System.out.println("[SourbyCraft] " + pin.plugin() + ": a plugin appeared during download; existing jar preserved.");
                return;
            }
            Files.move(tmp, dest);
        } finally {
            Files.deleteIfExists(tmp);
        }
        System.out.println("[SourbyCraft] " + pin.plugin() + " auto-provision: downloaded + SHA-256-verified "
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

    private static boolean anyPluginJarPresent(Path pluginsDir, Pin pin) throws IOException {
        if (!Files.isDirectory(pluginsDir)) return false;
        if (anyPrefixJarPresent(pluginsDir, pin.jarPrefix())) return true;
        try (DirectoryStream<Path> jars = Files.newDirectoryStream(pluginsDir)) {
            for (Path jar : jars) {
                if (!Files.isRegularFile(jar)) continue;
                String filename = jar.getFileName().toString();
                if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) continue;
                if (filename.equalsIgnoreCase(pin.plugin() + ".jar")) return true;
                try (JarFile archive = new JarFile(jar.toFile())) {
                    for (String descriptor : List.of("plugin.yml", "paper-plugin.yml")) {
                        var entry = archive.getJarEntry(descriptor);
                        if (entry == null) continue;
                        try (InputStream in = archive.getInputStream(entry)) {
                            byte[] data = in.readNBytes(65537);
                            if (data.length > 65536) continue;
                            for (String line : new String(data, StandardCharsets.UTF_8).split("\\R")) {
                                if (line.matches("(?i)^name\\s*:\\s*['\"]?" + java.util.regex.Pattern.quote(pin.plugin())
                                    + "['\"]?\\s*(#.*)?$")) return true;
                            }
                        }
                    }
                } catch (java.util.zip.ZipException ignored) { /* Unrelated broken jar: Paper reports it. */ }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Phase 2: default config seeding — server-jar resources, called from SourbyCraftBootstrap.init().
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
        Path pluginsDir = configuredPluginsDirectory;
        for (Pin pin : PINS) {
            try {
                ensureConfig(pin, pluginsDir);
            } catch (IOException e) {
                System.err.println(
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
                System.err.println(
                    "ViaVersion auto-provision: default config resource "
                        + pin.configResource() + " missing from jar — " + pin.plugin()
                        + " will generate its own default (no 1.20 floor pre-set).");
                return;
            }
            data = in.readAllBytes();
        }
        Files.createDirectories(cfgDir);
        Files.write(cfg, data);
        System.out.println(
            "[SourbyCraft] ViaVersion auto-provision: wrote default " + cfg
                + ("ViaVersion".equals(pin.plugin()) ? " (oldest client floor pinned to 1.20)." : "."));
    }
}
