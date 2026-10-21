package dev.iyanz.sourbycraft.guards;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.RegisteredListener;

/**
 * Logs once per plugin and legacy event where SourbyCraft runs that plugin's listener, so an
 * operator reading the log sees that a deprecated synchronous event is honoured and on which
 * thread, instead of guessing from behaviour. One line per (event, plugin) for the life of the
 * process.
 */
final class LegacyListenerNotice {

    private static final Set<String> SEEN = ConcurrentHashMap.newKeySet();

    private LegacyListenerNotice() {
    }

    /**
     * Logs, through {@code log}, one line for each plugin listening on {@code handlers} that has
     * not been reported for {@code event} before.
     *
     * @return how many plugins were reported now
     */
    static int report(final HandlerList handlers, final String event, final String where, final Consumer<String> log) {
        int reported = 0;
        for (final RegisteredListener listener : handlers.getRegisteredListeners()) {
            final String plugin = listener.getPlugin() == null ? "?" : listener.getPlugin().getName();
            if (SEEN.add(event + '\0' + plugin)) {
                log.accept("Plugin " + plugin + " listens to the deprecated synchronous " + event + "; SourbyCraft fires it " + where + ".");
                reported++;
            }
        }
        return reported;
    }

    /** Test seam: forget what was reported. */
    static void resetForTests() {
        SEEN.clear();
    }
}
