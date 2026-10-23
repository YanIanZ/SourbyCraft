package dev.iyanz.sourbycraft.mod;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.InputStream;
import java.util.Map;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Parsed and validated descriptor from {@code sourbymod.yml} inside a mod jar.
 *
 * <p>Parsing uses SnakeYAML's {@link SafeConstructor}, so {@code !!} gadget tags cannot instantiate
 * arbitrary classes from a descriptor; the same idiom is used by the server's YAML configuration.</p>
 */
public final class ModDescriptor {

    /** Allowed mod id pattern: lowercase letters, digits, hyphens, underscores, 1-32 chars. */
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9\\-_]{1,32}");

    public final String id;
    public final String name;
    public final String version;
    /** Fully-qualified class name that implements {@link SourbyMod}. */
    public final String main;
    /** SourbyMod API generation the mod targets. The loader rejects values newer than it knows. */
    public final int api;

    private ModDescriptor(final String id, final String name, final String version, final String main, final int api) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.main = main;
        this.api = api;
    }

    /**
     * Parse and validate a {@code sourbymod.yml} stream.
     *
     * @param in open stream for the descriptor (the caller closes it)
     * @param jarName jar filename used in log messages
     * @return the parsed descriptor, or {@code null} on any failure (the reason is logged)
     */
    @SuppressWarnings("unchecked")
    public static ModDescriptor parse(final InputStream in, final String jarName) {
        try {
            final Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            final Map<String, Object> map = yaml.load(in);
            if (map == null) {
                SourbyLogger.warn("mods/" + jarName + ": sourbymod.yml is empty");
                return null;
            }

            final String id = str(map, "id");
            if (id == null || !ID_PATTERN.matcher(id).matches()) {
                SourbyLogger.warn("mods/" + jarName
                    + ": missing or invalid 'id' (must match [a-z0-9-_]{1,32})");
                return null;
            }

            String name = str(map, "name");
            if (name == null || name.isBlank()) name = id;

            final String version = str(map, "version");
            if (version == null || version.isBlank()) {
                SourbyLogger.warn("mods/" + jarName + ": missing 'version'");
                return null;
            }

            final String main = str(map, "main");
            if (main == null || main.isBlank()) {
                SourbyLogger.warn("mods/" + jarName + ": missing 'main' class");
                return null;
            }

            final Object apiRaw = map.get("api");
            if (!(apiRaw instanceof Number number)) {
                SourbyLogger.warn("mods/" + jarName + ": missing or non-numeric 'api' field");
                return null;
            }
            final int api = number.intValue();
            if (api < 1) {
                SourbyLogger.warn("mods/" + jarName + ": invalid 'api' " + api + " (must be >= 1)");
                return null;
            }

            return new ModDescriptor(id, name, version, main, api);
        } catch (final Exception failed) {
            SourbyLogger.warn("mods/" + jarName + ": failed to parse sourbymod.yml: " + failed.getMessage());
            return null;
        }
    }

    private static String str(final Map<String, Object> map, final String key) {
        final Object value = map.get(key);
        return value instanceof String text ? text : null;
    }
}
