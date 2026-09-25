package dev.iyanz.sourbycraft.brand;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * Immutable snapshot of {@code META-INF/sourbycraft-build.properties}, baked into the jar at build
 * time. Read by {@code /ver}, {@code /sourbycraft version}, {@link SourbyCraftBanner} and the
 * auto-updater so they all report the same version/build/tagline without re-parsing the jar.
 *
 * @param version        the channel version, e.g. {@code "26.2-REL"}
 * @param build          the human-facing build id, e.g. {@code "4c"} (c = Canvas base); may be empty
 * @param buildNumber    the raw build number for update comparison, e.g. {@code "43.1"}, without
 *                       the platform suffix; derived from {@code build} when the properties file
 *                       predates the field
 * @param mcVersion       the Minecraft version this build targets
 * @param tagline        short marketing tagline shown in the startup banner
 * @param buildTimestamp ISO-8601 instant the jar was built, or empty if unknown
 */
public record BuildInfo(
    String version,
    String build,
    String buildNumber,
    String mcVersion,
    String codename,
    String tagline,
    String buildTimestamp
) {

    /**
     * The engine name shown to operators, derived from the release codename in
     * {@code gradle.properties} so the banner cannot drift from the release it ships in.
     * Falls back to the product name when no codename is stamped.
     */
    public String engineName() {
        return "Aurora";
    }

    /** Human-readable release codename, e.g. aurora-nexus -> Aurora Nexus. */
    public String codenameDisplay() {
        if (this.codename == null || this.codename.isBlank() || "dev".equalsIgnoreCase(this.codename)) {
            return "Development";
        }
        final String[] parts = this.codename.split("[-_ ]+");
        final StringBuilder out = new StringBuilder();
        for (final String part : parts) {
            if (part.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) out.append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.isEmpty() ? this.codename : out.toString();
    }

    /**
     * Human-facing build id, e.g. {@code "build 4c"} (c = Canvas base) — the channel version
     * ({@code 26.2-REL}) is deliberately NOT shown here (it stays on the
     * "Implementing API version" line as the protocol version). Falls back to the
     * bare channel version only when no build id is present.
     */
    public String displayVersion() {
        return (build == null || build.isEmpty())
            ? version
            : "Build " + stripPlatformSuffix(build);
    }

    /**
     * Branded server-version string for every version-reporting surface — the Folia watchdog line,
     * {@code CraftServer.getVersion()}/{@code Bukkit.getVersion()}, {@code getVersionMessage()},
     * crash reports and {@code ServerBuildInfoImpl.asString(...)}. Returns e.g.
     * {@code "SourbyCraft build 40c"}, replacing the raw upstream {@code "26.2-DEV-<gitHash>"} so no
     * surface leaks the dev/commit string. Never throws — degrades to {@code "SourbyCraft build ?"}
     * when the build id is absent (mirrors {@code PaperBootstrap.sourbyLoadingLine}'s fallback).
     */
    public static String serverVersionString() {
        String id = "?";
        try {
            final BuildInfo bi = load();
            if (bi != null && bi.build() != null && !bi.build().isEmpty()) {
                id = bi.build();
            }
        } catch (final Throwable ignored) {
            // never break a version-reporting path over the build id
        }
        return "SourbyCraft Build " + stripPlatformSuffix(id);
    }

    private static final String RESOURCE = "/META-INF/sourbycraft-build.properties";

    private static volatile BuildInfo CACHED;

    /**
     * Load (and cache) {@value #RESOURCE} from the classpath. Safe to call repeatedly — the jar
     * resource cannot change mid-process, so only the first call actually parses it. Falls back to
     * {@link #loadFrom(InputStream) loadFrom(null)} defaults if the resource is missing/unreadable.
     */
    public static BuildInfo load() {
        // The classpath resource cannot change mid-process; /ver, the banner and the updater all
        // call this — parse the properties once instead of re-reading the jar per call.
        BuildInfo cached = CACHED;
        if (cached != null) return cached;
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            return CACHED = loadFrom(in);
        } catch (IOException e) {
            return CACHED = loadFrom(null);
        }
    }

    /**
     * Parse a {@code sourbycraft-build.properties}-shaped stream into a {@link BuildInfo}. Any
     * missing key (or a {@code null}/unreadable stream) falls back to a sane per-field default so
     * this never throws. Exposed (not private) so callers/tests can build a {@link BuildInfo} from
     * an arbitrary source instead of the classpath resource.
     */
    public static BuildInfo loadFrom(InputStream in) {
        Properties p = new Properties();
        if (in != null) {
            try {
                p.load(in);
            } catch (IOException e) {
                // fall through to defaults
            }
        }
        String build = p.getProperty("build", "");
        String buildNumber = p.getProperty("buildNumber", "");
        if (buildNumber.isEmpty() && !build.isEmpty()) {
            buildNumber = stripPlatformSuffix(build);
        }
        return new BuildInfo(
            p.getProperty("version", "dev"),
            build,
            buildNumber,
            p.getProperty("mcVersion", "unknown"),
            p.getProperty("codename", ""),
            p.getProperty("tagline", "Lightning Fast Performance · Feature Rich"),
            p.getProperty("buildTimestamp", "")
        );
    }

    /**
     * Strip the trailing single-letter platform suffix ({@code c}=Canvas, {@code f}=Folia) from
     * a build id when it follows a digit or dot, returning the raw build number for comparison.
     * E.g. {@code "43c"} -> {@code "43"}, {@code "43.1c"} -> {@code "43.1"},
     * {@code "44-hotfix"} -> {@code "44-hotfix"} (no known suffix).
     */
    public String releaseIdentity() {
        final String release = displayVersion();
        final String name = codenameDisplay();
        return "Development".equals(name) ? release : release + " — " + name;
    }

    private static String stripPlatformSuffix(String build) {
        if (build.length() >= 2) {
            char last = build.charAt(build.length() - 1);
            if ((last == 'c' || last == 'f') && !Character.isLetter(build.charAt(build.length() - 2))) {
                return build.substring(0, build.length() - 1);
            }
        }
        return build;
    }
}
