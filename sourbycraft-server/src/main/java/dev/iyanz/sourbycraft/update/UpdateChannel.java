package dev.iyanz.sourbycraft.update;

import java.util.Locale;

/**
 * SourbyCraft release channel (F1-8). Mirrors the build-time version suffix produced by the
 * Gradle {@code sourbycraftSuffixProvider} (see root {@code build.gradle.kts}): every SourbyCraft
 * build reports its version as {@code <major>-<CHANNEL>} — e.g. {@code 26.2-REL}, {@code 26.2-DEV},
 * {@code 26.2-EXP} — where the channel encodes how stable the build is.
 *
 * <ul>
 *   <li>{@link #REL} — release/* branch, the stable line operators run in production.</li>
 *   <li>{@link #DEV} — development builds (develop / *-dev / no codename).</li>
 *   <li>{@link #EXP} — experimental / feature builds.</li>
 * </ul>
 *
 * <p>The auto-updater is <b>channel-aware</b>: a server on a given channel only ever offers to
 * update to releases on the <i>same</i> channel (a REL server never auto-updates to a DEV build,
 * and vice-versa). {@link #parseFromVersion(String)} extracts the channel from a version string;
 * {@link #tagMatchesChannel(String)} decides whether a GitHub release tag belongs to this channel.
 */
public enum UpdateChannel {
    REL,
    DEV,
    EXP;

    /**
     * Parse the channel out of a SourbyCraft version string like {@code 26.2-REL} or
     * {@code 26.2-1.2-DEV}. The channel is the token after the LAST {@code -}. Unknown/absent
     * suffixes default to {@link #REL} (the conservative, production channel) so a malformed
     * build string can never silently pull a DEV/EXP jar onto a production box.
     */
    public static UpdateChannel parseFromVersion(String version) {
        if (version == null || version.isBlank()) return REL;
        int dash = version.lastIndexOf('-');
        String suffix = dash >= 0 && dash < version.length() - 1
            ? version.substring(dash + 1)
            : version;
        return switch (suffix.trim().toUpperCase(Locale.ROOT)) {
            case "DEV" -> DEV;
            case "EXP" -> EXP;
            default -> REL; // "REL" and anything unrecognised map to the stable channel.
        };
    }

    /** Lenient parse for an operator-configured channel string; null/blank/unknown -> null. */
    public static UpdateChannel parseConfig(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * True when a GitHub release tag name belongs to THIS channel. A SourbyCraft release tag is
     * expected to carry the channel suffix (e.g. {@code 26.2-REL}); the check is the tag's own
     * parsed channel equalling this one. Case-insensitive; tolerant of a leading {@code v}.
     */
    public boolean tagMatchesChannel(String tagName) {
        if (tagName == null || tagName.isBlank()) return false;
        String t = tagName.trim();
        if (t.regionMatches(true, 0, "v", 0, 1)) t = t.substring(1);
        return parseFromVersion(t) == this;
    }

    /** The suffix token ({@code REL}/{@code DEV}/{@code EXP}) — the way this channel appears in a version. */
    public String suffix() {
        return name();
    }
}
