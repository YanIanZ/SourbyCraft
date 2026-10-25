package dev.iyanz.sourbycraft.bootstrap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads only the bootstrap boolean switches, before the full configuration is loaded (and so before
 * the Build 49 layout migration has run on a first boot, and before {@code ConfigFormatMigration}
 * can convert the old file).
 *
 * <p>It is deliberately self-contained: the provisioning probe compiles this class with {@code javac}
 * and nothing else on the classpath, so it cannot use the configuration layer's parsers. It reads
 * the YAML file's nesting or the legacy TOML tables (the {@code .toml} beside a missing {@code .yml})
 * for exactly one key family:</p>
 * <ol>
 *   <li>Build 49: {@code plugins.<group>.auto-provision} (nested table or dotted key) decides;</li>
 *   <li>pre-Build-49: {@code <group>.auto-provision} (table or dotted key) is only detected. When it
 *       is set and the Build 49 key is not (a file the configuration migration has not rewritten
 *       yet), provisioning is skipped for this boot with one message: honouring it would rely on a
 *       read that Build 50 drops, and falling back to the default {@code true} could download plugins
 *       an operator switched off.</li>
 * </ol>
 *
 * <p>The first spelling wins over the second whatever their order; within one spelling the first
 * line wins. Only {@code true}/{@code false} are booleans in TOML; YAML also takes {@code True},
 * {@code TRUE}, {@code False} and {@code FALSE}, as the configuration layer's YAML parser does.
 * Provisioning must fail closed, so a value that is not a boolean, a flow-style mapping or sequence
 * on a line of this key family, or a file that cannot be read yields {@link Setting#SKIP}: nothing is
 * downloaded, installed or quarantined this boot.</p>
 */
final class BootstrapPluginSettings {
    private BootstrapPluginSettings() {}

    private static final String LEAF = "auto-provision";

    /** What phase 1 may do for one plugin group this boot. */
    enum Setting {
        /** Install (or restore) the pinned jars. */
        ENABLED,
        /** Quarantine the pinned jars provisioned on an earlier boot. */
        DISABLED,
        /** The operator's intent cannot be read this boot: leave {@code plugins/} exactly as it is. */
        SKIP
    }

    /** True only when installation is allowed this boot ({@link Setting#ENABLED}). */
    static boolean enabled(final Path config, final String group, final boolean fallback) {
        return setting(config, group, fallback) == Setting.ENABLED;
    }

    static Setting setting(final Path config, final String group, final boolean fallback) {
        if (Files.exists(config) && !Files.isRegularFile(config)) {
            System.err.println("[SourbyCraft] Cannot read plugin provisioning settings: " + config
                + ": not a regular file; provisioning skipped this boot.");
            return Setting.SKIP;
        }
        final Path file = existing(config);
        if (file == null) return fallback ? Setting.ENABLED : Setting.DISABLED;
        final String name = file.getFileName().toString();
        final boolean yaml = name.endsWith(".yml") || name.endsWith(".yaml");
        final Map<String, Boolean> values;
        try {
            values = yaml ? readYaml(file) : readToml(file);
        } catch (final InvalidValue invalid) {
            System.err.println("[SourbyCraft] Invalid " + invalid.path + "; provisioning skipped this boot.");
            return Setting.SKIP;
        } catch (final IOException failure) {
            System.err.println("[SourbyCraft] Cannot read plugin provisioning settings: " + file
                + ": " + failure.getMessage() + "; provisioning skipped this boot.");
            return Setting.SKIP;
        }
        final Boolean current = values.get("plugins." + group + "." + LEAF);
        if (current != null) return current ? Setting.ENABLED : Setting.DISABLED;
        if (values.containsKey(group + "." + LEAF)) {
            System.err.println("[SourbyCraft] " + file + " sets only the pre-Build-49 key " + group + "." + LEAF
                + "; " + group + " provisioning skipped this boot (nothing downloaded, installed or quarantined) until "
                + "plugins." + group + "." + LEAF + " is set. The configuration migration writes it from the old key.");
            return Setting.SKIP;
        }
        return fallback ? Setting.ENABLED : Setting.DISABLED;
    }

    /** The YAML file, or the legacy TOML beside it, or {@code null} when neither exists. */
    private static Path existing(final Path path) {
        if (Files.isRegularFile(path)) return path;
        final String name = path.getFileName().toString();
        if (name.endsWith(".yml")) {
            final Path legacy = path.resolveSibling(name.substring(0, name.length() - 4) + ".toml");
            if (Files.isRegularFile(legacy)) return legacy;
        }
        return null;
    }

    // ------------------------------------------------------------------------------- TOML (legacy)

    /** {@code [table]} headers and {@code key = value}; the previous line reader, unchanged in behaviour. */
    private static Map<String, Boolean> readToml(final Path file) throws IOException {
        final Map<String, Boolean> values = new LinkedHashMap<>();
        String table = "";
        boolean first = true;
        for (final String raw : Files.readAllLines(file)) {
            String line = raw;
            if (first) {
                if (line.startsWith("\uFEFF")) line = line.substring(1);
                first = false;
            }
            line = line.split("#", 2)[0].trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("[") && line.endsWith("]")) {
                table = unquote(line.substring(1, line.length() - 1).trim());
                continue;
            }
            final int separator = line.indexOf('=');
            if (separator < 0) continue;
            final String key = unquote(line.substring(0, separator).trim());
            final String value = line.substring(separator + 1).trim();
            final boolean isLeaf = key.equals(LEAF) || key.endsWith("." + LEAF);
            if (!isLeaf) continue;
            final String path = table.isEmpty() ? key : table + "." + key;
            final Boolean parsed = tomlBool(value);
            if (parsed == null) throw new InvalidValue(path);
            values.putIfAbsent(path, parsed);
        }
        return values;
    }

    // ------------------------------------------------------------------------------------ YAML

    /**
     * Block-style YAML only (the shape the seeder writes): {@code key:} opens a section, {@code key:
     * true} is a leaf, nesting is indentation. Values must be unquoted booleans; a quoted or other
     * value is invalid, as in the TOML reader. A flow-style value ({@code {...}} or {@code [...]}) on a
     * {@code plugins}, group or {@code auto-provision} line could hide the switch, so it is invalid too.
     */
    private static Map<String, Boolean> readYaml(final Path file) throws IOException {
        final Map<String, Boolean> values = new LinkedHashMap<>();
        final List<String> stack = new ArrayList<>();
        final List<Integer> indents = new ArrayList<>();
        boolean first = true;
        for (final String raw : Files.readAllLines(file)) {
            String line = raw;
            if (first) {
                if (line.startsWith("\uFEFF")) line = line.substring(1);
                first = false;
            }
            line = stripComment(line);
            if (line.isBlank()) continue;
            final int indent = indentOf(line);
            final String text = line.substring(indent).stripTrailing();
            while (!indents.isEmpty() && indent <= indents.get(indents.size() - 1)) {
                indents.remove(indents.size() - 1);
                stack.remove(stack.size() - 1);
            }
            if (text.endsWith(":")) {
                stack.add(unquote(text.substring(0, text.length() - 1).trim()));
                indents.add(indent);
                continue;
            }
            final int colon = text.indexOf(':');
            if (colon < 0) continue;
            final String key = unquote(text.substring(0, colon).trim());
            final String value = text.substring(colon + 1).trim();
            final StringBuilder path = new StringBuilder();
            for (final String part : stack) path.append(part).append('.');
            path.append(key);
            if ((value.startsWith("{") || value.startsWith("[")) && namesProvisioning(path.toString())) {
                throw new InvalidValue(path.toString());
            }
            if (!key.equals(LEAF) && !key.endsWith("." + LEAF)) continue;
            final Boolean parsed = yamlBool(value);
            if (parsed == null) throw new InvalidValue(path.toString());
            values.putIfAbsent(path.toString(), parsed);
        }
        return values;
    }

    /** Whether a dotted YAML path is, or may contain, a provisioning switch. */
    private static boolean namesProvisioning(final String path) {
        for (final String part : path.split("\\.")) {
            if (part.equals("plugins") || part.equals("viaversion") || part.equals("protocollib") || part.equals(LEAF)) {
                return true;
            }
        }
        return false;
    }

    private static int indentOf(final String line) {
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') indent++;
        return indent;
    }

    /** Removes a {@code #} comment that is not inside single or double quotes. */
    private static String stripComment(final String line) {
        char quote = 0;
        for (int index = 0; index < line.length(); index++) {
            final char c = line.charAt(index);
            if (quote == 0) {
                if (c == '"' || c == '\'') quote = c;
                else if (c == '#') return line.substring(0, index);
            } else if (c == quote) {
                quote = 0;
            }
        }
        return line;
    }

    /** A value that is not a boolean, or a flow-style value on a provisioning line; {@link #path} names the key. */
    private static final class InvalidValue extends RuntimeException {
        final String path;

        InvalidValue(final String path) {
            super(path);
            this.path = path;
        }
    }

    /** TOML booleans are lower case only. */
    private static Boolean tomlBool(final String value) {
        if (value.equals("true")) return Boolean.TRUE;
        if (value.equals("false")) return Boolean.FALSE;
        return null;
    }

    /** The spellings the configuration layer's YAML parser turns into booleans (YAML 1.1 true/false forms). */
    private static Boolean yamlBool(final String value) {
        if (value.equals("true") || value.equals("True") || value.equals("TRUE")) return Boolean.TRUE;
        if (value.equals("false") || value.equals("False") || value.equals("FALSE")) return Boolean.FALSE;
        return null;
    }

    private static String unquote(final String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) return value.substring(1, value.length() - 1);
        return value;
    }
}
