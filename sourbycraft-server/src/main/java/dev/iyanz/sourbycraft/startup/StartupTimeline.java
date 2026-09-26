package dev.iyanz.sourbycraft.startup;

import dev.iyanz.sourbycraft.util.SourbyLogger;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;

/**
 * Collects this boot's phase timings and, when the server reports ready, turns them into a
 * {@link StartupProfile}: logged against the previous boot of the same start class, then saved.
 *
 * <p>Total time is measured from JVM start (the runtime MXBean) to {@link ServerLoadEvent} with
 * type STARTUP, which fires after worlds and plugins are up.</p>
 */
public final class StartupTimeline {

    static final Path PROFILE_FILE = Path.of("sourbycraft_config", "cache", "startup-profile.properties");

    private static final Map<String, Long> PHASES = new LinkedHashMap<>();
    private static volatile String startClass = "unknown";
    private static volatile StartupProfile last;
    private static volatile StartupProfile previous;

    private StartupTimeline() {}

    /** Adds a phase; repeated names accumulate. */
    public static void phase(final String name, final long nanos) {
        synchronized (PHASES) {
            PHASES.merge(name, Math.max(0L, nanos) / 1_000_000L, Long::sum);
        }
    }

    static void startClass(final String value) {
        startClass = value;
    }

    /** This boot's profile once the server is ready, else {@code null}. */
    public static StartupProfile last() {
        return last;
    }

    /** The profile the previous boot saved, once this boot has completed; else {@code null}. */
    public static StartupProfile previous() {
        return previous;
    }

    public static void registerListener(final Plugin owner) {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onLoad(final ServerLoadEvent event) {
                if (event.getType() != ServerLoadEvent.LoadType.STARTUP) return;
                complete(ManagementFactory.getRuntimeMXBean().getStartTime(), System.currentTimeMillis(), PROFILE_FILE);
            }
        }, owner);
    }

    static StartupProfile complete(final long jvmStartMillis, final long nowMillis, final Path file) {
        final Map<String, Long> phases;
        synchronized (PHASES) {
            phases = new LinkedHashMap<>(PHASES);
        }
        final StartupProfile current = new StartupProfile(startClass, Math.max(0L, nowMillis - jvmStartMillis), phases);
        final StartupProfile before = StartupProfile.load(file);
        previous = before;
        last = current;
        SourbyLogger.info("Aurora startup: " + current.totalMillis() + " ms to ready, " + current.startClass()
            + " start; " + current.compareTo(before));
        try {
            current.save(file);
        } catch (final IOException unwritable) {
            SourbyLogger.warn("Aurora startup profile could not be saved: " + unwritable.getMessage());
        }
        return current;
    }
}
