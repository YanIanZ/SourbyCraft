package dev.iyanz.sourbycraft.brand;

/**
 * The SourbyCraft build line of a crash report, from {@link BuildInfo} (the same source as
 * {@code /ver} and the startup banner).
 *
 * <p>Paper feature patch 0015 puts it first in {@code CraftCrashReport}'s detail. Upstream's
 * {@code BrandInfo} line stays: it is built from the jar manifest ({@code ServerBuildInfo}) and
 * reads {@code 26.2-DEV-...} whenever the manifest has no CI {@code Build-Number}.</p>
 */
public final class CrashReportIdentity {

    private CrashReportIdentity() {
    }

    /** The line for this jar's build. Never throws: a crash report must not fail over it. */
    public static String line() {
        try {
            return line(BuildInfo.load());
        } catch (final Throwable unreadable) {
            return "unknown build (" + unreadable.getClass().getSimpleName() + ")";
        }
    }

    /** E.g. {@code Build 47 — Aurora Nexus (channel 26.2-REL, Minecraft 26.2, built 2026-10-20T10:00:00Z)}. */
    public static String line(final BuildInfo build) {
        final String built = build.buildTimestamp() == null || build.buildTimestamp().isEmpty()
            ? "unknown" : build.buildTimestamp();
        return build.releaseIdentity() + " (channel " + build.version() + ", Minecraft " + build.mcVersion()
            + ", built " + built + ")";
    }
}
