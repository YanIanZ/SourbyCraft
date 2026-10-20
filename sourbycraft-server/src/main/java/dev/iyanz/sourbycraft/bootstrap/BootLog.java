package dev.iyanz.sourbycraft.bootstrap;

import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Logging for the pre-plugin-scan provisioning phase, through the log4j logger {@code "SourbyCraft"}.
 *
 * <p>Phase 1 runs from the patched Minecraft {@code Main}, after log4j is configured (Paper's
 * {@code [bootstrap]} lines precede it), so these lines get the normal timestamp, level and
 * {@code [SourbyCraft]} tag and reach {@code logs/latest.log}. They used to be written with
 * {@code System.out}/{@code System.err}, which bypassed log4j. The log4j API is used directly rather
 * than {@code java.util.logging}: at this point JUL is not yet routed to log4j under SourbyClip
 * (seen on a test boot: the line came out in JUL's default two-line format, naming this class).
 *
 * <p>log4j is looked up reflectively so these sources still compile and run without it on the
 * classpath (the standalone provisioning probe in {@code scripts/test_plugin_provisioning.py});
 * there the lines fall back to {@code System.out}/{@code System.err} as before.
 */
final class BootLog {

    private static volatile BiConsumer<Level, String> sink = defaultSink();

    private BootLog() {}

    static void info(final String message) { sink.accept(Level.INFO, message); }

    static void warn(final String message) { sink.accept(Level.WARNING, message); }

    private static BiConsumer<Level, String> defaultSink() {
        try {
            final Class<?> manager = Class.forName("org.apache.logging.log4j.LogManager");
            final Object logger = manager.getMethod("getLogger", String.class).invoke(null, "SourbyCraft");
            final java.lang.reflect.Method info = logger.getClass().getMethod("info", String.class);
            final java.lang.reflect.Method warn = logger.getClass().getMethod("warn", String.class);
            return (level, message) -> {
                try {
                    (level.intValue() >= Level.WARNING.intValue() ? warn : info).invoke(logger, message);
                } catch (final ReflectiveOperationException e) {
                    fallback(level, message);
                }
            };
        } catch (final ReflectiveOperationException | LinkageError e) {
            return BootLog::fallback;
        }
    }

    private static void fallback(final Level level, final String message) {
        (level.intValue() >= Level.WARNING.intValue() ? System.err : System.out).println("[SourbyCraft] " + message);
    }

    /** Test seam: routes messages to {@code capture} until the returned handle is closed. */
    static AutoCloseable captureForTesting(final BiConsumer<Level, String> capture) {
        final BiConsumer<Level, String> previous = sink;
        sink = capture;
        return () -> sink = previous;
    }
}
