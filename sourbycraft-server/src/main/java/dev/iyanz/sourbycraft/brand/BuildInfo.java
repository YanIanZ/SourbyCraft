package dev.iyanz.sourbycraft.brand;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public record BuildInfo(
    String version,
    String build,
    String mcVersion,
    String tagline,
    String buildTimestamp
) {
    /**
     * Human-facing Folia build id, e.g. {@code "build 1f"} — the channel version
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
