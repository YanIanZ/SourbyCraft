package dev.iyanz.sourbycraft.brand;

import dev.iyanz.sourbycraft.util.SourbyLogger;

/**
 * Runs the warning-only JVM flag advisor ({@link GcAdvisor}) once per process and logs its
 * findings as WARN lines on the {@code SourbyCraft} logger, so they reach {@code logs/latest.log}
 * for the operator reading a lag incident after the fact. Nothing is written to stdout directly:
 * since Build 49 the console carries no separate banner, the launch lines ({@link LaunchLines})
 * and the ready summary ({@link ReadySummary}) identify the build.
 *
 * <p>Never throws: advice must not take the server down.
 */
public final class JvmAdvisor {

    private static volatile boolean ran = false;

    private JvmAdvisor() {}

    /** Logs the advisor's warnings (if any). Only the first call in the process does anything. */
    public static synchronized void logOnce() {
        if (ran) return;
        ran = true;
        try {
            final GcAdvisor.Result advice = GcAdvisor.run();
            if (advice.acceptable()) {
                return;
            }
            SourbyLogger.warn("JVM flag advisor:");
            for (final String warning : advice.warnings()) {
                SourbyLogger.warn("  - " + warning);
            }
            SourbyLogger.warn("  Recommended (Java 25): -Xms2G -Xmx<75-85% of allocation> -XX:+UseZGC "
                + "-XX:ZUncommitDelay=60 --add-modules=jdk.incubator.vector");
        } catch (final Throwable failure) {
            SourbyLogger.warn("JVM flag advisor failed: " + failure.getMessage());
        }
    }
}
