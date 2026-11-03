package dev.iyanz.sourbycraft.api;

import java.util.Objects;
import org.jspecify.annotations.NullMarked;

/**
 * Added in Build 54. The running SourbyCraft server's build, read from the
 * {@code META-INF/sourbycraft-build.properties} file baked into the server jar (what {@code /ver} reports).
 *
 * @param buildNumber the build number, e.g. {@code 54} or, for a hotfix, {@code 48.3}
 * @param buildLabel the build label, e.g. {@code 54}, or {@code 54-dirty} for a jar built from a modified tree
 * @param codename the release codename, e.g. {@code aurora-nexus}; {@code dev} for a development build
 * @param apiVersion the version of the {@code dev.iyanz.sourbycraft:sourbyapi} artifact this server ships, which is
 *     the server's channel version, e.g. {@code 26.2-REL} or {@code 26.2-DEV}
 * @param minecraftVersion the Minecraft version, e.g. {@code 26.2}
 * @param gitCommit the source commit the jar was built from; empty when unknown
 */
@NullMarked
public record SourbyBuild(String buildNumber, String buildLabel, String codename, String apiVersion,
                          String minecraftVersion, String gitCommit) {

    public SourbyBuild {
        Objects.requireNonNull(buildNumber, "buildNumber");
        Objects.requireNonNull(buildLabel, "buildLabel");
        Objects.requireNonNull(codename, "codename");
        Objects.requireNonNull(apiVersion, "apiVersion");
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(gitCommit, "gitCommit");
    }

    /**
     * The whole-number part of {@link #buildNumber()} ({@code 48} for {@code 48.3}), for "at least Build N" checks;
     * {@code -1} when it is not a number.
     */
    public int build() {
        int end = 0;
        while (end < this.buildNumber.length() && Character.isDigit(this.buildNumber.charAt(end))) end++;
        if (end == 0 || end > 9) return -1;
        return Integer.parseInt(this.buildNumber.substring(0, end));
    }

    /** Whether this server is at least Build {@code build}. */
    public boolean atLeast(final int build) {
        return build() >= build;
    }
}
