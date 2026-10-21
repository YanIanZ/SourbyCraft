package dev.iyanz.sourbycraft.guards;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

/**
 * Logs once per plugin and legacy event where SourbyCraft runs that plugin's listener, so an
 * operator reading the log sees that a deprecated synchronous event is honoured and on which
 * thread, instead of guessing from behaviour. One line per (event, plugin) for the life of the
 * process.
 *
 * <p>Called on every legacy event, so the common case must cost nothing: the handler list's
 * registered-listener array is only rebuilt when a listener is registered or unregistered, so the
 * listeners are walked only when that array is not the one walked last time for the event.</p>
 */
final class LegacyListenerNotice {

    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();
    /** Per event: the listener array last walked (identity). */
    private static final Map<String, RegisteredListener[]> WALKED = new ConcurrentHashMap<>();

    private LegacyListenerNotice() {
    }

    /**
     * Logs, through {@code log}, one line for each plugin listening on {@code handlers} that has
     * not been reported for {@code event} before.
     *
     * @return how many plugins were reported now
     */
    static int report(final HandlerList handlers, final String event, final String where, final Consumer<String> log) {
        final RegisteredListener[] listeners = handlers.getRegisteredListeners();
        if (WALKED.get(event) == listeners) {
            return 0; // nothing registered or unregistered since the last walk
        }
        int reported = 0;
        for (final RegisteredListener listener : listeners) {
            final Plugin owner = listener.getPlugin();
            final String plugin = owner == null ? "?" : owner.getName();
            if (SEEN.add(event + '\0' + plugin)) {
                log.accept("Plugin " + plugin + " listens to the deprecated synchronous " + event + "; SourbyCraft fires it " + where + ".");
                reported++;
            }
        }
        WALKED.put(event, listeners);
        return reported;
    }

    /** Test seam: forget what was reported. */
    static void resetForTests() {
        SEEN.clear();
        WALKED.clear();
    }
}
