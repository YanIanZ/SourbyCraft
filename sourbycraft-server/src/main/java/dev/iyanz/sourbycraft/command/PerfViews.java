package dev.iyanz.sourbycraft.command;

import java.util.List;
import org.bukkit.command.CommandSender;

/**
 * The {@code /perf} view registry. List order is the order of the usage text and of tab
 * completion. A new view is one class plus one line here.
 */
final class PerfViews {

    private static final PerfView TICK = new PerfTickView();
    private static final PerfView CPU = new PerfCpuView();
    private static final PerfView MEMORY = new PerfMemoryView();
    private static final PerfView REGION = new PerfRegionView();
    private static final PerfView HEALTH = new PerfHealthView();

    static final PerfViews DEFAULT = new PerfViews(List.of(
        new PerfOverviewView(List.of(TICK, CPU, MEMORY, REGION, HEALTH)),
        TICK,
        CPU,
        MEMORY,
        new PerfGcView(),
        new PerfLanesView(),
        new PerfAsyncView(),
        new PerfNetworkView(),
        new PerfGovernorView(),
        PerfView.of("scheduler", lines -> {
            PerfSchedulerView.render(lines);
            PerfTelemetryRows.renderTaskWait(lines);
        }),
        PerfView.of("incidents", PerfIncidentsView::render),
        new PerfStorageView(),
        new PerfAwfView(),
        new PerfPluginsView(),
        new PerfHistoryView(),
        new PerfPlayerView(),
        new PerfChunksView(),
        new PerfEntitiesView(),
        REGION,
        HEALTH));

    private final List<PerfView> views;

    PerfViews(final List<PerfView> views) {
        final java.util.Set<String> names = new java.util.HashSet<>();
        for (final PerfView view : views) {
            if (!names.add(view.name())) throw new IllegalArgumentException("duplicate /perf view " + view.name());
        }
        this.views = List.copyOf(views);
    }

    /** The listed view names, in registration order. */
    List<String> names() {
        return this.views.stream().filter(PerfView::listed).map(PerfView::name).toList();
    }

    /** The listed views {@code sender} may see, in registration order. */
    List<PerfView> listed(final CommandSender sender) {
        return this.views.stream().filter(view -> view.listed() && view.visibleTo(sender)).toList();
    }

    /** The view called exactly {@code name}, or null. */
    PerfView find(final String name) {
        for (final PerfView view : this.views) {
            if (view.name().equals(name)) return view;
        }
        return null;
    }

    /** The view called exactly {@code name} that {@code sender} may see, or null. */
    PerfView find(final String name, final CommandSender sender) {
        final PerfView view = find(name);
        return view != null && view.visibleTo(sender) ? view : null;
    }

    /** The view whose name equals {@code name} ignoring case that {@code sender} may see, or null. */
    PerfView findIgnoreCase(final String name, final CommandSender sender) {
        for (final PerfView view : this.views) {
            if (view.name().equalsIgnoreCase(name)) return view.visibleTo(sender) ? view : null;
        }
        return null;
    }
}
