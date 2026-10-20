package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.SourbyCraftColors;
import dev.iyanz.sourbycraft.api.metrics.PerformanceSnapshot;
import dev.iyanz.sourbycraft.api.metrics.SourbyMetrics;
import dev.iyanz.sourbycraft.perf.MetricsRuntime;
import dev.iyanz.sourbycraft.perf.RegionLocator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

/**
 * Read-only diagnostics: one immutable generation per invocation, no world/OS queries.
 *
 * <p>This class owns the frame (header, freshness, actions, footer), the argument dispatch and
 * the {@code /ram} alias. Each view is its own {@link PerfView} class, registered with one line
 * in {@link PerfViews}; add a view there, not here.</p>
 */
public final class PerfCommand extends Command {
    static final List<String> VIEWS = PerfViews.DEFAULT.names();

    private final PerfViews views;

    public PerfCommand(final String name) {
        this(name, PerfViews.DEFAULT);
    }

    PerfCommand(final String name, final PerfViews views) {
        super(name);
        this.views = views;
        this.description = "SourbyCraft performance diagnostics";
        this.usageMessage = "/" + name + (name.equals("ram") ? "" : " [" + String.join("|", views.names()) + "]");
        this.setPermission("sourbycraft.command." + name);
    }

    @Override
    public boolean execute(final CommandSender sender, final String alias, final String[] args) {
        if (!this.testPermission(sender)) return true;
        final boolean ram = getName().equals("ram");
        final String name = ram ? "memory" : args.length == 0 ? "overview" : args[0].toLowerCase(Locale.ROOT);
        final PerfView view = this.views.find(name, sender);
        // Views that take arguments (player, region) answer every form of their own.
        if (view != null && !ram && view.execute(sender, getName(), args)) {
            return true;
        }
        if (args.length > (ram ? 0 : 1) || view == null) {
            sender.sendMessage(Component.text(this.usageMessage, SourbyCraftColors.DIM));
            return true;
        }
        render(MetricsRuntime.provider(), view, name).forEach(sender::sendMessage);
        return true;
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (!testPermissionSilent(sender) || getName().equals("ram")) return List.of();
        if (args.length == 1) {
            final String prefix = args[0].toLowerCase(Locale.ROOT);
            return this.views.listed(sender).stream().map(PerfView::name).filter(view -> view.startsWith(prefix)).toList();
        }
        if (args.length >= 2) {
            final PerfView view = this.views.findIgnoreCase(args[0], sender);
            return view == null ? List.of() : view.complete(args);
        }
        return List.of();
    }

    /** The lines of {@code view}; an unknown name renders the frame with no body. */
    public static List<Component> render(final SourbyMetrics metrics, final String view) {
        return render(metrics, PerfViews.DEFAULT.find(view), view);
    }

    private static List<Component> render(final SourbyMetrics metrics, final PerfView view, final String name) {
        final PerformanceSnapshot snapshot = metrics.snapshot();
        final List<Component> lines = new ArrayList<>();
        lines.add(UiPanel.header(view == null ? "Performance / " + name : view.title()));
        if (view != null) {
            view.render(snapshot, lines);
        }
        lines.add(TpsCommand.freshness(snapshot.freshness()));
        lines.add(UiPanel.actions(List.of("/perf", "/perf lanes", "/perf network", "/perf plugins")));
        lines.add(UiPanel.footer());
        return List.copyOf(lines);
    }

    public static String health(final PerformanceSnapshot snapshot) {
        return PerfHealthView.health(snapshot);
    }

    // Entry points kept for existing callers and tests; the bodies live in the view classes.

    static List<Component> renderRegion(final SourbyMetrics metrics, final String world, final int x,
                                        final int z, final RegionLocator.Result result) {
        return PerfRegionView.renderRegion(metrics, world, x, z, result);
    }

    static void renderPlayer(final CommandSender sender, final org.bukkit.entity.Player player,
                             final org.bukkit.plugin.Plugin owner,
                             final java.util.function.BiConsumer<CommandSender, Runnable> reply) {
        PerfPlayerView.renderPlayer(sender, player, owner, reply);
    }

    static void renderAwf(final List<Component> lines, final long degradedDirectoryForces, final String degradedReason) {
        PerfAwfView.renderAwf(lines, degradedDirectoryForces, degradedReason);
    }

    static void renderAwf(final List<Component> lines, final long degradedDirectoryForces, final String degradedReason,
                          final long collapsedReadErrors) {
        PerfAwfView.renderAwf(lines, degradedDirectoryForces, degradedReason, collapsedReadErrors);
    }

    static String shortStorageName(final String path) {
        return PerfAwfView.shortStorageName(path);
    }

    static void renderSlowestStartups(final List<Component> lines, final dev.iyanz.sourbycraft.startup.PluginTimings timings) {
        PerfPluginsView.renderSlowestStartups(lines, timings);
    }

    static void renderChunkGeneration(final List<Component> lines) {
        PerfChunksView.renderChunkGeneration(lines);
    }
}
