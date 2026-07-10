package dev.iyanz.sourbycraft.brand;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public record BuildInfo(
    String version,
    String mcVersion,
    String tagline,
    String buildTimestamp
) {
    private static final String RESOURCE = "/META-INF/sourbycraft-build.properties";

    public static BuildInfo load() {
        try (InputStream in = BuildInfo.class.getResourceAsStream(RESOURCE)) {
            return loadFrom(in);
        } catch (IOException e) {
            return loadFrom(null);
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
            p.getProperty("mcVersion", "unknown"),
            p.getProperty("tagline", "Lightning Fast Performance · Feature Rich"),
            p.getProperty("buildTimestamp", "")
        );
    }
}
