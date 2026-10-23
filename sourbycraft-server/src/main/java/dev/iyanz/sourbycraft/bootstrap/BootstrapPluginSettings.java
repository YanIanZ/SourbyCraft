package dev.iyanz.sourbycraft.bootstrap;

import com.electronwill.nightconfig.core.Config;
import dev.iyanz.sourbycraft.config.ConfigSources;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads only the bootstrap boolean switches, before the full configuration is loaded (and so before
 * the Build 49 layout migration has run on a first boot).
 *
 * <p>Accepted spellings for {@code <group>} = {@code viaversion} / {@code protocollib}, in order of
 * precedence when more than one is present:</p>
 * <ol>
 *   <li>Build 49: {@code plugins.<group>.auto-provision} (table, inline table or dotted root key);</li>
 *   <li>pre-Build-49 (read until Build 50): {@code <group>.auto-provision} (table or root key).</li>
 * </ol>
 *
 * <p>The file is read with the format its extension names ({@code .yml} now, the old {@code .toml}
 * until it has been converted). A value that is neither a boolean nor {@code "true"}/{@code "false"}
 * disables installation for this boot, as the previous line parser did.</p>
 */
final class BootstrapPluginSettings {
    private BootstrapPluginSettings() {}

    static boolean enabled(final Path config, final String group, final boolean fallback) {
        if (Files.exists(config) && !Files.isRegularFile(config)) {
            System.err.println("[SourbyCraft] Cannot read plugin provisioning settings: " + config
                + ": not a regular file; installation disabled this boot.");
            return false;
        }
        final Path file = ConfigSources.existing(config);
        if (file == null) return fallback;
        try {
            final Config parsed = ConfigSources.read(file);
            final Object current = parsed.get("plugins." + group + ".auto-provision");
            final Object legacy = current != null ? null : parsed.get(group + ".auto-provision");
            final Object value = current != null ? current : legacy;
            if (value == null) return fallback;
            if (value instanceof Boolean enabled) return enabled;
            System.err.println("[SourbyCraft] Invalid " + (current != null ? "plugins." + group : group)
                + ".auto-provision; installation disabled this boot.");
            return false;
        } catch (final Exception failure) {
            System.err.println("[SourbyCraft] Cannot read plugin provisioning settings: " + file
                + ": " + failure.getMessage() + "; installation disabled this boot.");
            return false;
        }
    }
}
