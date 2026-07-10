package dev.iyanz.sourbycraft.util;

import java.util.logging.Level;
import java.util.logging.Logger;

public final class SourbyLogger {

    private static final Logger LOG = Logger.getLogger("SourbyCraft");

    public static void info(String msg)     { LOG.log(Level.INFO, msg); }
    public static void warn(String msg)     { LOG.log(Level.WARNING, msg); }
    public static void error(String msg)    { LOG.log(Level.SEVERE, msg); }
    public static void error(String msg, Throwable t) { LOG.log(Level.SEVERE, msg, t); }

    private SourbyLogger() {}
}
