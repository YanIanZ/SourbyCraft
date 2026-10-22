package dev.iyanz.sourbycraft.bootstrap;

import java.io.IOException;
import java.nio.file.Path;

/** Installs the official ProtocolLib plugin before Paper's first plugin scan. */
final class ProtocolLibProvisioner {
    // Tagged release 5.4.0 (asset 280088448, SHA-256 ee2e7ab9..., 10,251,186 bytes,
    // published 2025-08-07). Load-tested 2026-10-20 on 26.2: enables and disables
    // cleanly (Done 15.7s/16.0s, exit 0); upstream logs a soft "MC 26.2.0 has not yet
    // been tested" caveat (MCVERSIONS map) — not a fatal incompatibility.
    // See build-data/protocollib-pin.json and docs/development/PROTOCOLLIB.md.
    static final PluginProvisioner.Pin PIN = new PluginProvisioner.Pin(
        "ProtocolLib", "ProtocolLib-", "ProtocolLib-5.4.0.jar",
        "https://github.com/dmulloy2/ProtocolLib/releases/download/5.4.0/ProtocolLib.jar",
        "ee2e7ab9b5386f2d103081c4d108e61b1035df2ca692b53d6e2409fb1f5caccf", 10251186L, null);

    private ProtocolLibProvisioner() {}

    static void provision(Path plugins, Path config, LibDownloader.Transport transport) {
        boolean enabled = BootstrapPluginSettings.enabled(config, "protocollib", true);
        try {
            if (enabled) PluginProvisioner.ensureJar(PIN, plugins, transport);
            else PluginProvisioner.quarantineProvisionedJars(plugins, java.util.List.of(PIN));
        } catch (IOException failure) {
            BootLog.warn("ProtocolLib installation failed: " + failure.getMessage()
                + ". Source: " + PIN.downloadUrl() + "; target: " + plugins.resolve(PIN.fileName())
                + ". Expected SHA-256: " + PIN.sha256() + ". Packet-dependent plugins may not load.");
        }
    }
}
