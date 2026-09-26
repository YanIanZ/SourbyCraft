package dev.iyanz.sourbycraft.startup;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * The parts of a plugin descriptor the startup index needs, read without loading the plugin.
 *
 * <p>This is analysis, not loading: nothing here creates a class loader, touches plugin code or
 * replaces the plugin manager's own parse. The server still parses every descriptor itself when it
 * loads plugins; this record only lets SourbyCraft say, before that happens, which plugins the
 * region-threading base will refuse.</p>
 *
 * @param name the declared plugin name
 * @param version the declared version, or empty
 * @param main the declared main class, or empty
 * @param paperPlugin whether the descriptor is {@code paper-plugin.yml}
 * @param regionSupported whether the base will accept it: {@code folia-supported} or
 *                        {@code canvas-supported}, evaluated the way the base evaluates them
 */
public record PluginDescriptor(String name, String version, String main, boolean paperPlugin,
                               boolean regionSupported) {

    static final String PAPER_DESCRIPTOR = "paper-plugin.yml";
    static final String LEGACY_DESCRIPTOR = "plugin.yml";

    /**
     * Reads the descriptor of a plugin jar, preferring {@code paper-plugin.yml} as Paper does.
     *
     * @return the descriptor, or {@code null} when the jar carries neither file or the file does
     *         not declare a name
     * @throws IOException when the jar cannot be read
     */
    public static PluginDescriptor read(final Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(PAPER_DESCRIPTOR);
            final boolean paper = entry != null;
            if (entry == null) {
                entry = zip.getEntry(LEGACY_DESCRIPTOR);
            }
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry);
                 Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return parse(reader, paper);
            }
        }
    }

    static PluginDescriptor parse(final Reader reader, final boolean paper) throws IOException {
        final Object document;
        try {
            document = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        } catch (final RuntimeException malformed) {
            throw new IOException("malformed plugin descriptor", malformed);
        }
        if (!(document instanceof Map<?, ?> map)) {
            return null;
        }
        final String name = scalar(map.get("name"));
        if (name == null || name.isBlank()) {
            return null;
        }
        final String version = scalar(map.get("version"));
        final String main = scalar(map.get("main"));
        return new PluginDescriptor(clean(name), clean(version), clean(main), paper,
            paper ? paperSupported(map) : legacySupported(map));
    }

    /** {@code PaperPluginMeta}: either boolean flag is enough. */
    private static boolean paperSupported(final Map<?, ?> map) {
        return "true".equalsIgnoreCase(scalar(map.get("folia-supported")))
            || "true".equalsIgnoreCase(scalar(map.get("canvas-supported")));
    }

    /**
     * {@code PluginDescriptionFile}: {@code folia-supported} is read as a string and must equal
     * {@code true}; {@code canvas-supported} is consulted only when the Folia flag is absent or
     * {@code false}.
     */
    private static boolean legacySupported(final Map<?, ?> map) {
        String flag = map.get("folia-supported") == null ? null : scalar(map.get("folia-supported"));
        if ((flag == null || flag.equalsIgnoreCase("false")) && map.containsKey("canvas-supported")) {
            flag = scalar(map.get("canvas-supported"));
        }
        return flag != null && flag.equalsIgnoreCase("true");
    }

    private static String scalar(final Object value) {
        return value == null ? null : value.toString();
    }

    private static String clean(final String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }

    /** The cache payload: one field per line. Fields never contain line breaks. */
    String encode() {
        return String.join("\n", this.name, this.version, this.main,
            Boolean.toString(this.paperPlugin), Boolean.toString(this.regionSupported));
    }

    /** The descriptor in a payload, or {@code null} when the payload is not one. */
    static PluginDescriptor decode(final String payload) {
        final String[] fields = payload.split("\n", -1);
        if (fields.length != 5 || fields[0].isEmpty()) {
            return null;
        }
        return new PluginDescriptor(fields[0], fields[1], fields[2],
            Boolean.parseBoolean(fields[3]), Boolean.parseBoolean(fields[4]));
    }
}
