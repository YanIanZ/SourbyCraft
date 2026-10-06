package dev.iyanz.sourbycraft.bootstrap;

import java.io.IOException;
import java.nio.file.Path;

/** Installs the official ProtocolLib plugin before Paper's first plugin scan. */
final class ProtocolLibProvisioner {
    // Official GitHub release asset metadata, 2026-10-03. dev-build is mutable: a changed
    // asset is rejected by this exact hash/size until the pin is deliberately updated.
    static final PluginProvisioner.Pin PIN = new PluginProvisioner.Pin(
        "ProtocolLib", "ProtocolLib-", "ProtocolLib-5.5.0-dev-608298510.jar",
        "https://github.com/dmulloy2/ProtocolLib/releases/download/dev-build/ProtocolLib.jar",
        "3242a696fbb55a0622d6148d88206ccc392e1d94c5b78ddd423f43ebb82a768a", 10567071L, null);

    private ProtocolLibProvisioner() {}

    static void provision(Path plugins, Path config, boolean nativeIntave, LibDownloader.Transport transport) {
        boolean enabled = BootstrapPluginSettings.enabled(config, "protocollib", true);
        if (nativeIntave) {
            System.out.println("[SourbyCraft] ProtocolLib plugin auto-install skipped: the private native build reserves its packet API.");
            enabled = false;
        }
        try {
            if (enabled) PluginProvisioner.ensureJar(PIN, plugins, transport);
            else PluginProvisioner.quarantineProvisionedJars(plugins, java.util.List.of(PIN));
        } catch (IOException failure) {
            System.err.println("[SourbyCraft] ProtocolLib installation failed: " + failure.getMessage()
                + ". Source: " + PIN.downloadUrl() + "; target: " + plugins.resolve(PIN.fileName())
                + ". Expected SHA-256: " + PIN.sha256() + ". Packet-dependent plugins may not load.");
        }
    }
}
