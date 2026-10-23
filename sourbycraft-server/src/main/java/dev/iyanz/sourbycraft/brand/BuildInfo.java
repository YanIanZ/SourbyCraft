package dev.iyanz.sourbycraft.brand;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/**
 * Immutable snapshot of {@code META-INF/sourbycraft-build.properties}, baked into the jar at build
 * time. Read by {@code /ver}, {@code /sourbycraft version}, {@link LaunchLines}, {@link ReadySummary} and the
 * auto-updater so they all report the same version/build/tagline without re-parsing the jar.
 *
 * @param version        the channel version, e.g. {@code "26.2-REL"}
 * @param build          the build label, e.g. {@code "48-dirty"}; may be empty
 * @param buildNumber    the raw build number for update comparison, e.g. {@code "43.1"}, without
 *                       the platform suffix; derived from {@code build} when the properties file
 *                       predates the field
 * @param mcVersion       the Minecraft version this build targets
 * @param tagline        short marketing tagline (baked, not shown on the Build 49 console)
 * @param buildTimestamp ISO-8601 instant the jar was built, or empty if unknown
 * @param gitBranch      source branch, from the same provider as the manifest's Git-Branch
 * @param gitCommit      full source commit, matching the manifest's Git-Commit
 * @param prerelease     whether this jar was built for a GitHub prerelease ({@code release=pre});
 *                       its display identity ends with {@code @<commit>PR!} and is shown in red
 */
public record BuildInfo(
    String version,
    String build,
    String buildNumber,
    String mcVersion,
    String codename,
    String tagline,
    String buildTimestamp,
    String gitBranch,
    String gitCommit,
    boolean prerelease
) {

    /** Compatibility constructor for callers without source metadata. */
    public BuildInfo(String version, String build, String buildNumber, String mcVersion,
                     String codename, String tagline, String buildTimestamp) {
        this(version, build, buildNumber, mcVersion, codename, tagline, buildTimestamp, "", "", false);
    }

    /** Compatibility constructor for callers with source metadata but no release-stage flag. */
    public BuildInfo(String version, String build, String buildNumber, String mcVersion,
                     String codename, String tagline, String buildTimestamp, String gitBranch, String gitCommit) {
        this(version, build, buildNumber, mcVersion, codename, tagline, buildTimestamp, gitBranch, gitCommit, false);
    }

    /**
     * The engine name shown to operators. Since Build 47 it is always Aurora: the release codename
     * ({@code aurora-nexus}) names the release, not the engine, and is shown separately by
     * {@link #codenameDisplay()}.
     */
    public String engineName() {
        return "Aurora";
    }

    /** Human-readable release codename, e.g. aurora-nexus -> Aurora Nexus. */
    public String codenameDisplay() {
        return codenameDisplay(this.codename);
    }

    private static String codenameDisplay(String codename) {
        if (codename == null || codename.isBlank() || "dev".equalsIgnoreCase(codename)) {
            return "Development";
        }
        final String[] parts = codename.split("[-_ ]+");
        final StringBuilder out = new StringBuilder();
        for (final String part : parts) {
            if (part.isBlank()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) out.append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.isEmpty() ? codename : out.toString();
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
        // The classpath resource cannot change mid-process; /ver, the launch lines and the updater all
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
            p.getProperty("buildTimestamp", ""),
            p.getProperty("gitBranch", ""),
            p.getProperty("gitCommit", ""),
            Boolean.parseBoolean(p.getProperty("prerelease", "false").trim())
        );
    }

    /** Display-only channel from the baked version suffix; never assumes a release. */
    public String channel() {
        if (version == null) return "UNKNOWN";
        int dash = version.lastIndexOf('-');
        return dash < 0 ? "UNKNOWN" : normalizeChannel(version.substring(dash + 1));
    }

    private static String normalizeChannel(String channel) {
        if (channel == null) return "UNKNOWN";
        return switch (channel.trim().toUpperCase(Locale.ROOT)) {
            case "REL" -> "REL";
            case "DEV" -> "DEV";
            case "EXP" -> "EXP";
            default -> "UNKNOWN";
        };
    }

    /** Shared display text only; version/build fields retain their machine-readable formats. */
    public String releaseIdentity() {
        final String commit = sourceOrUnknown(gitCommit);
        return formatReleaseIdentity(build, codename, channel(), gitBranch,
            commit.substring(0, Math.min(7, commit.length())), this.prerelease);
    }

    /** Pure release formatter. The codename is the raw build codename; commit is already short. */
    public static String formatReleaseIdentity(String buildNumber, String codename, String channel,
                                               String branch, String shortCommit) {
        return formatReleaseIdentity(buildNumber, codename, channel, branch, shortCommit, false);
    }

    /**
     * Pure release formatter with the publication stage. A prerelease renders
     * {@code Build 50@abc1234PR!} (the GitHub prerelease marker at the end); a normal release keeps
     * {@code Build 50 — Aurora Nexus}; a development build keeps the branch/commit suffix.
     */
    public static String formatReleaseIdentity(String buildNumber, String codename, String channel,
                                               String branch, String shortCommit, boolean prerelease) {
        final String release = "Build " + stripPlatformSuffix(sourceOrUnknown(buildNumber));
        if (prerelease) {
            return release + "@" + sourceOrUnknown(shortCommit) + "PR!";
        }
        final String name = codenameDisplay(codename);
        final String identity = "Development".equals(name) ? release : release + " — " + name;
        final String actualChannel = normalizeChannel(channel);
        return "REL".equals(actualChannel) ? identity
            : identity + " (" + actualChannel + ", " + sourceOrUnknown(branch)
                + "@" + sourceOrUnknown(shortCommit) + ")";
    }

    private static String sourceOrUnknown(String value) {
        return value == null || value.isBlank() || "unknown".equalsIgnoreCase(value.trim())
            ? "?" : value.trim();
    }

    /** Strip historical single-letter platform suffixes from build ids. */
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
