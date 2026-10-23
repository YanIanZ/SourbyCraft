package dev.iyanz.sourbycraft.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads only the bootstrap boolean switches, before the full TOML configuration is loaded (and so
 * before the Build 49 layout migration has run on a first boot).
 *
 * <p>Accepted spellings for {@code <group>} = {@code viaversion} / {@code protocollib}, in order of
 * precedence when more than one is present:</p>
 * <ol>
 *   <li>Build 49: {@code [plugins.<group>] auto-provision}, {@code [plugins] <group>.auto-provision},
 *       or the root key {@code plugins.<group>.auto-provision};</li>
 *   <li>pre-Build-49 (read until Build 50): {@code [<group>] auto-provision} or the root key
 *       {@code <group>.auto-provision}.</li>
 * </ol>
 * <p>Within one spelling the first line wins (unchanged from Build 48).</p>
 */
final class BootstrapPluginSettings {
    private BootstrapPluginSettings() {}

    static boolean enabled(Path config, String group, boolean fallback) {
        if (!Files.exists(config)) return fallback;
        String table = "";
        Boolean current = null;
        Boolean legacy = null;
        try {
            boolean first = true;
            for (String raw : Files.readAllLines(config)) {
                // readAllLines keeps a UTF-8 byte order mark ("UTF-8 with BOM" editors) on line one.
                if (first && raw.startsWith("﻿")) raw = raw.substring(1);
                first = false;
                String line = raw.split("#", 2)[0].trim();
                if (line.startsWith("[") && line.endsWith("]")) {
                    table = unquote(line.substring(1, line.length() - 1).trim());
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator < 0) continue;
                String key = unquote(line.substring(0, separator).trim());
                final boolean currentSpelling =
                    (table.equals("plugins." + group) && key.equals("auto-provision"))
                        || (table.equals("plugins") && key.equals(group + ".auto-provision"))
                        || (table.isEmpty() && key.equals("plugins." + group + ".auto-provision"));
                final boolean legacySpelling =
                    (table.equals(group) && key.equals("auto-provision"))
                        || (table.isEmpty() && key.equals(group + ".auto-provision"));
                if (!currentSpelling && !legacySpelling) continue;
                String value = line.substring(separator + 1).trim();
                final Boolean parsed;
                if (value.equals("true")) parsed = Boolean.TRUE;
                else if (value.equals("false")) parsed = Boolean.FALSE;
                else {
                    System.err.println("[SourbyCraft] Invalid " + (currentSpelling ? "plugins." : "") + group
                        + ".auto-provision; installation disabled this boot.");
                    return false;
                }
                if (currentSpelling) { if (current == null) current = parsed; }
                else if (legacy == null) legacy = parsed;
            }
            if (current != null) return current;
            if (legacy != null) return legacy;
            return fallback;
        } catch (IOException failure) {
            System.err.println("[SourbyCraft] Cannot read plugin provisioning settings: " + config
                + ": " + failure.getMessage() + "; installation disabled this boot.");
            return false;
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) return value.substring(1, value.length() - 1);
        return value;
    }
}
