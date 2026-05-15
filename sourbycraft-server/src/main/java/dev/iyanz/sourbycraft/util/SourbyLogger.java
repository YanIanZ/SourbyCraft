package dev.iyanz.sourbycraft.util;

public final class SourbyLogger {

    public static void info(String msg)     { System.out.println("[SourbyCraft] " + msg); }
    public static void warn(String msg)     { System.out.println("[SourbyCraft] [WARN] " + msg); }
    public static void error(String msg)    { System.out.println("[SourbyCraft] [ERROR] " + msg); }
    public static void error(String msg, Throwable t) {
        System.out.println("[SourbyCraft] [ERROR] " + msg);
        t.printStackTrace(System.out);
    }

    private SourbyLogger() {}
}
