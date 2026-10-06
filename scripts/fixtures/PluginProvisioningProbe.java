package dev.iyanz.sourbycraft.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/** Exercises the actual JDK-only installers; no Minecraft/plugin loader stand-ins. */
public final class PluginProvisioningProbe {
    private static final Path ROOT = Path.of(".").toAbsolutePath();
    private static final Path PLUGINS = ROOT.resolve("custom-plugins");
    private static final Path CONFIG = ROOT.resolve("global.toml");
    private static final byte[] BYTES = "controlled verified artifact bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static PluginProvisioner.Pin pin(String url) throws Exception {
        return new PluginProvisioner.Pin("ProtocolLib", "ProtocolLib-", "ProtocolLib-test.jar", url,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BYTES)), BYTES.length, null);
    }

    private static void jar(String filename, String descriptor) throws IOException {
        jar(filename, descriptor, "ProtocolLib");
    }

    private static void jar(String filename, String descriptor, String plugin) throws IOException {
        Files.createDirectories(PLUGINS);
        try (var archive = new JarOutputStream(Files.newOutputStream(PLUGINS.resolve(filename)))) {
            archive.putNextEntry(new JarEntry(descriptor));
            archive.write(("name: '" + plugin + "'\nversion: operator\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            archive.closeEntry();
        }
    }

    private static void noTemps() throws IOException {
        try (var entries = Files.list(PLUGINS)) {
            check(entries.noneMatch(p -> p.toString().endsWith(".tmp")), "failed install leaked a temporary file");
        }
    }

    public static void main(String[] args) throws Exception {
        var calls = new AtomicInteger();
        LibDownloader.Transport good = (uri, into, maximum) -> { calls.incrementAndGet(); Files.write(into, BYTES); };
        LibDownloader.Transport forbidden = (uri, into, maximum) -> { throw new AssertionError("network was touched"); };
        var pin = pin("https://example.invalid/artifact.jar");
        Path target = PLUGINS.resolve(pin.fileName());
        switch (args[0]) {
            case "settings" -> {
                Files.writeString(CONFIG, "[other]\nauto-provision=false\n[protocollib]\nauto-provision=false\n[viaversion]\nauto-provision=true\n");
                check(!BootstrapPluginSettings.enabled(CONFIG, "protocollib", true), "ProtocolLib toggle ignored");
                check(BootstrapPluginSettings.enabled(CONFIG, "viaversion", true), "ProtocolLib disabled Via");
                Files.writeString(CONFIG, "viaversion.auto-provision = false\n\"protocollib.auto-provision\" = true\n");
                check(!BootstrapPluginSettings.enabled(CONFIG, "viaversion", true), "dotted Via toggle ignored");
                check(BootstrapPluginSettings.enabled(CONFIG, "protocollib", false), "quoted dotted ProtocolLib toggle ignored");
                Files.writeString(CONFIG, "['protocollib']\n'auto-provision'=false # operator decision\n");
                check(!BootstrapPluginSettings.enabled(CONFIG, "protocollib", true), "quoted table/key ignored");
                Files.writeString(CONFIG, "[protocollib]\nauto-provision=invalid\n");
                check(!BootstrapPluginSettings.enabled(CONFIG, "protocollib", true), "malformed setting enabled download");
            }
            case "install" -> {
                PluginProvisioner.ensureJar(pin, PLUGINS, good);
                check(Files.mismatch(target, Files.write(ROOT.resolve("expected"), BYTES)) == -1, "wrong published bytes");
                PluginProvisioner.ensureJar(pin, PLUGINS, forbidden);
                check(calls.get() == 1, "verified cache was redownloaded");
                noTemps();
            }
            case "bad-download" -> {
                byte[] wrongHash = BYTES.clone(); wrongHash[0] ^= 1;
                for (byte[] bad : List.of(wrongHash, new byte[BYTES.length - 1])) {
                    try {
                        PluginProvisioner.ensureJar(pin, PLUGINS, (uri, into, max) -> Files.write(into, bad));
                        throw new AssertionError("invalid artifact published");
                    } catch (IOException expected) { }
                    check(!Files.exists(target), "invalid bytes became a plugin");
                    noTemps();
                }
                try {
                    PluginProvisioner.ensureJar(pin("http://example.invalid/artifact.jar"), PLUGINS, forbidden);
                    throw new AssertionError("non-HTTPS URL accepted");
                } catch (IOException expected) { }
            }
            case "preserve" -> {
                jar("renamed-packet-plugin.jar", "paper-plugin.yml");
                Path operator = PLUGINS.resolve("renamed-packet-plugin.jar");
                byte[] before = Files.readAllBytes(operator);
                PluginProvisioner.ensureJar(pin, PLUGINS, forbidden);
                check(java.util.Arrays.equals(before, Files.readAllBytes(operator)), "operator artifact changed");
                check(!Files.exists(target), "duplicate ProtocolLib installed");
                Path config = PLUGINS.resolve("ProtocolLib/config.yml");
                Files.createDirectories(config.getParent()); Files.writeString(config, "operator: preserved\n");
                Files.writeString(CONFIG, "[protocollib]\nauto-provision=false\n[viaversion]\nauto-provision=false\n");
                PluginProvisioner.provisionJars(PLUGINS, CONFIG, false, forbidden);
                check(Files.readString(config).equals("operator: preserved\n"), "operator config overwritten");
            }
            case "late-operator" -> {
                byte[] operator = "operator jar arriving during download".getBytes();
                PluginProvisioner.ensureJar(pin, PLUGINS, (uri, into, max) -> {
                    Files.write(into, BYTES);
                    Files.write(target, operator);
                });
                check(java.util.Arrays.equals(operator, Files.readAllBytes(target)), "late operator artifact overwritten");
                noTemps();
            }
            case "preserve-via" -> {
                jar("ViaVersion.jar", "plugin.yml", "ViaVersion");
                jar("renamed-backwards.JAR", "plugin.yml", "ViaBackwards");
                for (String plugin : List.of("ViaVersion", "ViaBackwards")) {
                    var via = new PluginProvisioner.Pin(plugin, plugin + "-", plugin + "-test.jar",
                        pin.downloadUrl(), pin.sha256(), pin.sizeBytes(), null);
                    PluginProvisioner.ensureJar(via, PLUGINS, forbidden);
                    check(!Files.exists(PLUGINS.resolve(via.fileName())), "duplicate Via plugin installed");
                }
            }
            case "quarantine" -> {
                PluginProvisioner.ensureJar(pin, PLUGINS, good);
                PluginProvisioner.quarantineProvisionedJars(PLUGINS, List.of(pin));
                Path disabled = PLUGINS.resolve(pin.fileName() + ".disabled");
                check(!Files.exists(target) && Files.exists(disabled), "managed toggle-off left active jar");
                PluginProvisioner.ensureJar(pin, PLUGINS, forbidden);
                check(Files.exists(target) && !Files.exists(disabled), "managed jar not restored offline");
                Files.writeString(disabled, "operator data");
                PluginProvisioner.quarantineProvisionedJars(PLUGINS, List.of(pin));
                check(Files.exists(target) && Files.readString(disabled).equals("operator data"), "existing quarantine overwritten");
            }
            case "offline" -> {
                System.setProperty("sourbyclip.offline", "true");
                try { PluginProvisioner.ensureJar(pin, PLUGINS, forbidden); throw new AssertionError("offline missing jar accepted"); }
                catch (IOException expected) { }
                Files.write(target, BYTES);
                PluginProvisioner.ensureJar(pin, PLUGINS, forbidden);
                check(Files.exists(target), "verified offline jar lost");
            }
            case "native" -> {
                Files.writeString(CONFIG, "[viaversion]\nauto-provision=false\n[protocollib]\nauto-provision=true\n");
                PluginProvisioner.provisionJars(PLUGINS, CONFIG, true, forbidden);
                check(!Files.exists(PLUGINS.resolve(ProtocolLibProvisioner.PIN.fileName())), "native mode installed conflicting injector");
            }
            case "custom-directory" -> {
                System.setProperty("sourbyclip.offline", "true");
                PluginProvisioner.provisionJars(PLUGINS);
                PluginProvisioner.provisionConfigs(true);
                check(Files.exists(PLUGINS.resolve("ViaVersion/config.yml")), "Via config ignored custom plugin directory");
                check(!Files.exists(ROOT.resolve("plugins")), "default plugin directory used instead of CLI directory");
                Files.writeString(PLUGINS.resolve("ViaVersion/config.yml"), "operator: preserved\n");
                PluginProvisioner.provisionConfigs(true);
                check(Files.readString(PLUGINS.resolve("ViaVersion/config.yml")).equals("operator: preserved\n"), "existing config was reseeded");
            }
            case "pin" -> {
                var p = ProtocolLibProvisioner.PIN;
                System.out.println(p.fileName() + "\n" + p.downloadUrl() + "\n" + p.sha256() + "\n" + p.sizeBytes());
            }
            default -> throw new AssertionError("unknown scenario");
        }
    }
}
