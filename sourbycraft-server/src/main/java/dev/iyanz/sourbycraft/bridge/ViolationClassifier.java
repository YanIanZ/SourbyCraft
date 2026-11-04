package dev.iyanz.sourbycraft.bridge;

import java.util.Locale;
import java.util.Set;

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
 * {@code TickThread.ensureTickThread} or by the engine's {@code TickGuard}. SourbyCraft's own refusals
 * ({@code guards/RegionRefusal}) are recognised by their exception type. The base's messages are matched
 * exactly, plus TickThread's "off owning region" reason; a message that merely mentions region threading
 * is not a violation. A cross-region access the guard lets through ({@code guard-severity: LOG}) throws
 * nothing and reaches the bridge through {@link GuardViolations} instead.</p>
 */
public final class ViolationClassifier {

    /** The base's refusal messages, lower-cased; matched whole, never as a substring. */
    private static final Set<String> BASE_MESSAGES = Set.of(
        "unsupported in region threading",
        "not on any region",
        "must use teleportasync while in region threading",
        "cannot teleport async during login in region threading, please schedule this to the next tick",
        "cannot teleport async during logout events in region threading",
        "use region threading hooks");

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
        if (t instanceof dev.iyanz.sourbycraft.guards.RegionRefusal.Refused) {
            return true;
        }
        if (!(t instanceof UnsupportedOperationException) && !(t instanceof IllegalStateException)) {
            return false;
        }
        final String message = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
        if (BASE_MESSAGES.contains(message) || message.contains("off owning region")) {
            return true;
        }
        for (final StackTraceElement frame : t.getStackTrace()) {
            if (frame.getClassName().endsWith(".TickThread") && frame.getMethodName().startsWith("ensureTickThread")) {
                return true;
            }
            // The engine's tick guard refuses a body running on no tick thread at all (an async
            // task opening an inventory, say) with a plain IllegalStateException of its own.
            if (frame.getClassName().endsWith(".TickGuard")) {
                return true;
            }
        }
        return false;
    }
}
