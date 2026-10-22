package dev.iyanz.sourbycraft.command;

import dev.iyanz.sourbycraft.perf.SchedulerQueueCollector;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.GlobalTick;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.LateTicks;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.Runners;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.Scheduler;
import dev.iyanz.sourbycraft.perf.SchedulerQueueSnapshot.WorldRegions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;

/**
 * {@code /perf scheduler}: the region scheduler's own threads and regions.
 *
 * <p>Rendering is a pure function of a {@link SchedulerQueueSnapshot}, so it is tested without a
 * server. Values read racily are labelled approximate; values the engine does not expose safely are
 * printed as "n/a" with the reason, never estimated. See {@link SchedulerQueueCollector} for which
 * read is which.</p>
 */
public final class PerfSchedulerView {

    /** Shown for every value the engine keeps behind a lock or a package boundary. */
    public static final String NOT_AVAILABLE = "n/a";

    private PerfSchedulerView() {}

    /** A rendered line: a section heading when {@code label} is null, otherwise a label/value row. */
    public record Line(String section, String label, String value) {
        static Line section(final String title) {
            return new Line(title, null, null);
        }

        static Line row(final String label, final String value) {
            return new Line(null, label, value);
        }

        public boolean isSection() {
            return this.label == null;
        }

        /** The plain text a player would read, without colours. */
        public String plain() {
            return this.isSection() ? this.section : this.label + ": " + this.value;
        }
    }

    /** Collects from the live engine and renders; for {@code PerfCommand}. */
    public static void render(final List<Component> out) {
        out.addAll(components(SchedulerQueueCollector.collect()));
    }

    public static List<Component> components(final SchedulerQueueSnapshot snapshot) {
        final List<Component> out = new ArrayList<>();
        for (final Line line : lines(snapshot)) {
            out.add(line.isSection() ? UiPanel.section(line.section()) : UiPanel.row(line.label(), line.value()));
        }
        return out;
    }

    public static List<Line> lines(final SchedulerQueueSnapshot snapshot) {
        final List<Line> lines = new ArrayList<>();
        lines.add(Line.section("Region scheduler"));
        if (!snapshot.available()) {
            lines.add(Line.row("Region scheduler", "unavailable — " + snapshot.unavailableReason()));
            return List.copyOf(lines);
        }
        final Scheduler scheduler = snapshot.scheduler();
        lines.add(Line.row("Scheduler (threaded-regions.scheduler)", scheduler.type()));
        lines.add(Line.row("Threads configured / in pool / alive",
            count(scheduler.configuredThreads()) + " / " + count(scheduler.coreThreads()) + " / " + count(scheduler.aliveThreads())));
        lines.add(Line.row("Tick rate / grid-exponent", (Double.isFinite(scheduler.tickRate())
            ? String.format(Locale.ROOT, "%.1f", scheduler.tickRate()) + " TPS" : "unknown")
            + " / " + count(scheduler.regionShift())));
        final Runners runners = snapshot.runners();
        lines.add(Line.row("Threads on region / global / idle (approximate)", runners.region() + " / "
            + runners.global() + " / " + runners.idle()
            + (runners.unknown() > 0 ? " (+" + runners.unknown() + " unrecognised)" : "")));

        lines.add(Line.section("Global tick"));
        final GlobalTick global = snapshot.global();
        lines.add(Line.row("Next tick late by (approximate)", global.overdueNanos() == SchedulerQueueSnapshot.NONE
            ? NOT_AVAILABLE + " (no scheduled start)"
            : global.overdueNanos() <= SchedulerQueueCollector.OVERDUE_THRESHOLD_NANOS ? "on time" : ms(global.overdueNanos())));
        lines.add(Line.row("Tick in progress", global.runningNanos() == SchedulerQueueSnapshot.NONE
            ? "none" : "running for " + ms(global.runningNanos())));
        lines.add(Line.row("Queued global tasks", NOT_AVAILABLE + " (private queue, no size accessor)"));

        lines.add(Line.section("Regions (approximate: read without locks)"));
        int total = 0, ready = 0, ticking = 0, transitioning = 0, dead = 0, descheduled = 0;
        int overdue = 0, pending = 0, inProgress = 0;
        long worstOverdue = 0L, longestTick = 0L, queuedTick = 0L, queuedChunk = 0L, largestQueue = 0L;
        for (final WorldRegions world : snapshot.worlds()) {
            total += world.total();
            ready += world.ready();
            transitioning += world.transitioning();
            ticking += world.ticking();
            dead += world.dead();
            descheduled += world.descheduled();
            overdue += world.overdue();
            pending += world.withPendingTasks();
            inProgress += world.tickInProgress();
            worstOverdue = Math.max(worstOverdue, world.worstOverdueNanos());
            longestTick = Math.max(longestTick, world.longestTickNanos());
            queuedTick += world.queuedTickTasks();
            queuedChunk += world.queuedChunkTasks();
            largestQueue = Math.max(largestQueue, world.largestRegionQueue());
        }
        lines.add(Line.row("Regions total / ready / ticking", total + " / " + ready + " / " + ticking));
        lines.add(Line.row("Merging-splitting / dead / descheduled", transitioning + " / " + dead + " / " + descheduled));
        lines.add(Line.row("Ready but past their tick start", overdue == 0 ? "0"
            : overdue + " (worst " + ms(worstOverdue) + " late)"));
        lines.add(Line.row("With pending tasks or packets", Integer.toString(pending)));
        lines.add(Line.row("Queued region tasks tick / chunk", queuedTick + " / " + queuedChunk
            + " (largest region " + largestQueue + "; entries, incl. cancelled not yet polled)"));
        lines.add(Line.row("Ticks in progress", inProgress == 0 ? "0"
            : inProgress + " (longest " + ms(longestTick) + ")"));
        for (final WorldRegions world : snapshot.worlds()) {
            lines.add(Line.row("  " + world.world(), world.total() + " regions, " + world.ticking() + " ticking, "
                + world.overdue() + " late"
                + (world.overdue() > 0 ? " (worst " + ms(world.worstOverdueNanos()) + ")" : "")
                + ", " + world.withPendingTasks() + " with tasks, "
                + (world.queuedTickTasks() + world.queuedChunkTasks()) + " queued"));
        }

        lines.add(Line.section("Late tick starts since boot (approximate)"));
        final LateTicks late = snapshot.lateTicks();
        final String threshold = ms(SchedulerQueueCollector.OVERDUE_THRESHOLD_NANOS);
        if (!late.known()) {
            lines.add(Line.row("Late tick starts", NOT_AVAILABLE + " (engine counters not readable)"));
        } else {
            lines.add(Line.row("Region ticks started > " + threshold + " late", lateOf(late.regionLate(), late.regionStarted())));
            lines.add(Line.row("Global ticks started > " + threshold + " late", lateOf(late.globalLate(), late.globalStarted())));
        }

        lines.add(Line.section("Not exposed by the engine"));
        lines.add(Line.row("Scheduler run queue / idle threads", NOT_AVAILABLE + " (private to the scheduler lock)"));
        return List.copyOf(lines);
    }

    static String lateOf(final long late, final long started) {
        if (started <= 0L) {
            return late + " of 0 started";
        }
        return late + " of " + started + " started (" + String.format(Locale.ROOT, "%.2f", 100.0 * late / started) + "%)";
    }

    private static String count(final int value) {
        return value < 0 ? "unknown" : Integer.toString(value);
    }

    static String ms(final long nanos) {
        return String.format(Locale.ROOT, "%.1fms", nanos / 1_000_000.0);
    }
}
