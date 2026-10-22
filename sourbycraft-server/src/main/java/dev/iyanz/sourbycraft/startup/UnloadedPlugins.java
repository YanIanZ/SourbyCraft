package dev.iyanz.sourbycraft.startup;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.ServerLoadEvent;
import org.bukkit.plugin.Plugin;

/**
 * Plugins whose jar the startup index read but that the plugin manager never created, recorded as
 * load failures for {@code /plugins}, {@code /perf plugins} and {@code /sys}.
 *
 * <p>A plugin refused before it exists — a missing hard dependency, a dependency cycle, an invalid
 * descriptor — never reaches {@code PluginManager#getPlugins()}, and Paper's modern loader reports it
 * only as a log line ({@code Could not load 'plugins/x.jar' in 'plugins'}) that the log capture in
 * {@code PluginLoadDiagnostics} does not match. So {@code /plugins} said "0 failed" while such a
 * plugin was missing. This compares the two sources SourbyCraft itself holds: the descriptors the
 * startup index read from every jar in {@code plugins/}, and the plugin manager after startup
 * ({@link ServerLoadEvent} {@code STARTUP}). Nothing here parses the log.</p>
 *
 * <p>The reason comes from the same {@link DependencyGraph} that logged the startup warning, which
 * resolves {@code provides} as Paper's loader does. A jar without a descriptor is treated as a library,
 * as the startup index treats it, and is not reported.</p>
 */
public final class UnloadedPlugins {

    /** Prefix of the startup index's failure text for a descriptor that is not valid YAML. */
    private static final String MALFORMED = "malformed plugin descriptor";

    /**
     * @param jar the jar's file name
     * @param name the declared plugin name, or {@code null} when the descriptor could not be read
     * @param reason why the plugin manager did not create it
     */
    public record Unloaded(String jar, String name, String reason) {}

    private UnloadedPlugins() {}

    /**
     * The indexed plugins that did not load.
     *
     * @param loaded whether the plugin manager holds a plugin of this name
     */
    static List<Unloaded> find(final List<PluginStartupIndex.Indexed> indexed, final DependencyGraph.Report graph,
                               final Predicate<String> loaded) {
        final Map<String, PluginDescriptor> absent = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (final PluginStartupIndex.Indexed item : indexed) {
            final PluginDescriptor d = item.descriptor();
            if (d != null && !loaded.test(d.name())) absent.putIfAbsent(d.name(), d);
        }
        final List<Unloaded> out = new ArrayList<>();
        for (final PluginStartupIndex.Indexed item : indexed) {
            final String jar = fileName(item.jar());
            final PluginDescriptor d = item.descriptor();
            if (d == null) {
                if (item.failure() != null && item.failure().contains(MALFORMED)) {
                    out.add(new Unloaded(jar, null, "plugin.yml / paper-plugin.yml is not valid YAML; the plugin manager did not load it"));
                }
                continue;
            }
            if (loaded.test(d.name())) continue;
            out.add(new Unloaded(jar, d.name(), reason(d, graph, absent.keySet())));
        }
        return out;
    }

    static String reason(final PluginDescriptor d, final DependencyGraph.Report graph, final Set<String> absent) {
        final List<String> missing = graph == null ? null : graph.missing().get(d.name());
        if (missing != null && !missing.isEmpty()) {
            return "Missing hard dependency: " + String.join(", ", missing) + "; the plugin manager did not load it";
        }
        if (graph != null) {
            for (final List<String> cycle : graph.cycles()) {
                if (cycle.contains(d.name())) {
                    return "In a dependency cycle (" + String.join(" -> ", cycle) + "); the plugin manager did not load it";
                }
            }
        }
        final Set<String> failedDeps = new TreeSet<>();
        for (final String dep : d.depend()) {
            if (absent.contains(dep)) failedDeps.add(dep);
        }
        if (!failedDeps.isEmpty()) {
            return "Hard dependency did not load: " + String.join(", ", failedDeps) + "; the plugin manager did not load it";
        }
        return "The plugin manager did not load it; the server log has the cause";
    }

    private static String fileName(final Path jar) {
        final Path name = jar.getFileName();
        return name == null ? jar.toString() : name.toString();
    }

    /** Records {@link #find} results once plugins are loaded and enabled; never throws into the event. */
    public static void registerListener(final Plugin owner) {
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR)
            public void onLoad(final ServerLoadEvent event) {
                if (event.getType() != ServerLoadEvent.LoadType.STARTUP) return;
                try {
                    recordAll();
                } catch (final RuntimeException unexpected) {
                    // Diagnostics only; plugin loading has already happened.
                }
            }
        }, owner);
    }

    static void recordAll() {
        final PluginStartupIndex.Result index = StartupIndexStage.last();
        if (index == null) return;
        final List<Unloaded> unloaded = find(index.plugins(), StartupIndexStage.lastGraph(),
            name -> Bukkit.getPluginManager().getPlugin(name) != null);
        record(unloaded);
    }

    /** Adds each entry unless a failure for the same jar file was already recorded (bridge refusal, log capture). */
    static void record(final List<Unloaded> unloaded) {
        final Set<String> known = new TreeSet<>();
        for (final var entry : dev.iyanz.sourbycraft.brand.PluginLoadDiagnostics.recent()) {
            // The log capture records "plugins/x.jar", the bridge and this class "x.jar".
            final String jar = entry.pluginJar().replace('\\', '/');
            known.add(jar.substring(jar.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT));
        }
        for (final Unloaded u : unloaded) {
            if (known.add(u.jar().toLowerCase(Locale.ROOT))) {
                dev.iyanz.sourbycraft.brand.PluginLoadDiagnostics.record(u.jar(), u.name(), u.reason());
            }
        }
    }
}
