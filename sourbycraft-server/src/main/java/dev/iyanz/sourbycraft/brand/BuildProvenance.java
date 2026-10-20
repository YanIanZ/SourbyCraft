package dev.iyanz.sourbycraft.brand;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * Build provenance baked into {@code META-INF/sourbycraft-build.properties} next to {@link BuildInfo}:
 * the source identity ({@code gitCommit}, {@code treeHash}, {@code dirty}) and every
 * {@code provenance.<Name>} key the root {@code writeBuildInfo} task writes — SHA-256 of each patch
 * directory, of the engine-baseline manifest and of the toolchain lock, the pinned toolchain jar
 * hashes, and the JDK that compiled the sources. The same values are in the jar manifest as
 * {@code Provenance-<Name>}; {@code scripts/verify_build_identity.py} checks that both agree and,
 * with {@code --source-root}, that the patch hashes equal the checkout the jar was built from.
 *
 * <p>Read-only, parsed once. A jar built before provenance existed (or a test classpath without the
 * resource) yields an {@link #isEmpty() empty} provenance and {@code unknown} source fields; nothing
 * here throws.
 *
 * @param commit   full Git commit, or {@code unknown}
 * @param treeHash SHA-256 over the source inputs ({@code scripts/build_identity.py}), or {@code unknown}
 * @param dirty    whether the source tree differed from the commit when the jar was built
 * @param entries  {@code provenance.<Name>} values by {@code <Name>}, in {@link #ORDER} then name order
 */
public record BuildProvenance(String commit, String treeHash, boolean dirty, Map<String, String> entries) {

    /** Display order of the known keys; unknown future keys follow in name order. */
    public static final List<String> ORDER = List.of(
        "Minecraft-Features", "Paper-Features", "Api-Paper-Patches", "Engine-Baseline",
        "Toolchain-Lock", "Patcher-Sha256", "Patcher-Pom-Sha256", "Patcher-Module-Sha256",
        "Clip-Sha256", "Jdk");

    private static final String RESOURCE = "/META-INF/sourbycraft-build.properties";
    private static final String PREFIX = "provenance.";
    private static volatile BuildProvenance cached;

    public BuildProvenance {
        entries = Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /** This jar's provenance (cached: a classpath resource cannot change mid-process). */
    public static BuildProvenance load() {
        BuildProvenance c = cached;
        if (c != null) return c;
        try (InputStream in = BuildProvenance.class.getResourceAsStream(RESOURCE)) {
            return cached = loadFrom(in);
        } catch (IOException e) {
            return cached = loadFrom(null);
        }
    }

    /** Parse a {@code sourbycraft-build.properties}-shaped stream; {@code null} or unreadable gives an empty result. */
    public static BuildProvenance loadFrom(InputStream in) {
        Properties p = new Properties();
        if (in != null) {
            try {
                p.load(in);
            } catch (IOException ignored) {
                // empty provenance
            }
        }
        Map<String, String> found = new TreeMap<>();
        for (String key : p.stringPropertyNames()) {
            if (key.startsWith(PREFIX) && key.length() > PREFIX.length()) {
                found.put(key.substring(PREFIX.length()), p.getProperty(key).trim());
            }
        }
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String key : ORDER) {
            String v = found.remove(key);
            if (v != null) ordered.put(key, v);
        }
        ordered.putAll(found);
        return new BuildProvenance(
            p.getProperty("gitCommit", "unknown").trim(),
            p.getProperty("treeHash", "unknown").trim(),
            Boolean.parseBoolean(p.getProperty("dirty", "true").trim()),
            ordered);
    }

    /** True when the jar carries no {@code provenance.*} key (older build, or no build resource). */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * Operator-facing lines, {@code label: value}, hashes shortened to 12 hex characters (the full
     * values are in the manifest and the properties file). The JDK is printed whole.
     */
    public List<String[]> displayRows() {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Source", shortHash(commit) + " tree " + shortHash(treeHash) + (dirty ? " (dirty)" : " (clean)")});
        if (isEmpty()) {
            rows.add(new String[] {"Provenance", "not recorded in this build"});
            return rows;
        }
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String value = "Jdk".equals(e.getKey()) ? e.getValue() : shortHash(e.getValue());
            rows.add(new String[] {label(e.getKey()), value});
        }
        return rows;
    }

    static String shortHash(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.matches("[0-9a-f]{13,}") ? value.substring(0, 12) : value;
    }

    private static String label(String key) {
        return switch (key) {
            case "Minecraft-Features" -> "Patches: Minecraft features";
            case "Paper-Features" -> "Patches: Paper features";
            case "Api-Paper-Patches" -> "Patches: API (Paper)";
            case "Engine-Baseline" -> "Engine baseline manifest";
            case "Toolchain-Lock" -> "Toolchain lock";
            case "Patcher-Sha256" -> "SourbyPatcher jar";
            case "Patcher-Pom-Sha256" -> "SourbyPatcher POM";
            case "Patcher-Module-Sha256" -> "SourbyPatcher module";
            case "Clip-Sha256" -> "SourbyClip jar";
            case "Jdk" -> "Built with JDK";
            default -> key;
        };
    }
}
