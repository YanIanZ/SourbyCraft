package dev.iyanz.sourbycraft.util;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class SourbyLogger {

    private static final Logger LOG = Logger.getLogger("SourbyCraft");

    /**
     * Verbose gate for the perf-engine's per-field boot/tier dumps (knob-by-knob loads, per-field
     * KnobEnforcer bridge lines). Off by default so the boot log stays tidy; enable with
     * {@code -Dsourbycraft.debug=true} to surface the full per-key detail.
     */
    public static final boolean DEBUG = Boolean.getBoolean("sourbycraft.debug");

    public static void info(String msg)     { LOG.log(Level.INFO, msg); }
    public static void warn(String msg)     { LOG.log(Level.WARNING, msg); }
    public static void error(String msg)    { LOG.log(Level.SEVERE, msg); }
    public static void error(String msg, Throwable t) { LOG.log(Level.SEVERE, msg, t); }

    /** Logs at INFO only when {@code -Dsourbycraft.debug=true}; otherwise a no-op. */
    public static void debug(String msg)    { if (DEBUG) LOG.log(Level.INFO, msg); }

    private SourbyLogger() {}
}
