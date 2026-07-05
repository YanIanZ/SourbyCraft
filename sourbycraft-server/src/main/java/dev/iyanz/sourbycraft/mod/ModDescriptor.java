package dev.iyanz.sourbycraft.mod;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parsed and validated descriptor from {@code sourbymod.yml} inside a mod jar.
 *
 * <p>Parse is hardened via SnakeYAML {@link SafeConstructor} (no {@code !!}-tag gadget RCE),
 * following the same idiom used in {@code SourbyCraftSecurityConfig.load}.
 */
public final class ModDescriptor {

    /** Allowed mod id pattern: lowercase letters, digits, hyphens, underscores, 1–32 chars. */
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9\\-_]{1,32}");

    public final String id;
    public final String name;
    public final String version;
    /** Fully-qualified class name that implements {@link SourbyMod}. */
    public final String main;
    /** SourbyMod API generation the mod targets. Loader rejects values newer than it knows. */
    public final int api;

    private ModDescriptor(String id, String name, String version, String main, int api) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.main = main;
        this.api = api;
    }

    /**
     * Parse and validate a {@code sourbymod.yml} stream.
     *
     * @param in      open stream for the descriptor (caller closes it)
     * @param jarName jar filename used in log messages
     * @return parsed descriptor, or {@code null} on any failure (reason already logged)
     */
    @SuppressWarnings("unchecked")
    public static ModDescriptor parse(InputStream in, String jarName) {
        try {
            // SourbyCraft — SafeConstructor blocks gadget RCE via !!tags (same idiom as SourbyCraftSecurityConfig)
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Map<String, Object> map = yaml.load(in);
            if (map == null) {
                SourbyLogger.warn("[SourbyCraft] mods/" + jarName + ": sourbymod.yml is empty");
                return null;
            }

            String id = str(map, "id");
            if (id == null || !ID_PATTERN.matcher(id).matches()) {
                SourbyLogger.warn("[SourbyCraft] mods/" + jarName
                    + ": missing or invalid 'id' (must match [a-z0-9-_]{1,32})");
                return null;
            }

            String name = str(map, "name");
            if (name == null || name.isBlank()) name = id;

            String version = str(map, "version");
            if (version == null || version.isBlank()) {
                SourbyLogger.warn("[SourbyCraft] mods/" + jarName + ": missing 'version'");
                return null;
            }

            String main = str(map, "main");
            if (main == null || main.isBlank()) {
                SourbyLogger.warn("[SourbyCraft] mods/" + jarName + ": missing 'main' class");
                return null;
            }

            Object apiRaw = map.get("api");
            if (!(apiRaw instanceof Number)) {
                SourbyLogger.warn("[SourbyCraft] mods/" + jarName + ": missing or non-numeric 'api' field");
                return null;
            }
            int api = ((Number) apiRaw).intValue();

            return new ModDescriptor(id, name, version, main, api);
        } catch (Exception e) {
            SourbyLogger.warn("[SourbyCraft] mods/" + jarName
                + ": failed to parse sourbymod.yml: " + e.getMessage());
            return null;
        }
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }
}
