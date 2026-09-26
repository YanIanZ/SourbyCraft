package dev.iyanz.sourbycraft.bridge;

import java.util.Locale;

/**
 * Tells a region-threading violation apart from an ordinary plugin bug.
 *
 * <p>Both reach the bridge as a throwable escaping a task. Only the first is the bridge's
 * business: it means the plugin touched state its task's thread does not own, which the base
 * refused. The second is the plugin failing on its own terms and is recorded as the last failure
 * without counting toward quarantine, as the plain server would log it and carry on.</p>
 *
 * <p>The base signals ownership violations with {@code UnsupportedOperationException} ("Unsupported
 * in region threading", "Not on any region") and with {@code IllegalStateException} thrown from
 * {@code TickThread.ensureTickThread}. Both shapes are matched here; anything else is not a
 * violation.</p>
 */
public final class ViolationClassifier {

    private ViolationClassifier() {}

    public static boolean isRegionViolation(final Throwable thrown) {
        Throwable current = thrown;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (matches(current)) return true;
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }

    private static boolean matches(final Throwable t) {
        if (!(t instanceof UnsupportedOperationException) && !(t instanceof IllegalStateException)) {
            return false;
        }
        final String message = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("region threading") || message.contains("not on any region")
            || message.contains("off owning region") || message.contains("regionized")) {
            return true;
        }
        for (final StackTraceElement frame : t.getStackTrace()) {
            if (frame.getClassName().endsWith(".TickThread") && frame.getMethodName().startsWith("ensureTickThread")) {
                return true;
            }
        }
        return false;
    }
}
