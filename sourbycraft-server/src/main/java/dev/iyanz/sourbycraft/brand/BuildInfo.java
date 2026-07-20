package dev.iyanz.sourbycraft.brand;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Immutable snapshot of {@code META-INF/sourbycraft-build.properties}, baked into the jar at build
 * time. Read by {@code /ver}, {@code /sourbycraft version}, {@link SourbyCraftBanner} and the
 * auto-updater so they all report the same version/build/tagline without re-parsing the jar.
 *
 * @param version        the channel version, e.g. {@code "26.2-REL"}
 * @param build          the human-facing build id, e.g. {@code "4c"} (c = Canvas base); may be empty
 * @param mcVersion       the Minecraft version this build targets
 * @param tagline        short marketing tagline shown in the startup banner
 * @param buildTimestamp ISO-8601 instant the jar was built, or empty if unknown
 */
public record BuildInfo(
    String version,
    String build,
    String mcVersion,
    String tagline,
    String buildTimestamp
) {
    /**
     * Human-facing build id, e.g. {@code "build 4c"} (c = Canvas base) — the channel version
     * ({@code 26.2-REL}) is deliberately NOT shown here (it stays on the
     * "Implementing API version" line as the protocol version). Falls back to the
     * bare channel version only when no build id is present.
     */
    public String displayVersion() {
        return (build == null || build.isEmpty())
            ? version
            : "build " + build;
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
        return new BuildInfo(
            p.getProperty("version", "dev"),
            p.getProperty("build", ""),
            p.getProperty("mcVersion", "unknown"),
            p.getProperty("tagline", "Lightning Fast Performance · Feature Rich"),
            p.getProperty("buildTimestamp", "")
        );
    }
}
