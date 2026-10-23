package dev.iyanz.sourbycraft.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.ConfigFormat;
import com.electronwill.nightconfig.toml.TomlFormat;
import dev.iyanz.sourbycraft.config.yaml.YamlFormat;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Which file a logical config lives in, and which format reads it.
 *
 * <p>SourbyCraft's files are YAML ({@code sourbycraft_global_config.yml}, {@code aurora.yml}); the
 * previous TOML files are still read when the YAML one does not exist yet (a read-only directory, or
 * the boot phase before {@link ConfigFormatMigration} converts them). The format is chosen by the
 * file extension, so tests and {@link SourbyCraftConfig#installForTesting} can keep pointing at a
 * {@code .toml} path.</p>
 */
public final class ConfigSources {

    public static final String YAML_SUFFIX = ".yml";
    public static final String LEGACY_SUFFIX = ".toml";

    private ConfigSources() {
    }

    /** The parser for {@code file}: YAML for {@code .yml}/{@code .yaml}, TOML for anything else. */
    public static ConfigFormat<? extends CommentedConfig> formatFor(final Path file) {
        final String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".yml") || name.endsWith(".yaml")) return YamlFormat.DEFAULT;
        return TomlFormat.instance();
    }

    public static CommentedConfig parse(final Path file, final Reader reader) {
        // "UTF-8 with BOM" from Windows editors: neither parser expects U+FEFF as its first char.
        try {
            final java.io.StringWriter text = new java.io.StringWriter();
            reader.transferTo(text);
            final String raw = text.toString();
            final String body = raw.startsWith("\uFEFF") ? raw.substring(1) : raw;
            return formatFor(file).createParser().parse(new java.io.StringReader(body));
        } catch (final IOException failed) {
            throw new com.electronwill.nightconfig.core.io.ParsingException("Could not read " + file, failed);
        }
    }

    /**
     * The file to read for a logical YAML path: the YAML file when it exists, else the legacy TOML
     * beside it, else {@code null}. A path that is not {@code .yml} (a test seam) is returned when it
     * exists, unchanged.
     */
    public static Path existing(final Path path) {
        if (Files.isRegularFile(path)) return path;
        final Path legacy = legacySibling(path);
        return legacy != null && Files.isRegularFile(legacy) ? legacy : null;
    }

    /** {@code aurora.yml} -> {@code aurora.toml}; {@code null} when the path is not a YAML name. */
    public static Path legacySibling(final Path path) {
        final String name = path.getFileName().toString();
        if (!name.endsWith(YAML_SUFFIX)) return null;
        return path.resolveSibling(name.substring(0, name.length() - YAML_SUFFIX.length()) + LEGACY_SUFFIX);
    }

    /** Whether a file can be opened (used to decide "new install" seeding). */
    public static boolean anyExists(final Path yaml) {
        if (Files.exists(yaml)) return true;
        final Path legacy = legacySibling(yaml);
        return legacy != null && Files.exists(legacy);
    }

    /** Reads a whole file with the format its extension names; {@code IOException} when it cannot be opened. */
    public static CommentedConfig read(final Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            return parse(file, reader);
        }
    }
}
