package dev.iyanz.sourbycraft.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads only bootstrap boolean switches, before the full TOML configuration is loaded. */
final class BootstrapPluginSettings {
    private BootstrapPluginSettings() {}

    static boolean enabled(Path config, String group, boolean fallback) {
        if (!Files.exists(config)) return fallback;
        String table = "";
        try {
            for (String raw : Files.readAllLines(config)) {
                String line = raw.split("#", 2)[0].trim();
                if (line.startsWith("[") && line.endsWith("]")) {
                    table = unquote(line.substring(1, line.length() - 1).trim());
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator < 0) continue;
                String key = unquote(line.substring(0, separator).trim());
                if (!(table.equals(group) && key.equals("auto-provision"))
                    && !(table.isEmpty() && key.equals(group + ".auto-provision"))) continue;
                String value = line.substring(separator + 1).trim();
                if (value.equals("true")) return true;
                if (value.equals("false")) return false;
                System.err.println("[SourbyCraft] Invalid " + group + ".auto-provision; installation disabled this boot.");
                return false;
            }
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
