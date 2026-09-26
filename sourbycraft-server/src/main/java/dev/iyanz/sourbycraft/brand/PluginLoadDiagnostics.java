package dev.iyanz.sourbycraft.brand;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures plugin-load failures by tailing the root JUL logger and pulling
 * the plugin name out of Paper's "Could not load plugin 'NAME.jar'" message.
 * The handler is installed once at SourbyCraftConfig.init(), which runs in
 * DedicatedServer#initServer before CraftServer#loadPlugins fires — that's
 * the only window where a load failure record is emitted.
 *
 * <p>Enable failures ("Error occurred while enabling NAME vX") are captured too, so
 * {@code /plugins} can show a plugin that loaded but failed to enable as FAILED rather than
 * as merely disabled.
 *
 * <p>/sys reads {@link #recent()} to surface the failed plugin names + the
 * first line of the stack trace, so operators can see "X failed, last
 * message: Y" without digging through the full log.
 */
public final class PluginLoadDiagnostics {

    private static final int MAX_ENTRIES = 16;
    private static final Pattern LOAD_FAILURE = Pattern.compile(
            "Could not load plugin '([^']+)'(?:\\s+in folder '[^']+')?");
    /**
     * Paper's enable failure, from both the legacy and the Paper plugin managers. The captured
     * group is the display name, {@code "Name vVersion"}.
     */
    private static final Pattern ENABLE_FAILURE = Pattern.compile(
            "Error occurred while enabling (.+?) \\(Is it up to date\\?\\)");
    private static final List<Entry> ENTRIES = new CopyOnWriteArrayList<>();
    private static final Set<String> ENABLE_FAILURES = ConcurrentHashMap.newKeySet();
    private static volatile boolean installed = false;

    /** One captured plugin-load failure: the jar name and a short human-readable reason. */
    public record Entry(String pluginJar, String reason) {}

    private PluginLoadDiagnostics() {}

    /**
     * Install the root-logger tailing handler that captures plugin-load failures. Idempotent —
     * safe to call more than once; only the first call attaches the handler. Must run before
     * {@code CraftServer#loadPlugins} so no load-failure log record is missed.
     */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        Logger root = LogManager.getLogManager().getLogger("");
        root.addHandler(new Handler() {
            /** Hands every emitted {@link LogRecord} to {@link #capture}. */
            @Override
            public void publish(LogRecord record) {
                if (record == null) return;
                capture(record.getLevel(), record.getMessage(), record.getThrown());
            }
            /** No-op — this handler holds no buffered/flushable state. */
            @Override public void flush() {}
            /** No-op — this handler is never detached; nothing to release. */
            @Override public void close() {}
        });
    }

    /**
     * Records one log line if it is a plugin load or enable failure. Package-visible so the
     * matching can be tested without a live logger.
     */
    static void capture(final Level level, final String message, final Throwable thrown) {
        if (level == null || message == null) return;
        if (level.intValue() < Level.WARNING.intValue()) return;
        final Matcher enable = ENABLE_FAILURE.matcher(message);
        if (enable.find()) {
            // Bounded: one entry per plugin display name, and plugins are finite per run.
            ENABLE_FAILURES.add(enable.group(1));
            return;
        }
        Matcher m = LOAD_FAILURE.matcher(message);
        if (!m.find()) return;
        String jar = m.group(1);
        String reason = firstThrowableLine(thrown);
        if (reason == null) reason = message;
        if (ENTRIES.size() >= MAX_ENTRIES) {
            ENTRIES.remove(0);
        }
        ENTRIES.add(new Entry(jar, reason));
    }

    /**
     * Whether an enable failure was captured for the plugin with this name. Paper logs the
     * display name, {@code "Name vVersion"}, so the name is matched as that prefix.
     */
    public static boolean enableFailed(final String pluginName) {
        if (pluginName == null) return false;
        final String prefix = pluginName + " v";
        for (final String display : ENABLE_FAILURES) {
            if (display.equals(pluginName) || display.startsWith(prefix)) return true;
        }
        return false;
    }

    /** Clears captured state; tests only. */
    static void resetForTest() {
        ENTRIES.clear();
        ENABLE_FAILURES.clear();
    }

    /** Every captured plugin-load failure so far, oldest first, capped at {@link #MAX_ENTRIES}. */
    public static List<Entry> recent() {
        return Collections.unmodifiableList(ENTRIES);
    }

    /** Count of captured plugin-load failures so far (may be less than the true count if capped). */
    public static int failedCount() {
        return ENTRIES.size();
    }

    private static String firstThrowableLine(Throwable t) {
        if (t == null) return null;
        // Walk to the deepest cause — that's typically where the symbol-not-found
        // / class-loader error lives. Bail out if a cycle (cause == self).
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getClass().getSimpleName();
        if (cur.getMessage() != null) {
            msg += ": " + cur.getMessage();
        }
        // Truncate to one ~120-char line so the /sys panel stays readable.
        return msg.length() > 120 ? msg.substring(0, 117) + "..." : msg;
    }
}
